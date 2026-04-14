package com.nighthunt.match.service;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.elo.service.EloService;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.match.dto.MatchEndRequest;
import com.nighthunt.match.dto.MatchEndResponse;
import com.nighthunt.match.entity.Match;
import com.nighthunt.match.entity.MatchPlayerResult;
import com.nighthunt.match.repository.MatchPlayerResultRepository;
import com.nighthunt.match.repository.MatchRepository;
import com.nighthunt.relay.service.RelaySessionManager;
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

    // ── Coin rewards (configurable via application.properties) ────────────────
    @Value("${coins.reward.ranked.win:50}")    private long coinsRankedWin;
    @Value("${coins.reward.ranked.draw:25}")   private long coinsRankedDraw;
    @Value("${coins.reward.ranked.loss:10}")   private long coinsRankedLoss;
    @Value("${coins.reward.custom.win:20}")    private long coinsCustomWin;
    @Value("${coins.reward.custom.draw:10}")   private long coinsCustomDraw;
    @Value("${coins.reward.custom.loss:5}")    private long coinsCustomLoss;

    @Transactional
    public MatchEndResponse processMatchEnd(MatchEndRequest req, boolean isRanked) {
        // 1. Validate match
        Match match = matchRepository.findByMatchId(req.getMatchId())
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

            if (isRanked) {
                // Determine opponent average ELO (for 2-team matches)
                double opponentAvg = teamAvgElo.entrySet().stream()
                        .filter(e -> !e.getKey().equals(myTeam))
                        .mapToDouble(Map.Entry::getValue)
                        .average().orElse(1000.0);

                int delta = eloService.calculateDelta(eloBefore, opponentAvg, actualScore);
                eloChange = eloService.applyDelta(user, delta, actualScore);
            }

            // Coin reward
            long coinReward = resolveCoinReward(actualScore, isRanked);
            user.setCoins(user.getCoins() + coinReward);

            userRepository.save(user);

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

        // 5. Close relay session
        relaySessionManager.getByRoomId(match.getRoomId())
                .ifPresent(s -> relaySessionManager.finishSession(s.getSessionToken()));

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

        log.info("[MatchEnd] Processed match {} winner={} reason={} players={}",
                req.getMatchId(), req.getWinnerTeamId(), req.getEndReason(), req.getPlayerResults().size());

        return response;
    }

    // ── Internal ─────────────────────────────────────────────────────────────

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
