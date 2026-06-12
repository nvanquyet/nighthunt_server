package com.nighthunt.match.service;

import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.elo.service.EloService;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.friend.service.PlayerStatusService;
import com.nighthunt.match.adapter.RedisMatchPresenceCache;
import com.nighthunt.match.dto.MatchEndRequest;
import com.nighthunt.match.dto.MatchEndResponse;
import com.nighthunt.match.entity.Match;
import com.nighthunt.match.entity.MatchPlayerResult;
import com.nighthunt.match.repository.MatchPlayerResultRepository;
import com.nighthunt.match.repository.MatchRepository;
import com.nighthunt.match.repository.UserAbandonRecordRepository;
import com.nighthunt.messaging.service.MessageBrokerService;
import com.nighthunt.party.entity.PartyMember;
import com.nighthunt.party.repository.PartyMemberRepository;
import com.nighthunt.party.repository.PartyRepository;
import com.nighthunt.room.entity.Room;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import com.nighthunt.room.service.RoomResponseAssembler;
import com.nighthunt.relay.service.RelaySessionManager;
import com.nighthunt.realtime.service.RealtimeOutboxService;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * BE-30 — Match Result Persistence & ELO Update.
 *
 * <p>Called by {@code POST /api/match/end}. Responsibilities:
 * <ol>
 *   <li>Validate the matchId and that the match is IN_GAME.</li>
 *   <li>Persist per-player result rows ({@link MatchPlayerResult}).</li>
 *   <li>Calculate ELO deltas via {@link EloService} and update {@link User} entities.</li>
 *   <li>Mark the {@link Match} as FINISHED with winner/reason.</li>
 *   <li>Close the relay session (if one exists).</li>
 *   <li>Broadcast {@code match_ended} WS event to all players.</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchResultService {

    private final MatchRepository              matchRepository;
    private final MatchPlayerResultRepository  resultRepository;
    private final UserRepository               userRepository;
    private final EloService                   eloService;
    private final RelaySessionManager          relaySessionManager;
    private final ConnectionManager            connectionManager;
    private final RoomRepository               roomRepository;
    private final RoomPlayerRepository         roomPlayerRepository;
    private final RoomResponseAssembler        roomResponseAssembler;
    private final RedisMatchPresenceCache      matchPresenceCache;
    private final PlayerStatusService          playerStatusService;
    private final RealtimeOutboxService         realtimeOutboxService;
    private final PartyMemberRepository        partyMemberRepository;
    private final PartyRepository              partyRepository;
    private final MessageBrokerService         messageBrokerService;
    private final UserAbandonRecordRepository  abandonRecordRepository;

    // ── Coin rewards (configurable via application.properties) ────────────────
    @Value("${coins.reward.ranked.win:50}")    private long coinsRankedWin;
    @Value("${coins.reward.ranked.draw:25}")   private long coinsRankedDraw;
    @Value("${coins.reward.ranked.loss:10}")   private long coinsRankedLoss;
    @Value("${coins.reward.custom.win:20}")    private long coinsCustomWin;
    @Value("${coins.reward.custom.draw:10}")   private long coinsCustomDraw;
    @Value("${coins.reward.custom.loss:5}")    private long coinsCustomLoss;
    @Value("${match.afk.elo-penalty:50}")      private int afkEloPenalty;

    @Transactional
    public MatchEndResponse processMatchEnd(MatchEndRequest req, boolean isRanked) {
        // 1. Validate match — use SELECT FOR UPDATE so concurrent end-calls for the same
        //    matchId are serialized at DB level, preventing double ELO/coin writes.
        Match match = matchRepository.findByMatchIdForUpdate(req.getMatchId())
                .orElseThrow(() -> new BusinessException(ErrorCodes.MATCH_NOT_FOUND,
                        "Match not found: " + req.getMatchId()));

        if ("FINISHED".equals(match.getStatus())) {
            throw new BusinessException(ErrorCodes.MATCH_NOT_FOUND,
                    "Match already finished: " + req.getMatchId());
        }

        // Group players by team for ELO calc
        Map<Integer, List<MatchEndRequest.PlayerResultEntry>> byTeam = new HashMap<>();
        for (var entry : req.getPlayerResults()) {
            byTeam.computeIfAbsent(entry.getTeamId(), k -> new ArrayList<>()).add(entry);
        }

        // 2. Calculate average ELO per team
        Map<Integer, Double> teamAvgElo = new HashMap<>();
        for (var t : byTeam.entrySet()) {
            double avg = t.getValue().stream()
                    .mapToInt(e -> {
                        User u = userRepository.findById(e.getUserId()).orElse(null);
                        return u != null ? u.getElo() : 1000;
                    })
                    .average().orElse(1000.0);
            teamAvgElo.put(t.getKey(), avg);
        }

        List<MatchEndResponse.PlayerResultRow> resultRows = new ArrayList<>();

        Long postMatchRoomId = isRanked ? null : match.getRoomId();

        // 3. For each player: persist result + update ELO/coins if ranked
        for (var entry : req.getPlayerResults()) {
            User user = userRepository.findById(entry.getUserId()).orElse(null);
            if (user == null) {
                log.warn("[MatchEnd] User {} not found, skipping ELO update", entry.getUserId());
                continue;
            }

            int myTeam = entry.getTeamId();
            double actualScore;
            if (req.getWinnerTeamId() == -1)           actualScore = 0.5; // draw
            else if (req.getWinnerTeamId() == myTeam)  actualScore = 1.0; // win
            else                                       actualScore = 0.0; // loss

            int eloBefore = user.getElo();
            int eloChange = 0;
            // Check the DB record written by AbandonPenaltyService — more reliable than the
            // Redis presence snapshot which may have already expired by the time match ends.
            boolean abandonedAndPenalized = hasAbandonPenaltyRecord(req.getMatchId(), entry.getUserId());
            // Also check the live Redis snapshot (covers the window between abandon and end-match
            // where the DB record exists but match hasn't fully concluded yet).
            boolean abandoned = abandonedAndPenalized
                    || isAbandoned(req.getMatchId(), entry.getUserId());

            if (isRanked) {
                // Determine opponent average ELO (for 2-team matches)
                double opponentAvg = teamAvgElo.entrySet().stream()
                        .filter(e -> !e.getKey().equals(myTeam))
                        .mapToDouble(Map.Entry::getValue)
                        .average().orElse(1000.0);

                int delta = eloService.calculateDelta(eloBefore, opponentAvg, actualScore);
                eloChange = eloService.applyDelta(user, delta, actualScore);
                if (abandoned) {
                    if (abandonedAndPenalized) {
                        // AbandonPenaltyService already deducted ELO mid-match.
                        // Applying afkEloPenalty on top would double-penalize the player.
                        // Revert the positive ELO gain from applyDelta (treat as a loss/zero),
                        // but do NOT subtract afkEloPenalty again.
                        user.setElo(Math.max(0, eloBefore));
                        user.setTier(eloService.resolveTier(user.getElo()));
                        eloChange = user.getElo() - eloBefore;
                        log.info("[MatchEnd] Abandoned player {} already penalized by AbandonPenaltyService " +
                                "(matchId={}) — skipping afkEloPenalty to avoid double-charge. " +
                                "ELO reverted to pre-match value: {} → {}",
                                entry.getUserId(), req.getMatchId(), eloBefore, user.getElo());
                    } else {
                        // Player abandoned but no prior penalty record — apply end-of-match penalty.
                        int afkAdjustedElo = Math.max(0, user.getElo() - afkEloPenalty);
                        user.setElo(afkAdjustedElo);
                        user.setTier(eloService.resolveTier(afkAdjustedElo));
                        eloChange = afkAdjustedElo - eloBefore;
                    }
                }
            }

            // Coin reward
            long coinReward = resolveCoinReward(actualScore, isRanked);
            if (abandoned) {
                coinReward = 0L;
            }
            user.setCoins(user.getCoins() + coinReward);

            userRepository.save(user);
            try {
                playerStatusService.setBackToOnline(entry.getUserId());
                playerStatusService.updateCurrentRoom(entry.getUserId(), postMatchRoomId);
            } catch (Exception e) {
                log.warn("[MatchEnd] Failed to reset player status for user {}: {}",
                        entry.getUserId(), e.getMessage());
            }

            // Persist result row
            int placement;
            if (req.getWinnerTeamId() == -1)                    placement = 0; // draw
            else if (req.getWinnerTeamId() == entry.getTeamId()) placement = 1; // winner
            else                                                 placement = 2; // loser

            MatchPlayerResult row = MatchPlayerResult.builder()
                    .matchId(req.getMatchId())
                    .userId(entry.getUserId())
                    .teamId(entry.getTeamId())
                    .displayName(entry.getDisplayName())
                    .kills(entry.getKills())
                    .deaths(entry.getDeaths())
                    .score(entry.getScore())
                    .eloBefore(eloBefore)
                    .eloAfter(user.getElo())
                    .eloChange(eloChange)
                    .placement(placement)
                    .build();
            resultRepository.save(row);

            resultRows.add(MatchEndResponse.PlayerResultRow.builder()
                    .userId(entry.getUserId())
                    .displayName(entry.getDisplayName())
                    .teamId(entry.getTeamId())
                    .kills(entry.getKills())
                    .deaths(entry.getDeaths())
                    .score(entry.getScore())
                    .eloBefore(eloBefore)
                    .eloAfter(user.getElo())
                    .eloChange(eloChange)
                    .tier(user.getTier())
                    .coinChange(coinReward)
                    .coinsTotal(user.getCoins())
                    .build());
        }

        // 4. Update Match entity
        match.setStatus("FINISHED");
        match.setWinnerTeamId(req.getWinnerTeamId());
        match.setEndReason(req.getEndReason());
        match.setFinishedAt(LocalDateTime.now());
        matchRepository.save(match);

        if (isRanked) {
            finishRoom(match);
        }

        // 5. Close relay session
        relaySessionManager.getByRoomId(match.getRoomId())
                .ifPresent(s -> relaySessionManager.finishSession(s.getSessionToken()));

        if (!isRanked) {
            resetCustomRoomForNextLobby(match);
        }

        clearPresenceCache(req.getMatchId(), req.getPlayerResults());
        if (isRanked) {
            resetRankedPartyState(req.getPlayerResults().stream()
                    .map(MatchEndRequest.PlayerResultEntry::getUserId)
                    .toList());
        }

        // 6. Broadcast match_ended WS event to all participants
        MatchEndResponse response = MatchEndResponse.builder()
                .matchId(req.getMatchId())
                .winnerTeamId(req.getWinnerTeamId())
                .endReason(req.getEndReason())
                .playerResults(resultRows)
                .build();

        for (var entry : req.getPlayerResults()) {
            connectionManager.sendToUser(entry.getUserId(), "match_ended", response);
        }
        realtimeOutboxService.enqueue("events.match.ended", response);

        log.info("[MatchEnd] Processed match {} winner={} reason={} players={}",
                req.getMatchId(), req.getWinnerTeamId(), req.getEndReason(), req.getPlayerResults().size());

        return response;
    }

    private void finishRoom(Match match) {
        roomRepository.findById(match.getRoomId()).ifPresent(room -> {
            room.setStatus(GameConstants.ROOM_STATUS_FINISHED);
            roomRepository.save(room);
            connectionManager.broadcastToRoom(room.getId(), "room_status_changed",
                    java.util.Map.of(
                            "newStatus", GameConstants.ROOM_STATUS_FINISHED,
                            "room", roomResponseAssembler.toResponseById(room.getId())));
        });
    }

    private void resetCustomRoomForNextLobby(Match finishedMatch) {
        roomRepository.findById(finishedMatch.getRoomId()).ifPresent(room -> {
            String nextMatchId = UUID.randomUUID().toString();
            Match nextMatch = Match.builder()
                    .matchId(nextMatchId)
                    .roomId(room.getId())
                    .status(GameConstants.MATCH_STATUS_LOBBY)
                    .gameMode(room.getMode())
                    .build();
            matchRepository.save(nextMatch);

            room.setMatchId(nextMatchId);
            room.setStatus(GameConstants.ROOM_STATUS_WAITING);
            roomRepository.save(room);

            resetRoomPlayersForLobby(room);

            var response = roomResponseAssembler.toResponseById(room.getId());
            connectionManager.broadcastToRoom(room.getId(), "room_status_changed",
                    java.util.Map.of(
                            "newStatus", GameConstants.ROOM_STATUS_WAITING,
                            "room", response));
            connectionManager.broadcastToRoom(room.getId(), "room_updated", response);

            log.info("[MatchEnd] Custom room {} reset to WAITING with next matchId={}",
                    room.getId(), nextMatchId);
        });
    }

    private void resetRoomPlayersForLobby(Room room) {
        List<RoomPlayer> players = roomPlayerRepository.findByRoomId(room.getId());
        for (RoomPlayer player : players) {
            player.setIsReady(room.getOwnerId().equals(player.getUserId()));
            roomPlayerRepository.save(player);
            connectionManager.updateUserRoom(player.getUserId(), room.getId());
            try {
                playerStatusService.updateCurrentRoom(player.getUserId(), room.getId());
            } catch (Exception e) {
                log.warn("[MatchEnd] Failed to keep user {} in custom room {}: {}",
                        player.getUserId(), room.getId(), e.getMessage());
            }
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     * Returns true if a {@link UserAbandonRecord} exists for this (matchId, userId) pair,
     * meaning {@link AbandonPenaltyService} has already deducted ELO mid-match.
     * Used to prevent double-penalizing at end-of-match.
     */
    private boolean hasAbandonPenaltyRecord(String matchId, Long userId) {
        if (matchId == null || userId == null) {
            return false;
        }
        return abandonRecordRepository.findByMatchId(matchId)
                .stream()
                .anyMatch(r -> r.getUserId().equals(userId));
    }

    private boolean isAbandoned(String matchId, Long userId) {
        return matchPresenceCache.get(matchId, userId)
                .map(snapshot -> snapshot.isAbandoned())
                .orElse(false);
    }

    private void clearPresenceCache(String matchId, List<MatchEndRequest.PlayerResultEntry> results) {
        for (MatchEndRequest.PlayerResultEntry entry : results) {
            matchPresenceCache.delete(matchId, entry.getUserId());
        }
    }

    private void resetRankedPartyState(Collection<Long> userIds) {
        try {
            Set<Long> partyIds = userIds.stream()
                    .map(userId -> partyMemberRepository.findByUserId(userId)
                            .map(PartyMember::getPartyId)
                            .orElse(null))
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());

            for (Long partyId : partyIds) {
                partyRepository.findById(partyId).ifPresent(party -> {
                    if (!"RANKED".equals(party.getPartyMode())
                            && !"IN_GAME".equals(party.getPartyStatus())
                            && !"IN_QUEUE".equals(party.getPartyStatus())) {
                        return;
                    }

                    String oldStatus = party.getPartyStatus();
                    party.setPartyStatus("IDLE");
                    party.setPartyMode("NONE");
                    partyRepository.save(party);
                    try {
                        messageBrokerService.publishPartyStatusChanged(partyId, oldStatus, "IDLE");
                    } catch (Exception ex) {
                        log.warn("[MatchEnd] Failed to publish party {} IDLE status: {}", partyId, ex.getMessage());
                    }
                });
            }
        } catch (Exception ex) {
            log.warn("[MatchEnd] Failed to reset ranked party state: {}", ex.getMessage());
        }
    }

    private long resolveCoinReward(double actualScore, boolean isRanked) {
        if (isRanked) {
            if (actualScore >= 1.0) return coinsRankedWin;
            if (actualScore <= 0.0) return coinsRankedLoss;
            return coinsRankedDraw;
        } else {
            if (actualScore >= 1.0) return coinsCustomWin;
            if (actualScore <= 0.0) return coinsCustomLoss;
            return coinsCustomDraw;
        }
    }
}
