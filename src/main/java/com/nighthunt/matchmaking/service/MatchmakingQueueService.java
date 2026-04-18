package com.nighthunt.matchmaking.service;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.dedicatedserver.dto.ServerAllocateResponse;
import com.nighthunt.dedicatedserver.service.DedicatedServerService;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.gamemode.dto.GameModeDTO;
import com.nighthunt.gamemode.service.GameModeService;
import com.nighthunt.matchmaking.entity.MatchmakingEntry;
import com.nighthunt.matchmaking.repository.MatchmakingEntryRepository;
import com.nighthunt.room.dto.RoomResponse;
import com.nighthunt.room.service.RoomService;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * BE-28 — Ranked Matchmaking Queue Service.
 *
 * <p>Players call {@link #enqueue} to enter the queue.  A scheduled task
 * ({@link #processTick}) runs every few seconds, tries to form full lobbies
 * from SEARCHING entries whose ELO ranges overlap sufficiently, and on match
 * fires a {@code match_found} WebSocket event to each matched player.</p>
 *
 * <h3>ELO range expansion</h3>
 * Every expansion tick (default 15 s) each waiting entry's search window grows
 * by {@code ELO_EXPAND_STEP} on each side, up to the configured maximum.
 * This progressively relaxes skill requirements to prevent indefinite queuing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchmakingQueueService {

    // ── Config ────────────────────────────────────────────────────────────────
    /** Initial ELO window half-width (±X around player's ELO). */
    @Value("${matchmaking.elo.initial-range:100}")
    private int initialEloRange;

    /** Expand the window by this much (each side) on each expansion tick. */
    @Value("${matchmaking.elo.expand-step:50}")
    private int expandStep;

    /** Expand every N seconds. */
    @Value("${matchmaking.elo.expand-interval-seconds:15}")
    private int expandIntervalSeconds;

    /** Maximum total range (±N from player ELO). */
    @Value("${matchmaking.elo.max-range:500}")
    private int maxEloRange;

    // ── Dependencies ─────────────────────────────────────────────────────────
    private final MatchmakingEntryRepository entryRepository;
    private final UserRepository              userRepository;
    private final ConnectionManager           connectionManager;
    private final RoomService                 roomService;
    private final DedicatedServerService      dsService;
    private final GameModeService             gameModeService;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Add a player to the matchmaking queue (or refresh if already queued).
     *
     * @param userId   the player
     * @param gameMode e.g. "2v2"
     * @param mapId    MapEntry.mapId the player wants (e.g. "map_01"), null = any
     * @param platform "MOBILE" | "PC" | null (unknown / not sent)
     */
    @Transactional
    public void enqueue(Long userId, String gameMode, String mapId, String platform) {
        // Validate mode against DB — rejects modes not in the DB or disabled
        GameModeDTO mode = gameModeService.getGameModeByKey(gameMode);
        if (!"AVAILABLE".equalsIgnoreCase(mode.getModeStatus()) || !mode.isMatchmakingEnabled()) {
            throw new BusinessException(ErrorCodes.MATCH_NOT_FOUND,
                    "Game mode not available for matchmaking: " + gameMode);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.USER_NOT_FOUND,
                        "User not found: " + userId));

        // Persist platform on the user record so it is available in ProfileResponse
        if (platform != null && !platform.equalsIgnoreCase(user.getPlatform())) {
            user.setPlatform(platform);
            userRepository.save(user);
        }

        // Upsert: remove old entry if present
        entryRepository.deleteByUserId(userId);

        int elo = user.getElo();
        MatchmakingEntry entry = MatchmakingEntry.builder()
                .userId(userId)
                .elo(elo)
                .gameMode(gameMode.toLowerCase())
                .mapId(mapId)
                .platform(platform)
                .queuedAt(LocalDateTime.now())
                .searchMinElo(Math.max(0, elo - initialEloRange))
                .searchMaxElo(elo + initialEloRange)
                .status("SEARCHING")
                .build();

        entryRepository.save(entry);
        log.info("[MM] User {} (ELO={}) queued for {} map={} platform={}", userId, elo, gameMode, mapId, platform);
    }

    /**
     * Remove a player from the queue (called on leave / disconnect).
     */
    @Transactional
    public void dequeue(Long userId) {
        entryRepository.findByUserId(userId).ifPresent(e -> {
            e.setStatus("CANCELLED");
            entryRepository.save(e);
            log.info("[MM] User {} dequeued", userId);
        });
    }

    /**
     * Scheduled: try to form matches from SEARCHING entries.
     * Runs every 5 seconds.
     */
    @Scheduled(fixedDelayString = "${matchmaking.tick.interval-ms:5000}")
    public void processTick() {
        expandWindows();

        // Process each DB-configured mode where matchmaking is enabled
        for (GameModeDTO mode : gameModeService.getMatchmakingEnabledModes()) {
            List<MatchmakingEntry> candidates = entryRepository.findSearchingByMode(mode.getModeKey());

            // Dev/test modes: allow single-player match formation so DS container boot
            // can be tested without waiting for a full lobby.
            int minRequired = mode.isDevMode() ? 1 : mode.getTotalPlayers();
            if (candidates.size() < minRequired) continue;

            tryFormMatches(candidates, mode);
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     * Expand search windows for all entries that have been waiting longer than
     * {@code expandIntervalSeconds}.
     */
    private void expandWindows() {
        LocalDateTime threshold = LocalDateTime.now().minus(expandIntervalSeconds, ChronoUnit.SECONDS);
        List<MatchmakingEntry> stale = entryRepository.findSearchingQueuedBefore(threshold);

        for (MatchmakingEntry e : stale) {
            int currentRange = (e.getSearchMaxElo() - e.getSearchMinElo()) / 2;
            if (currentRange < maxEloRange) {
                e.setSearchMinElo(Math.max(0, e.getSearchMinElo() - expandStep));
                e.setSearchMaxElo(e.getSearchMaxElo() + expandStep);
                entryRepository.save(e);
                log.debug("[MM] Expanded window for user {} to [{}, {}]",
                        e.getUserId(), e.getSearchMinElo(), e.getSearchMaxElo());
            }
        }
    }

    /**
     * Greedy match formation: iterate candidates in queue order; try to build a
     * full lobby where all players' ELO windows overlap around a common midpoint.
     */
    private void tryFormMatches(List<MatchmakingEntry> candidates, GameModeDTO mode) {
        // Dev modes only need 1 player to form a "match" (DS boot test, no opponent)
        int needed = mode.isDevMode() ? 1 : mode.getTotalPlayers();
        Set<Long> used = new HashSet<>();

        for (MatchmakingEntry anchor : candidates) {
            if (used.contains(anchor.getUserId())) continue;

            // Collect compatible players (dev mode: anchor alone is sufficient)
            List<MatchmakingEntry> group = new ArrayList<>();
            group.add(anchor);

            if (!mode.isDevMode()) {
                for (MatchmakingEntry other : candidates) {
                    if (used.contains(other.getUserId())) continue;
                    if (other.getUserId().equals(anchor.getUserId())) continue;
                    if (isCompatible(anchor, other)) {
                        group.add(other);
                        if (group.size() == needed) break;
                    }
                }
            }

            if (group.size() >= needed) {
                formMatch(group, mode);
                group.forEach(e -> used.add(e.getUserId()));
            }
        }
    }


    /**
     * Two entries are compatible if their ELO windows intersect AND they want the same map
     * (null mapId = any map, compatible with everyone).
     */
    private boolean isCompatible(MatchmakingEntry a, MatchmakingEntry b) {
        boolean eloOk = a.getSearchMaxElo() >= b.getSearchMinElo()
                && b.getSearchMaxElo() >= a.getSearchMinElo();
        if (!eloOk) return false;

        // null map = wildcard; two non-null maps must match
        if (a.getMapId() != null && b.getMapId() != null && !a.getMapId().equals(b.getMapId())) {
            return false;
        }

        // Platform: two known platforms must match; null = "any", always compatible
        if (a.getPlatform() != null && b.getPlatform() != null
                && !a.getPlatform().equalsIgnoreCase(b.getPlatform())) {
            return false;
        }

        return true;
    }

    /**
     * Mark entries as MATCHED and immediately start DS allocation.
     * No accept/decline phase — when players are grouped the DS boots right away
     * and {@code match_ready} is broadcast to all players.
     *
     * {@code mode.isDevMode()} only controls the minimum player count
     * (1 instead of totalPlayers) set in {@link #tryFormMatches}. Once a group
     * is formed the logic here is identical for all modes.
     */
    private void formMatch(List<MatchmakingEntry> group, GameModeDTO mode) {
        // Prefer first non-null mapId in group (anchor is first)
        String resolvedMapId = group.stream()
                .map(MatchmakingEntry::getMapId)
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElse(null);

        String lobbyToken = UUID.randomUUID().toString();

        for (MatchmakingEntry entry : group) {
            entry.setStatus("MATCHED");
            entry.setLobbyToken(lobbyToken);
            entry.setMapId(resolvedMapId);
            entryRepository.save(entry);
        }

        log.info("[MM] Match formed — mode={} lobbyToken={} players={} devMode={}",
                mode.getModeKey(), lobbyToken,
                group.stream().map(MatchmakingEntry::getUserId).toList(),
                mode.isDevMode());

        createMatchedRoom(group);
    }

    // ── Room Creation ─────────────────────────────────────────────────────────

    private void createMatchedRoom(List<MatchmakingEntry> group) {
        String modeKey  = group.get(0).getGameMode();
        List<Long> userIds = group.stream().map(MatchmakingEntry::getUserId).toList();
        String lToken    = group.get(0).getLobbyToken();
        String mapId     = group.get(0).getMapId();

        log.info("[MM] createMatchedRoom ▶ mode={} mapId={} players={} lobbyToken={}", modeKey, mapId, userIds, lToken);

        try {
            // Step 1: Create room
            RoomResponse room = roomService.createRankedRoom(userIds, modeKey, mapId);
            log.info("[MM] Step 1 OK: Room created — roomCode={} matchId={}", room.getRoomCode(), room.getMatchId());

            // Step 2: Allocate DS
            ServerAllocateResponse ds = dsService.allocateServerForMatch("vn", mapId, group.size(), room.getMatchId());
            log.info("[MM] Step 2 OK: DS allocated — serverId={} ds={}:{} devMode={}", ds.getServerId(), ds.getIp(), ds.getPort(), ds.getDevSecret() != null);

            Map<String, Object> payload = new HashMap<>();
            payload.put("lobbyToken",   lToken);
            payload.put("gameMode",     modeKey);
            payload.put("mapId",        mapId);
            payload.put("roomCode",     room.getRoomCode());
            payload.put("roomId",       room.getRoomId());
            payload.put("matchId",      room.getMatchId());
            payload.put("dsIp",         ds.getIp());
            payload.put("dsPort",       ds.getPort());
            payload.put("sessionToken", ds.getSessionToken());
            if (ds.getDevSecret() != null) payload.put("devSecret", ds.getDevSecret());

            // Step 3: Clean up queue entries
            for (MatchmakingEntry e : group) {
                entryRepository.delete(e);
            }
            log.info("[MM] Step 3 OK: Queue entries deleted for {} players", group.size());

            // Step 4: Broadcast match_ready via WS
            int sent = 0;
            for (Long uid : userIds) {
                connectionManager.sendToUser(uid, "match_ready", payload);
                log.debug("[MM] match_ready sent to userId={}", uid);
                sent++;
            }
            log.info("[MM] Step 4 OK: match_ready broadcast complete — sent={}/{} matchId={} ds={}:{}",
                    sent, userIds.size(), room.getMatchId(), ds.getIp(), ds.getPort());

            // Step 5: Transition ranked room to IN_GAME so RoomOwnerTransferService
            // cannot disband it while the DS is booting and players are loading the scene.
            roomService.markRankedRoomInGame(room.getMatchId());
            log.info("[MM] Step 5 OK: room transitioned to IN_GAME — matchId={}", room.getMatchId());

        } catch (Exception ex) {
            log.error("[MM] createMatchedRoom FAILED — mode={} players={} err={}", modeKey, userIds, ex.getMessage(), ex);
            // Re-queue all players on failure
            int requeued = 0;
            for (MatchmakingEntry e : group) {
                try { enqueue(e.getUserId(), e.getGameMode(), e.getMapId(), e.getPlatform()); requeued++; } catch (Exception ignored) {}
            }
            log.warn("[MM] Re-queued {}/{} players after failure", requeued, group.size());
        }
    }
}
