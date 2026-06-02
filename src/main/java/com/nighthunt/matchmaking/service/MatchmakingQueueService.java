package com.nighthunt.matchmaking.service;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.config.gameconfig.RuntimeConfigService;
import com.nighthunt.dedicatedserver.dto.ServerAllocateResponse;
import com.nighthunt.dedicatedserver.service.DedicatedServerService;
import com.nighthunt.friend.service.PlayerStatusService;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.gamemode.dto.GameModeDTO;
import com.nighthunt.gamemode.service.GameModeService;
import com.nighthunt.map.service.GameMapService;
import com.nighthunt.messaging.service.MessageBrokerService;
import com.nighthunt.matchmaking.entity.MatchmakingEntry;
import com.nighthunt.matchmaking.repository.MatchmakingEntryRepository;
import com.nighthunt.party.repository.PartyMemberRepository;
import com.nighthunt.party.repository.PartyRepository;
import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.room.dto.RoomResponse;
import com.nighthunt.room.entity.Room;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import com.nighthunt.room.service.RoomService;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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

    private static final String MATCHER_LOCK_KEY = "lock:matchmaking:tick";
    private static final Duration MATCHER_LOCK_TTL = Duration.ofMinutes(2);
    private static final RedisScript<Long> RENEW_MATCHER_LOCK_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);
    private static final RedisScript<Long> RELEASE_MATCHER_LOCK_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    // ── Dependencies ─────────────────────────────────────────────────────────
    private final MatchmakingEntryRepository entryRepository;
    private final UserRepository              userRepository;
    private final ConnectionManager           connectionManager;
    private final RoomService                 roomService;
    private final RoomPlayerRepository        roomPlayerRepository;
    private final RoomRepository              roomRepository;
    private final PartyMemberRepository       partyMemberRepository;
    private final PartyRepository             partyRepository;
    private final DedicatedServerService      dsService;
    private final GameModeService             gameModeService;
    private final GameMapService              gameMapService;
    private final PlayerStatusService         playerStatusService;
    private final RuntimeConfigService        runtimeConfig;
    private final MessageBrokerService        messageBrokerService;
    private final StringRedisTemplate         redisTemplate;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Add a player to the matchmaking queue (or refresh if already queued).
     *
     * @param userId   the player
     * @param gameMode e.g. "2v2"
     * @param mapId    MapEntry.mapId the player wants (e.g. "map_01"), null = any
     * @param platform "MOBILE" | "PC" | null (unknown / not sent)
     */
    @Retryable(retryFor = CannotAcquireLockException.class, maxAttempts = 3,
               backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public void enqueue(Long userId, String gameMode, String mapId, String platform) {
        enqueueSolo(userId, gameMode, mapId, platform);
    }

    @Retryable(retryFor = CannotAcquireLockException.class, maxAttempts = 3,
               backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public void enqueueSolo(Long userId, String gameMode, String mapId, String platform) {
        ensureUserCanEnterMatchmaking(userId);
        if (partyMemberRepository.existsByUserId(userId)) {
            throw new BusinessException(ErrorCodes.PARTY_ALREADY_IN_PARTY,
                    "Leave or disband your ranked party before joining solo matchmaking");
        }

        enqueueInternal(userId, gameMode, mapId, platform, null, 1, true);
    }

    @Retryable(retryFor = CannotAcquireLockException.class, maxAttempts = 3,
               backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public void enqueuePartyMember(Long userId, String gameMode, String mapId, String platform) {
        ensureUserCanEnterMatchmaking(userId);
        enqueueInternal(userId, gameMode, mapId, platform, null, 1, true);
    }

    @Retryable(retryFor = CannotAcquireLockException.class, maxAttempts = 3,
               backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public void enqueuePartyMember(
            Long userId,
            String gameMode,
            String mapId,
            String platform,
            Long partyId,
            int partySize,
            boolean allowFill
    ) {
        ensureUserCanEnterMatchmaking(userId);
        enqueueInternal(userId, gameMode, mapId, platform, partyId, partySize, allowFill);
    }

    private void enqueueInternal(
            Long userId,
            String gameMode,
            String mapId,
            String platform,
            Long partyId,
            int partySize,
            boolean allowFill
    ) {
        // Validate mode against DB — rejects modes not in the DB or disabled
        GameModeDTO mode = gameModeService.getGameModeByKey(gameMode);
        if (!"AVAILABLE".equalsIgnoreCase(mode.getModeStatus()) || !mode.isMatchmakingEnabled()) {
            throw new BusinessException(ErrorCodes.MATCH_NOT_FOUND,
                    "Game mode not available for matchmaking: " + gameMode);
        }
        int normalizedPartySize = Math.max(1, partySize);
        if (normalizedPartySize > mode.getPlayersPerTeam()) {
            throw new BusinessException(ErrorCodes.PARTY_SIZE_MISMATCH,
                    "Queue unit size exceeds team size for " + gameMode);
        }
        if (partyId != null && allowFill && !mode.isAllowFill()) {
            throw new BusinessException(ErrorCodes.MATCH_NOT_FOUND,
                    "Fill Party is disabled for game mode: " + gameMode);
        }

        // Enforce platform restriction set on the game mode
        if (platform != null && !"ALL".equalsIgnoreCase(mode.getPlatformFilter())) {
            if (!mode.getPlatformFilter().equalsIgnoreCase(platform)) {
                throw new BusinessException(ErrorCodes.MATCH_NOT_FOUND,
                        "Game mode '" + gameMode + "' is restricted to " + mode.getPlatformFilter() + " players");
            }
        }

        if (mapId != null && !mapId.isBlank()
                && !gameMapService.isMapValidForMatchmaking(mapId, gameMode, mode.getTotalPlayers())) {
            throw new BusinessException(ErrorCodes.MATCH_NOT_FOUND,
                    "Map does not support matchmaking mode: " + mapId + " / " + gameMode);
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
                .queueGroupId(partyId == null ? "solo:" + userId : "party:" + partyId)
                .partyId(partyId)
                .partySize(normalizedPartySize)
                .allowFill(allowFill)
                .queuedAt(LocalDateTime.now())
                .searchMinElo(Math.max(0, elo - runtimeConfig.getInt("matchmaking.elo.initialRange", 100)))
                .searchMaxElo(elo + runtimeConfig.getInt("matchmaking.elo.initialRange", 100))
                .status("SEARCHING")
                .build();

        entryRepository.save(entry);
        log.info("[MM] User {} (ELO={}) queued for {} map={} platform={} group={} partySize={} allowFill={}",
                userId, elo, gameMode, mapId, platform, entry.getQueueGroupId(), normalizedPartySize, allowFill);
    }

    private void ensureUserCanEnterMatchmaking(Long userId) {
        // Guard 1: user must not be in an active room (IN_GAME or WAITING)
        if (roomPlayerRepository.existsUserInActiveRoom(userId)) {
            throw new BusinessException(ErrorCodes.ROOM_ALREADY_IN_ROOM,
                    "Leave the active room before joining matchmaking");
        }
        // Guard 2: user's party must not be in CUSTOM mode (in a custom lobby)
        // This catches the case where the user is a party member whose party joined a room.
        partyMemberRepository.findByUserId(userId).ifPresent(pm -> {
            com.nighthunt.party.entity.Party party =
                    partyRepository.findById(pm.getPartyId()).orElse(null);
            if (party != null && "CUSTOM".equals(party.getPartyMode())) {
                throw new BusinessException(ErrorCodes.PARTY_IN_CUSTOM_MODE,
                        "Leave the custom lobby before joining ranked matchmaking");
            }
        });
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

        // Also check if the user is in an active room associated with a ranked match
        List<RoomPlayer> activeRoomPlayers = roomPlayerRepository.findActiveRoomsByUserId(userId);
        for (RoomPlayer rp : activeRoomPlayers) {
            roomRepository.findById(rp.getRoomId()).ifPresent(room -> {
                if (room.getMatchId() != null && !room.getMatchId().isBlank()) {
                    log.info("[MM] User {} dequeued but is in active ranked room {} (matchId={}) — disbanding room to release players",
                            userId, room.getId(), room.getMatchId());

                    // 1. Notify other players in this room that the match is cancelled
                    List<RoomPlayer> players = roomPlayerRepository.findByRoomId(room.getId());
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("reason", "A player cancelled the queue");
                    payload.put("matchId", room.getMatchId());

                    for (RoomPlayer member : players) {
                        try {
                            connectionManager.sendToUser(member.getUserId(), "match_cancelled", payload);
                            playerStatusService.setBackToOnline(member.getUserId());
                        } catch (Exception ex) {
                            log.debug("[MM] Failed to notify user {} of match cancellation: {}", member.getUserId(), ex.getMessage());
                        }
                    }

                    // 2. Disband the room
                    try {
                        roomService.disbandRoom(room.getId(), room.getOwnerId());
                    } catch (Exception ex) {
                        log.error("[MM] Failed to disband room {} on dequeue: {}", room.getId(), ex.getMessage());
                    }

                    // 3. Reclaim server
                    try {
                        dsService.reclaimServerForMatch(room.getMatchId());
                    } catch (Exception ex) {
                        log.warn("[MM] Failed to reclaim server for matchId={}: {}", room.getMatchId(), ex.getMessage());
                    }
                }
            });
        }
    }

    /**
     * Return the current queue status for a user.
     * Used by the client on reconnect to sync local UI state with server reality.
     * Returns null if the user has no active (SEARCHING or MATCHED) queue entry.
     */
    public QueueStatusDTO getQueueStatus(Long userId) {
        return entryRepository.findByUserId(userId)
                .filter(e -> "SEARCHING".equals(e.getStatus()) || "MATCHED".equals(e.getStatus()))
                .map(e -> {
                    long waitSeconds = java.time.Duration.between(e.getQueuedAt(),
                            LocalDateTime.now()).getSeconds();
                    return new QueueStatusDTO(
                            e.getStatus(),
                            e.getGameMode(),
                            e.getLobbyToken(),
                            e.getQueuedAt(),
                            waitSeconds);
                })
                .orElse(null);
    }

    /** Snapshot of a user's active queue entry, returned to the client. */
    public record QueueStatusDTO(
            String status,
            String gameMode,
            String lobbyToken,
            LocalDateTime queuedAt,
            long waitSeconds) {}

    /**
     * Scheduled: try to form matches from SEARCHING entries.
     * Runs every 5 seconds.
     */
    @Scheduled(fixedDelayString = "${matchmaking.tick.interval-ms:5000}")
    public void processTick() {
        String lockToken = acquireMatcherLock();
        if (lockToken == null) {
            return;
        }

        try {
            expandWindows();

            // Process each DB-configured mode where matchmaking is enabled
            for (GameModeDTO mode : gameModeService.getMatchmakingEnabledModes()) {
                if (!renewMatcherLock(lockToken)) {
                    log.warn("[MM] Matcher lock expired before mode={}; stopping tick", mode.getModeKey());
                    return;
                }
                List<MatchmakingEntry> candidates = entryRepository.findSearchingByMode(mode.getModeKey());
                tryFormMatches(candidates, mode, lockToken);
            }
        } finally {
            releaseMatcherLock(lockToken);
        }
    }

    /**
     * Scheduled: cancel SEARCHING entries older than STALE_QUEUE_TIMEOUT_MINUTES.
     * Handles clients that crashed/force-quit after queuing before WS close fired.
     * Notifies the user via WS if they have since reconnected.
     */
    private static final int STALE_QUEUE_TIMEOUT_MINUTES = 15;

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void sweepStaleQueueEntries() {
        LocalDateTime cutoff = LocalDateTime.now()
                .minus(STALE_QUEUE_TIMEOUT_MINUTES, java.time.temporal.ChronoUnit.MINUTES);
        List<MatchmakingEntry> stale = entryRepository.findSearchingQueuedBefore(cutoff);
        if (stale.isEmpty()) return;

        log.info("[MM] Sweeping {} stale SEARCHING entries older than {} min", stale.size(), STALE_QUEUE_TIMEOUT_MINUTES);
        for (MatchmakingEntry e : stale) {
            e.setStatus("CANCELLED");
            entryRepository.save(e);
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("lobbyToken", e.getLobbyToken() != null ? e.getLobbyToken() : "");
                payload.put("reason", "Queue timed out after " + STALE_QUEUE_TIMEOUT_MINUTES
                        + " minutes without finding a match. Please try again.");
                connectionManager.sendToUser(e.getUserId(), "match_cancelled", payload);
            } catch (Exception ex) {
                log.debug("[MM] Could not WS-notify userId={} of stale cancel: {}", e.getUserId(), ex.getMessage());
            }
            log.info("[MM] Stale queue entry cancelled: userId={} mode={} queuedAt={}",
                    e.getUserId(), e.getGameMode(), e.getQueuedAt());
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     * Expand search windows for all entries that have been waiting longer than
     * {@code expandIntervalSeconds}.
     */
    private void expandWindows() {
        int expandIntervalSec = runtimeConfig.getInt("matchmaking.elo.expandIntervalSec", 15);
        int maxRange          = runtimeConfig.getInt("matchmaking.elo.maxRange", 500);
        int step              = runtimeConfig.getInt("matchmaking.elo.expandStep", 50);
        LocalDateTime threshold = LocalDateTime.now().minus(expandIntervalSec, ChronoUnit.SECONDS);
        List<MatchmakingEntry> stale = entryRepository.findSearchingQueuedBefore(threshold);

        for (MatchmakingEntry e : stale) {
            int currentRange = (e.getSearchMaxElo() - e.getSearchMinElo()) / 2;
            if (currentRange < maxRange) {
                e.setSearchMinElo(Math.max(0, e.getSearchMinElo() - step));
                e.setSearchMaxElo(e.getSearchMaxElo() + step);
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
    private void tryFormMatches(List<MatchmakingEntry> candidates, GameModeDTO mode, String lockToken) {
        List<MatchUnit> units = buildUnits(candidates);
        Set<String> usedGroups = new HashSet<>();

        for (MatchUnit anchor : units) {
            if (usedGroups.contains(anchor.groupId())) continue;

            MatchBuild build = new MatchBuild(mode.getPlayersPerTeam(), mode.isDevMode());
            if (!build.tryAdd(anchor)) continue;

            if (!mode.isDevMode()) {
                for (MatchUnit other : units) {
                    if (usedGroups.contains(other.groupId())) continue;
                    if (other.groupId().equals(anchor.groupId())) continue;
                    if (build.isComplete()) break;
                    if (isCompatible(anchor, other)) {
                        build.tryAdd(other);
                    }
                }
            }

            if (build.isComplete()) {
                if (!renewMatcherLock(lockToken)) {
                    log.warn("[MM] Matcher lock expired while forming mode={}; stopping tick", mode.getModeKey());
                    return;
                }
                List<MatchmakingEntry> group = build.entriesWithAssignedTeams();
                formMatch(group, mode);
                build.units().forEach(unit -> usedGroups.add(unit.groupId()));
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

    private boolean isCompatible(MatchUnit a, MatchUnit b) {
        for (MatchmakingEntry left : a.entries()) {
            for (MatchmakingEntry right : b.entries()) {
                if (!isCompatible(left, right)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Mark entries as MATCHED and start the match immediately (auto-accept).
     * Bước xác nhận thủ công (match_found → /accept) đã bị loại bỏ:
     * tất cả entries được pre-accept ngay khi match hình thành, DS được cấp phát
     * và {@code match_ready} được broadcast trực tiếp — không cần client gọi /accept.
     *
     * Resolves mapId: anchor's map wins; if null, leave null (DS allocator may randomize).
     */
    private void formMatch(List<MatchmakingEntry> group, GameModeDTO mode) {
        // Prefer first non-null mapId in group (anchor is first)
        String resolvedMapId = group.stream()
                .map(MatchmakingEntry::getMapId)
                .filter(m -> m != null && !m.isBlank())
                .filter(gameMapService::isMapValid)
                .findFirst()
                .orElse(null);

        String lobbyToken = UUID.randomUUID().toString();

        // Auto-accept: không gửi match_found, không đợi player xác nhận.
        // createMatchedRoom() sẽ gửi match_ready trực tiếp với DS info.
        for (MatchmakingEntry entry : group) {
            entry.setStatus("MATCHED");
            entry.setLobbyToken(lobbyToken);
            entry.setAcceptStatus("ACCEPTED");
            entry.setMapId(resolvedMapId);
            entryRepository.save(entry);
        }

        log.info("[MM] {} match formed (auto-accept), lobbyToken={}, players={}",
                mode.getModeKey(), lobbyToken,
                group.stream().map(MatchmakingEntry::getUserId).toList());

        createMatchedRoom(group);
    }

    // ── Accept / Decline ─────────────────────────────────────────────────────

    /**
     * Player accepts the pending match offer.
     * When ALL players in the group accept, a ranked room is created and
     * {@code match_ready} is broadcast to everyone with DS info.
     */
    @Transactional
    public void accept(Long userId, String lobbyToken) {
        MatchmakingEntry entry = entryRepository.findByUserId(userId)
                .filter(e -> lobbyToken.equals(e.getLobbyToken()) && "MATCHED".equals(e.getStatus()))
                .orElseThrow(() -> new BusinessException(ErrorCodes.MATCH_NOT_FOUND,
                        "No pending match found for lobbyToken: " + lobbyToken));

        // Guard: auto-accept flow already set all entries to ACCEPTED and called createMatchedRoom().
        // A second /accept call arriving late (e.g., manual API call or client retry) must be a no-op
        // to prevent a duplicate ranked room + DS allocation.
        if ("ACCEPTED".equals(entry.getAcceptStatus())) {
            log.info("[MM] accept(): userId={} lobbyToken={} is already ACCEPTED (auto-accept flow) — skipping duplicate createMatchedRoom",
                    userId, lobbyToken);
            return;
        }

        entry.setAcceptStatus("ACCEPTED");
        entryRepository.save(entry);
        log.info("[MM] User {} accepted match lobbyToken={}", userId, lobbyToken);

        List<MatchmakingEntry> group = entryRepository.findByLobbyToken(lobbyToken);
        boolean allAccepted = group.stream().allMatch(e -> "ACCEPTED".equals(e.getAcceptStatus()));
        if (allAccepted) {
            createMatchedRoom(group);
        }
    }

    /**
     * Player declines (or timeout auto-declines). Re-queues any players who had already accepted.
     */
    @Transactional
    public void decline(Long userId, String lobbyToken) {
        List<MatchmakingEntry> group = entryRepository.findByLobbyToken(lobbyToken);
        List<Long> allPlayerIds = group.stream().map(MatchmakingEntry::getUserId).toList();

        for (MatchmakingEntry e : group) {
            if ("CANCELLED".equals(e.getStatus())) continue;
            String prevMode = e.getGameMode();
            e.setStatus("CANCELLED");
            e.setAcceptStatus("DECLINED");
            entryRepository.save(e);
            // Re-queue anyone who isn't the decliner and was still in-lobby
            if (!e.getUserId().equals(userId)) {
                enqueueInternal(e.getUserId(), prevMode, e.getMapId(), e.getPlatform(),
                        e.getPartyId(), e.getPartySize(), e.isAllowFill());
            }
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("lobbyToken", lobbyToken);
        payload.put("reason", "A player declined");
        for (Long pid : allPlayerIds) {
            connectionManager.sendToUser(pid, "match_cancelled", payload);
        }
        log.info("[MM] User {} declined match lobbyToken={}. Re-queued {} players.",
                userId, lobbyToken, allPlayerIds.size() - 1);
    }

    // ── Room Creation ─────────────────────────────────────────────────────────

    private void createMatchedRoom(List<MatchmakingEntry> group) {
        String modeKey  = group.get(0).getGameMode(); // stored as lowercase string e.g. "2v2"
        List<Long> userIds = group.stream().map(MatchmakingEntry::getUserId).toList();
        String lToken    = group.get(0).getLobbyToken();
        String mapId     = group.get(0).getMapId(); // unified mapId set in formMatch()

        Map<Long, Integer> teamByUserId = group.stream()
                .filter(e -> e.getAssignedTeam() != null)
                .collect(Collectors.toMap(
                        MatchmakingEntry::getUserId,
                        MatchmakingEntry::getAssignedTeam,
                        (left, right) -> left,
                        LinkedHashMap::new));

        RoomResponse room = null;
        try {
            room = roomService.createRankedRoom(userIds, modeKey, mapId, teamByUserId);
            ServerAllocateResponse ds = dsService.allocateServerForMatch("vn", mapId, group.size(), room.getMatchId());

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
            // Only populated when ds.docker.enabled=false (local dev/test).
            // Developer uses this to simulate DS boot: POST /api/ds/register.
            if (ds.getDevSecret() != null) payload.put("devSecret", ds.getDevSecret());

            if (room.getPlayers() != null && !room.getPlayers().isEmpty()) {
                List<Map<String, Object>> playerEntries = new ArrayList<>();
                for (var p : room.getPlayers()) {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("userId",   p.getUserId());
                    entry.put("username", p.getUsername());
                    entry.put("team",     p.getTeam() != null ? p.getTeam() : 1);
                    User u = userRepository.findById(p.getUserId()).orElse(null);
                    entry.put("elo",  u != null ? u.getElo()  : 0);
                    entry.put("tier", u != null ? u.getTier() : "");
                    playerEntries.add(entry);
                }
                payload.put("players", playerEntries);
            }

            // Clean up queue entries
            for (MatchmakingEntry e : group) {
                entryRepository.delete(e);
            }

            for (Long uid : userIds) {
                connectionManager.sendToUser(uid, "match_ready", payload);
                try { playerStatusService.setInGame(uid); } catch (Exception ignored) {}
            }
            roomService.markRankedRoomInGame(room.getMatchId());
            markRankedPartiesInGame(group);

            log.info("[MM] Match ready: room={}, mode={}, ds={}:{}, players={}",
                    room.getRoomCode(), modeKey, ds.getIp(), ds.getPort(), userIds);
        } catch (Exception ex) {
            log.error("[MM] Failed to create matched room: {}", ex.getMessage(), ex);
            // Disband the room so players are not left stuck in an active room
            if (room != null) {
                try {
                    roomService.disbandRoom(room.getRoomId(), userIds.get(0));
                    log.info("[MM] Disbanded stale room {} after DS failure", room.getRoomId());
                } catch (Exception disbandEx) {
                    log.warn("[MM] Failed to disband room {} after DS failure: {}", room.getRoomId(), disbandEx.getMessage());
                }
            }
            // Re-queue all players on failure
            for (MatchmakingEntry e : group) {
                try {
                    enqueueInternal(e.getUserId(), e.getGameMode(), e.getMapId(), e.getPlatform(),
                            e.getPartyId(), e.getPartySize(), e.isAllowFill());
                } catch (Exception ignored) {}
            }
        }
    }

    private List<MatchUnit> buildUnits(List<MatchmakingEntry> candidates) {
        return candidates.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getQueueGroupId() != null ? e.getQueueGroupId() : "solo:" + e.getUserId(),
                        LinkedHashMap::new,
                        Collectors.toList()))
                .entrySet()
                .stream()
                .map(e -> new MatchUnit(
                        e.getKey(),
                        e.getValue().stream()
                                .sorted(Comparator.comparing(MatchmakingEntry::getQueuedAt))
                                .toList()))
                .sorted(Comparator.comparing(MatchUnit::queuedAt))
                .toList();
    }

    private String acquireMatcherLock() {
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(MATCHER_LOCK_KEY, token, MATCHER_LOCK_TTL);
            return Boolean.TRUE.equals(acquired) ? token : null;
        } catch (Exception ex) {
            log.error("[MM] Cannot acquire matcher lock; skipping tick: {}", ex.getMessage());
            return null;
        }
    }

    private boolean renewMatcherLock(String token) {
        try {
            Long renewed = redisTemplate.execute(
                    RENEW_MATCHER_LOCK_SCRIPT,
                    List.of(MATCHER_LOCK_KEY),
                    token,
                    String.valueOf(MATCHER_LOCK_TTL.toMillis()));
            return renewed != null && renewed == 1L;
        } catch (Exception ex) {
            log.error("[MM] Cannot renew matcher lock: {}", ex.getMessage());
            return false;
        }
    }

    private void releaseMatcherLock(String token) {
        try {
            redisTemplate.execute(RELEASE_MATCHER_LOCK_SCRIPT, List.of(MATCHER_LOCK_KEY), token);
        } catch (Exception ex) {
            log.warn("[MM] Cannot release matcher lock token={}: {}", token, ex.getMessage());
        }
    }

    private void markRankedPartiesInGame(List<MatchmakingEntry> group) {
        try {
            group.stream()
                    .map(MatchmakingEntry::getPartyId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .forEach(partyId -> partyRepository.findById(partyId).ifPresent(party -> {
                        String oldStatus = party.getPartyStatus();
                        party.setPartyStatus("IN_GAME");
                        party.setPartyMode("RANKED");
                        partyRepository.save(party);
                        try {
                            messageBrokerService.publishPartyStatusChanged(partyId, oldStatus, "IN_GAME");
                        } catch (Exception ex) {
                            log.warn("[MM] Failed to publish party {} IN_GAME status: {}", partyId, ex.getMessage());
                        }
                    }));
        } catch (Exception ex) {
            log.warn("[MM] Failed to mark ranked parties IN_GAME: {}", ex.getMessage());
        }
    }

    private record MatchUnit(String groupId, List<MatchmakingEntry> entries) {
        int size() {
            return entries.size();
        }

        boolean lockedTeam() {
            return entries.stream().anyMatch(e -> !e.isAllowFill());
        }

        LocalDateTime queuedAt() {
            return entries.stream()
                    .map(MatchmakingEntry::getQueuedAt)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(LocalDateTime.MIN);
        }
    }

    private static final class MatchBuild {
        private final int teamCapacity;
        private final boolean devMode;
        private final List<MatchUnit> team1 = new ArrayList<>();
        private final List<MatchUnit> team2 = new ArrayList<>();

        MatchBuild(int teamCapacity, boolean devMode) {
            this.teamCapacity = Math.max(1, teamCapacity);
            this.devMode = devMode;
        }

        boolean tryAdd(MatchUnit unit) {
            if (canAdd(team1, unit)) {
                team1.add(unit);
                return true;
            }
            if (!devMode && isTeamValid(team1) && canAdd(team2, unit)) {
                team2.add(unit);
                return true;
            }
            return false;
        }

        boolean isComplete() {
            if (devMode) {
                return teamSize(team1) > 0;
            }
            return isTeamValid(team1) && isTeamValid(team2);
        }

        List<MatchmakingEntry> entriesWithAssignedTeams() {
            List<MatchmakingEntry> entries = new ArrayList<>();
            appendAssigned(entries, team1, GameConstants.TEAM_1);
            appendAssigned(entries, team2, GameConstants.TEAM_2);
            return entries;
        }

        List<MatchUnit> units() {
            List<MatchUnit> units = new ArrayList<>(team1);
            units.addAll(team2);
            return units;
        }

        private boolean canAdd(List<MatchUnit> team, MatchUnit unit) {
            if (teamLocked(team)) {
                return false;
            }
            if (unit.lockedTeam() && !team.isEmpty()) {
                return false;
            }
            return teamSize(team) + unit.size() <= teamCapacity;
        }

        private boolean isTeamValid(List<MatchUnit> team) {
            if (team.isEmpty()) {
                return false;
            }
            int size = teamSize(team);
            return size == teamCapacity || teamLocked(team);
        }

        private boolean teamLocked(List<MatchUnit> team) {
            return team.stream().anyMatch(MatchUnit::lockedTeam);
        }

        private int teamSize(List<MatchUnit> team) {
            return team.stream().mapToInt(MatchUnit::size).sum();
        }

        private void appendAssigned(List<MatchmakingEntry> target, List<MatchUnit> team, int teamId) {
            for (MatchUnit unit : team) {
                for (MatchmakingEntry entry : unit.entries()) {
                    entry.setAssignedTeam(teamId);
                    target.add(entry);
                }
            }
        }
    }
}
