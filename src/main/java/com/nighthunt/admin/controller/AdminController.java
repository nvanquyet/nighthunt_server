package com.nighthunt.admin.controller;

import com.nighthunt.admin.entity.UserActivityLog;
import com.nighthunt.admin.repository.UserActivityLogRepository;
import com.nighthunt.admin.service.UserActivityService;
import com.nighthunt.ban.entity.Ban;
import com.nighthunt.ban.repository.BanRepository;
import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.elo.service.EloService;
import com.nighthunt.match.entity.Match;
import com.nighthunt.match.entity.MatchPlayerResult;
import com.nighthunt.match.repository.MatchPlayerResultRepository;
import com.nighthunt.match.repository.MatchRepository;
import com.nighthunt.room.entity.Room;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin Controller — full management API for the dashboard.
 * All endpoints require {@code X-Admin-Secret} header matching app config.
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    @Value("${ADMIN_SECRET:change-me-in-production}")
    private String adminSecret;

    private final UserRepository              userRepository;
    private final BanRepository               banRepository;
    private final MatchRepository             matchRepository;
    private final MatchPlayerResultRepository resultRepository;
    private final RoomRepository              roomRepository;
    private final RoomPlayerRepository        roomPlayerRepository;
    private final UserActivityLogRepository   activityLogRepository;
    private final EloService                  eloService;
    private final UserActivityService         activityService;
    private final RedisTemplate<String, Object> redisTemplate;

    // ── Security ──────────────────────────────────────────────────────────────

    private void checkSecret(String secret) {
        if (secret == null || !adminSecret.equals(secret)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid admin secret");
        }
    }

    /** Check Redis for session key — user is online if key exists. */
    private boolean isUserOnline(Long userId) {
        try {
            String key = GameConstants.REDIS_KEY_SESSION_PREFIX + userId;
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            return false;
        }
    }

    /** Count all active sessions via Redis key scan (session:*). */
    private long countOnlineUsers() {
        try {
            Set<String> keys = redisTemplate.keys(GameConstants.REDIS_KEY_SESSION_PREFIX + "*");
            return keys == null ? 0 : keys.size();
        } catch (Exception e) {
            return 0;
        }
    }

    // ── Overview ──────────────────────────────────────────────────────────────

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getOverview(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        checkSecret(secret);

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        long totalUsers       = userRepository.count();
        long newTodayUsers    = userRepository.countCreatedAfter(todayStart);
        long todayMatches     = matchRepository.countByCreatedAtAfter(todayStart);
        long activeBans       = banRepository.countByIsActiveTrue();
        long waitingRooms     = roomRepository.findByStatus("WAITING").size();
        long inGameRooms      = roomRepository.findByStatus("IN_GAME").size();
        long todayLogins      = activityLogRepository.countByCreatedAtAfter(todayStart);

        // Top 10 players
        List<Map<String, Object>> topPlayers = userRepository.findTop10ByOrderByEloDesc()
                .stream().map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",           u.getId());
                    m.put("username",     u.getUsername());
                    m.put("elo",          u.getElo());
                    m.put("tier",         u.getTier());
                    m.put("totalWins",    u.getTotalWins());
                    m.put("totalLosses",  u.getTotalLosses());
                    return m;
                }).collect(Collectors.toList());

        // Recent activity
        List<Map<String, Object>> recentActivity = activityLogRepository
                .findTop30ByOrderByCreatedAtDesc()
                .stream().map(this::logToMap).collect(Collectors.toList());

        // Tier distribution
        Map<String, Long> tierDist = userRepository.findAll().stream()
                .collect(Collectors.groupingBy(User::getTier, Collectors.counting()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalUsers",    totalUsers);
        result.put("newTodayUsers", newTodayUsers);
        result.put("todayMatches",  todayMatches);
        result.put("activeBans",    activeBans);
        result.put("waitingRooms",  waitingRooms);
        result.put("inGameRooms",   inGameRooms);
        result.put("todayLogins",   todayLogins);
        result.put("onlineNow",     countOnlineUsers());
        result.put("topPlayers",    topPlayers);
        result.put("recentActivity", recentActivity);
        result.put("tierDistribution", tierDist);
        return ResponseEntity.ok(result);
    }

    // ── Users ─────────────────────────────────────────────────────────────────

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> getUsers(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @RequestParam(defaultValue = "0")    int    page,
            @RequestParam(defaultValue = "20")   int    size,
            @RequestParam(defaultValue = "")     String search,
            @RequestParam(defaultValue = "id")   String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        checkSecret(secret);

        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        Page<User> userPage = userRepository.searchUsers(search, pageable);

        List<Map<String, Object>> content = userPage.getContent().stream()
                .map(this::userToMap).collect(Collectors.toList());

        return ResponseEntity.ok(pageResponse(content, userPage));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> getUser(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @PathVariable Long id) {
        checkSecret(secret);

        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Map<String, Object> result = userToMap(user);
        result.put("activeBans", banRepository.findAllActiveBansByUserId(id).stream()
                .map(this::banToMap).collect(Collectors.toList()));
        result.put("recentActivity", activityLogRepository
                .findTop20ByUserIdOrderByCreatedAtDesc(id).stream()
                .map(this::logToMap).collect(Collectors.toList()));
        result.put("matchResults", resultRepository.findByUserId(id).stream()
                .limit(20).map(this::resultToMap).collect(Collectors.toList()));
        return ResponseEntity.ok(result);
    }

    /** Root-only: returns ALL fields including passwordHash + full ban/activity/match history. */
    @GetMapping("/users/{id}/full")
    public ResponseEntity<Map<String, Object>> getUserFull(
            @RequestHeader(value = "X-Admin-Secret",  required = false) String secret,
            @RequestHeader(value = "X-Root-Admin",    required = false) String rootFlag,
            @PathVariable Long id) {
        checkSecret(secret);
        if (!"true".equalsIgnoreCase(rootFlag)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Root admin access required");
        }

        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Map<String, Object> result = userToMapFull(user);

        // All bans (active + expired history)
        List<Map<String, Object>> allBans = banRepository.findByUserIdOrderByBannedAtDesc(id)
                .stream().map(this::banToMap).collect(Collectors.toList());
        result.put("allBans",    allBans);
        result.put("activeBans", allBans.stream().filter(b -> Boolean.TRUE.equals(b.get("isActive"))).collect(Collectors.toList()));

        // Full activity log (up to 200 entries)
        result.put("allActivity", activityLogRepository.findTop200ByUserIdOrderByCreatedAtDesc(id)
                .stream().map(this::logToMap).collect(Collectors.toList()));

        // All match results
        result.put("matchResults", resultRepository.findByUserId(id)
                .stream().map(this::resultToMap).collect(Collectors.toList()));

        // Computed stats
        result.put("totalMatches", resultRepository.findByUserId(id).size());

        return ResponseEntity.ok(result);
    }

    /** GET /api/admin/online-users — list all currently online users */
    @GetMapping("/online-users")
    public ResponseEntity<Map<String, Object>> getOnlineUsers(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        checkSecret(secret);

        Set<String> keys = redisTemplate.keys(GameConstants.REDIS_KEY_SESSION_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return ResponseEntity.ok(Map.of("onlineCount", 0, "users", List.of()));
        }

        List<Map<String, Object>> onlineUsers = new ArrayList<>();
        for (String key : keys) {
            // key = "session:{userId}"
            String userIdStr = key.substring(GameConstants.REDIS_KEY_SESSION_PREFIX.length());
            try {
                Long userId = Long.parseLong(userIdStr);
                userRepository.findById(userId).ifPresent(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",       u.getId());
                    m.put("username", u.getUsername());
                    m.put("elo",      u.getElo());
                    m.put("tier",     u.getTier());
                    // Which room are they in?
                    List<RoomPlayer> rps = roomPlayerRepository.findByUserId(u.getId());
                    if (!rps.isEmpty()) {
                        RoomPlayer rp = rps.get(0);
                        m.put("roomId", rp.getRoomId());
                        roomRepository.findById(rp.getRoomId()).ifPresent(r -> {
                            m.put("roomCode",   r.getRoomCode());
                            m.put("roomStatus", r.getStatus());
                            m.put("gameMode",   r.getMode());
                        });
                    }
                    Long ttl = redisTemplate.getExpire(key);
                    m.put("sessionTtlSeconds", ttl);
                    onlineUsers.add(m);
                });
            } catch (NumberFormatException ignored) {}
        }

        onlineUsers.sort(Comparator.comparing(m -> m.get("username").toString()));
        return ResponseEntity.ok(Map.of("onlineCount", onlineUsers.size(), "users", onlineUsers));
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> updateUser(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        checkSecret(secret);

        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (body.containsKey("elo")) {
            int newElo = ((Number) body.get("elo")).intValue();
            user.setElo(newElo);
            user.setTier(eloService.resolveTier(newElo));
        }
        if (body.containsKey("totalWins"))   user.setTotalWins(((Number) body.get("totalWins")).intValue());
        if (body.containsKey("totalLosses")) user.setTotalLosses(((Number) body.get("totalLosses")).intValue());
        if (body.containsKey("totalDraws"))  user.setTotalDraws(((Number) body.get("totalDraws")).intValue());

        userRepository.save(user);
        activityService.log(null, "admin", UserActivityLog.BAN,
                "Admin updated user #" + id + ": " + body.keySet(), null);
        return ResponseEntity.ok(userToMap(user));
    }

    // ── Bans ──────────────────────────────────────────────────────────────────

    @PostMapping("/users/{id}/ban")
    public ResponseEntity<Map<String, Object>> banUser(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        checkSecret(secret);

        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String reason          = (String) body.getOrDefault("reason", "Admin ban");
        int    durationMinutes = ((Number) body.getOrDefault("durationMinutes", 0)).intValue();
        String banTypeStr      = (String) body.getOrDefault("banType", "USER");
        Ban.BanType banType    = Ban.BanType.valueOf(banTypeStr);

        Ban ban = Ban.builder()
                .userId(id)
                .banType(banType)
                .reason(reason)
                .banDurationMinutes(durationMinutes)
                .isActive(true)
                .autoUnbanned(false)
                .build();
        ban = banRepository.save(ban);

        activityService.log(id, user.getUsername(), UserActivityLog.BAN,
                "Banned by admin: " + reason, null);
        return ResponseEntity.ok(banToMap(ban));
    }

    @GetMapping("/bans")
    public ResponseEntity<Map<String, Object>> getBans(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @RequestParam(defaultValue = "0")    int     page,
            @RequestParam(defaultValue = "20")   int     size,
            @RequestParam(required = false)      String  type,
            @RequestParam(required = false)      Boolean active) {
        checkSecret(secret);

        Ban.BanType banType = type != null ? Ban.BanType.valueOf(type) : null;
        Pageable pageable = PageRequest.of(page, size);
        Page<Ban> banPage = banRepository.findFiltered(banType, active, pageable);

        List<Map<String, Object>> content = banPage.getContent().stream()
                .map(this::banToMap).collect(Collectors.toList());
        return ResponseEntity.ok(pageResponse(content, banPage));
    }

    @DeleteMapping("/bans/{id}")
    public ResponseEntity<Map<String, Object>> removeBan(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @PathVariable Long id) {
        checkSecret(secret);

        Ban ban = banRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ban not found"));
        ban.setIsActive(false);
        ban.setAutoUnbanned(true);
        banRepository.save(ban);

        activityService.log(ban.getUserId(), null, UserActivityLog.UNBAN,
                "Ban #" + id + " removed by admin", null);
        return ResponseEntity.ok(Map.of("success", true, "message", "Ban removed"));
    }

    // ── Activity Logs ─────────────────────────────────────────────────────────

    @GetMapping("/activity")
    public ResponseEntity<Map<String, Object>> getActivity(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @RequestParam(defaultValue = "0")  int    page,
            @RequestParam(defaultValue = "50") int    size,
            @RequestParam(required = false)    Long   userId,
            @RequestParam(required = false)    String eventType,
            @RequestParam(required = false)    String from,
            @RequestParam(required = false)    String to) {
        checkSecret(secret);

        LocalDateTime fromDt = parseDate(from, null);
        LocalDateTime toDt   = parseDate(to,   null);

        Pageable pageable = PageRequest.of(page, size);
        Page<UserActivityLog> logPage = activityLogRepository
                .findFiltered(userId, eventType, fromDt, toDt, pageable);

        List<Map<String, Object>> content = logPage.getContent().stream()
                .map(this::logToMap).collect(Collectors.toList());
        return ResponseEntity.ok(pageResponse(content, logPage));
    }

    // ── Rooms (live) ──────────────────────────────────────────────────────────

    @GetMapping("/rooms")
    public ResponseEntity<Map<String, Object>> getLiveRooms(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        checkSecret(secret);

        List<Room> waiting = roomRepository.findByStatus("WAITING");
        List<Room> inGame  = roomRepository.findByStatus("IN_GAME");
        List<Room> all     = new ArrayList<>(waiting);
        all.addAll(inGame);

        List<Map<String, Object>> rooms = all.stream().map(room -> {
            List<RoomPlayer> players = roomPlayerRepository.findByRoomId(room.getId());
            List<Map<String, Object>> playerList = players.stream().map(rp -> {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("userId",   rp.getUserId());
                pm.put("username", userRepository.findById(rp.getUserId())
                        .map(User::getUsername).orElse("?"));
                pm.put("team",     rp.getTeam());
                pm.put("slot",     rp.getSlot());
                pm.put("isReady",  rp.getIsReady());
                return pm;
            }).collect(Collectors.toList());

            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("roomId",     room.getId());
            rm.put("roomCode",   room.getRoomCode());
            rm.put("mode",       room.getMode());
            rm.put("status",     room.getStatus());
            rm.put("isPublic",   room.getIsPublic());
            rm.put("ownerName",  userRepository.findById(room.getOwnerId())
                    .map(User::getUsername).orElse("?"));
            rm.put("players",    playerList);
            rm.put("playerCount", players.size());
            rm.put("createdAt",  room.getCreatedAt());
            return rm;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("waitingCount", waiting.size());
        result.put("inGameCount",  inGame.size());
        result.put("rooms",        rooms);
        return ResponseEntity.ok(result);
    }

    // ── Matches ───────────────────────────────────────────────────────────────

    @GetMapping("/matches")
    public ResponseEntity<Map<String, Object>> getMatches(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @RequestParam(defaultValue = "0")  int    page,
            @RequestParam(defaultValue = "20") int    size,
            @RequestParam(required = false)    String status,
            @RequestParam(required = false)    String mode) {
        checkSecret(secret);

        Pageable pageable = PageRequest.of(page, size);
        Page<Match> matchPage = matchRepository.findFiltered(status, mode, pageable);

        List<Map<String, Object>> content = matchPage.getContent().stream().map(m -> {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id",          m.getId());
            mm.put("matchId",     m.getMatchId());
            mm.put("roomId",      m.getRoomId());
            mm.put("status",      m.getStatus());
            mm.put("gameMode",    m.getGameMode());
            mm.put("winnerTeamId", m.getWinnerTeamId());
            mm.put("endReason",   m.getEndReason());
            mm.put("startedAt",   m.getStartedAt());
            mm.put("finishedAt",  m.getFinishedAt());
            mm.put("createdAt",   m.getCreatedAt());
            if (m.getStartedAt() != null && m.getFinishedAt() != null) {
                long secs = java.time.Duration.between(m.getStartedAt(), m.getFinishedAt()).getSeconds();
                mm.put("durationSecs", secs);
            }
            // player results
            mm.put("players", resultRepository.findByMatchId(m.getMatchId())
                    .stream().map(this::resultToMap).collect(Collectors.toList()));
            return mm;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(pageResponse(content, matchPage));
    }

    // ── Force Cleanup ─────────────────────────────────────────────────────────

    /**
     * POST /admin/cleanup-stale-data
     *
     * One-shot cleanup: closes all rooms/matches with no live players/sessions.
     * Safe to run in production — does NOT delete users or match history.
     * Requires X-Admin-Secret + X-Root-Admin: true headers.
     */
    @PostMapping("/cleanup-stale-data")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Map<String, Object>> cleanupStaleData(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @RequestHeader(value = "X-Root-Admin",   required = false) String rootFlag) {

        checkSecret(secret);
        if (!"true".equalsIgnoreCase(rootFlag)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Root admin access required");
        }

        log.warn("[Admin] Force cleanup-stale-data triggered");

        int roomsClosed      = 0;
        int playersEvicted   = 0;
        int matchesFixed     = 0;
        int redisKeysRemoved = 0;

        // ── 1. Close WAITING/IN_GAME rooms where NO player has an active session ──
        List<Room> activeRooms = new ArrayList<>(roomRepository.findByStatus(GameConstants.ROOM_STATUS_WAITING));
        activeRooms.addAll(roomRepository.findByStatus(GameConstants.ROOM_STATUS_IN_GAME));

        for (Room room : activeRooms) {
            List<RoomPlayer> players = roomPlayerRepository.findByRoomId(room.getId());

            if (players.isEmpty()) {
                room.setStatus(GameConstants.ROOM_STATUS_CLOSED);
                roomRepository.save(room);
                roomsClosed++;
                continue;
            }

            boolean anyOnline = players.stream()
                    .anyMatch(rp -> isUserOnline(rp.getUserId()));

            if (!anyOnline) {
                log.warn("[Cleanup] Closing room {} (code={}, status={}) — {} players, none online",
                        room.getId(), room.getRoomCode(), room.getStatus(), players.size());
                room.setStatus(GameConstants.ROOM_STATUS_CLOSED);
                roomRepository.save(room);
                playersEvicted += players.size();
                roomPlayerRepository.deleteAll(players);
                roomsClosed++;

                // Clear Redis room-state key
                String roomStateKey = GameConstants.REDIS_KEY_ROOM_STATE_PREFIX + room.getId();
                try {
                    if (Boolean.TRUE.equals(redisTemplate.delete(roomStateKey))) redisKeysRemoved++;
                } catch (Exception ignored) {}
            }
        }

        // ── 2. Fix matches stuck in LOBBY or IN_GAME ──────────────────────────
        List<Match> staleMatches = new ArrayList<>(matchRepository.findByStatus(GameConstants.MATCH_STATUS_LOBBY));
        staleMatches.addAll(matchRepository.findByStatus(GameConstants.MATCH_STATUS_IN_GAME));

        for (Match match : staleMatches) {
            boolean roomActive = roomRepository.findById(match.getRoomId())
                    .map(r -> GameConstants.ROOM_STATUS_WAITING.equals(r.getStatus())
                            || GameConstants.ROOM_STATUS_IN_GAME.equals(r.getStatus()))
                    .orElse(false);

            if (!roomActive) {
                log.warn("[Cleanup] Force-finishing stale match {} (status={})",
                        match.getMatchId(), match.getStatus());
                match.setStatus(GameConstants.MATCH_STATUS_FINISHED);
                match.setEndReason("ABANDONED_CLEANUP");
                if (match.getFinishedAt() == null) match.setFinishedAt(LocalDateTime.now());
                matchRepository.save(match);

                // Clear Redis match-session and match-presence keys
                try {
                    String msKey = GameConstants.REDIS_KEY_MATCH_SESSION_PREFIX + match.getMatchId();
                    String mpKey = GameConstants.REDIS_KEY_MATCH_PRESENCE_PREFIX + match.getMatchId();
                    if (Boolean.TRUE.equals(redisTemplate.delete(msKey))) redisKeysRemoved++;
                    if (Boolean.TRUE.equals(redisTemplate.delete(mpKey))) redisKeysRemoved++;
                } catch (Exception ignored) {}

                matchesFixed++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success",        true);
        result.put("roomsClosed",    roomsClosed);
        result.put("playersEvicted", playersEvicted);
        result.put("matchesFixed",   matchesFixed);
        result.put("redisKeysRemoved", redisKeysRemoved);
        result.put("executedAt",     LocalDateTime.now().toString());
        log.warn("[Admin] Force cleanup done: rooms={}, players={}, matches={}, redis={}",
                roomsClosed, playersEvicted, matchesFixed, redisKeysRemoved);

        activityService.log(null, "admin", UserActivityLog.BAN,
                String.format("Force cleanup: rooms=%d players=%d matches=%d redis=%d",
                        roomsClosed, playersEvicted, matchesFixed, redisKeysRemoved), null);

        return ResponseEntity.ok(result);
    }

    // ── DB Stats (for database monitor page) ──────────────────────────────────

    /**
     * GET /admin/db-stats
     * Returns aggregated counts for the database monitor dashboard page.
     */
    @GetMapping("/db-stats")
    public ResponseEntity<Map<String, Object>> getDbStats(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        checkSecret(secret);

        // Rooms by status
        Map<String, Long> roomsByStatus = new LinkedHashMap<>();
        for (Object[] row : roomRepository.countGroupedByStatus()) {
            roomsByStatus.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }

        // Matches by status
        Map<String, Long> matchesByStatus = new LinkedHashMap<>();
        for (Match m : matchRepository.findAll()) {
            matchesByStatus.merge(m.getStatus(), 1L, Long::sum);
        }

        // Stale rooms: WAITING/IN_GAME with zero room_players
        long staleRooms = 0;
        List<Room> activeRooms = new ArrayList<>(roomRepository.findByStatus(GameConstants.ROOM_STATUS_WAITING));
        activeRooms.addAll(roomRepository.findByStatus(GameConstants.ROOM_STATUS_IN_GAME));
        for (Room r : activeRooms) {
            if (roomPlayerRepository.countByRoomId(r.getId()) == 0) staleRooms++;
        }

        // Rooms with players but no one online
        long ghostRooms = 0;
        for (Room r : activeRooms) {
            List<com.nighthunt.room.entity.RoomPlayer> players = roomPlayerRepository.findByRoomId(r.getId());
            if (!players.isEmpty() && players.stream().noneMatch(rp -> isUserOnline(rp.getUserId()))) {
                ghostRooms++;
            }
        }

        // Stuck matches: LOBBY or IN_GAME > 2 hours
        LocalDateTime twoHoursAgo = LocalDateTime.now().minusHours(2);
        long stuckMatches = matchRepository.findByStatus(GameConstants.MATCH_STATUS_LOBBY).stream()
                .filter(m -> m.getCreatedAt() != null && m.getCreatedAt().isBefore(twoHoursAgo)).count()
                + matchRepository.findByStatus(GameConstants.MATCH_STATUS_IN_GAME).stream()
                .filter(m -> m.getCreatedAt() != null && m.getCreatedAt().isBefore(twoHoursAgo)).count();

        // Online sessions
        long onlineSessions = countOnlineUsers();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roomsByStatus",   roomsByStatus);
        result.put("matchesByStatus", matchesByStatus);
        result.put("staleRooms",      staleRooms);
        result.put("ghostRooms",      ghostRooms);
        result.put("stuckMatches",    stuckMatches);
        result.put("onlineSessions",  onlineSessions);
        result.put("totalRoomPlayers", roomPlayerRepository.count());
        return ResponseEntity.ok(result);
    }

    /**
     * GET /admin/rooms/all?status=&page=&size=
     * Paginated room browser — all statuses, sorted newest first.
     */
    @GetMapping("/rooms/all")
    public ResponseEntity<Map<String, Object>> getAllRooms(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @RequestParam(defaultValue = "0")    int    page,
            @RequestParam(defaultValue = "20")   int    size,
            @RequestParam(required = false)      String status) {
        checkSecret(secret);

        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(page, size);
        org.springframework.data.domain.Page<Room> roomPage =
                roomRepository.findFiltered(status != null && !status.isEmpty() ? status : null, pageable);

        List<Map<String, Object>> content = roomPage.getContent().stream().map(room -> {
            int playerCount = roomPlayerRepository.countByRoomId(room.getId());
            boolean anyOnline = playerCount > 0 && roomPlayerRepository.findByRoomId(room.getId())
                    .stream().anyMatch(rp -> isUserOnline(rp.getUserId()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",          room.getId());
            m.put("roomCode",    room.getRoomCode());
            m.put("status",      room.getStatus());
            m.put("mode",        room.getMode());
            m.put("isPublic",    room.getIsPublic());
            m.put("ownerId",     room.getOwnerId());
            m.put("ownerName",   userRepository.findById(room.getOwnerId())
                    .map(u -> u.getUsername()).orElse("?"));
            m.put("playerCount", playerCount);
            m.put("anyOnline",   anyOnline);
            m.put("createdAt",   room.getCreatedAt());
            return m;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(pageResponse(content, roomPage));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> userToMap(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           u.getId());
        m.put("username",     u.getUsername());
        m.put("email",        u.getEmail());
        m.put("elo",          u.getElo());
        m.put("tier",         u.getTier());
        m.put("totalWins",    u.getTotalWins());
        m.put("totalLosses",  u.getTotalLosses());
        m.put("totalDraws",   u.getTotalDraws());
        m.put("isOnline",     isUserOnline(u.getId()));
        m.put("createdAt",    u.getCreatedAt());
        m.put("updatedAt",    u.getUpdatedAt());
        return m;
    }

    /** Root-admin extended map — includes sensitive fields. */
    private Map<String, Object> userToMapFull(User u) {
        Map<String, Object> m = userToMap(u);
        m.put("passwordHash",  u.getPasswordHash());    // bcrypt hash
        // Win-rate
        int total = u.getTotalWins() + u.getTotalLosses() + u.getTotalDraws();
        m.put("totalMatchesPlayed", total);
        m.put("winRate", total > 0
                ? String.format("%.1f%%", (u.getTotalWins() * 100.0) / total)
                : "N/A");
        return m;
    }

    private Map<String, Object> banToMap(Ban b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",               b.getId());
        m.put("userId",           b.getUserId());
        m.put("banType",          b.getBanType());
        m.put("ipAddress",        b.getIpAddress());
        m.put("deviceFingerprint", b.getDeviceFingerprint());
        m.put("reason",           b.getReason());
        m.put("banDurationMinutes", b.getBanDurationMinutes());
        m.put("bannedAt",         b.getBannedAt());
        m.put("expiresAt",        b.getExpiresAt());
        m.put("isActive",         b.getIsActive());
        m.put("isPermanent",      b.isPermanent());
        // resolve username
        if (b.getUserId() != null) {
            m.put("username", userRepository.findById(b.getUserId())
                    .map(User::getUsername).orElse("?"));
        }
        return m;
    }

    private Map<String, Object> logToMap(UserActivityLog l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",        l.getId());
        m.put("userId",    l.getUserId());
        m.put("username",  l.getUsername());
        m.put("eventType", l.getEventType());
        m.put("eventData", l.getEventData());
        m.put("ipAddress", l.getIpAddress());
        m.put("createdAt", l.getCreatedAt());
        return m;
    }

    private Map<String, Object> resultToMap(MatchPlayerResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId",      r.getUserId());
        m.put("matchId",     r.getMatchId());
        m.put("teamId",      r.getTeamId());
        m.put("displayName", r.getDisplayName());
        m.put("kills",       r.getKills());
        m.put("deaths",      r.getDeaths());
        m.put("score",       r.getScore());
        m.put("eloBefore",   r.getEloBefore());
        m.put("eloAfter",    r.getEloAfter());
        m.put("eloChange",   r.getEloChange());
        return m;
    }

    private <T> Map<String, Object> pageResponse(List<T> content, Page<?> page) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("content",       content);
        r.put("totalElements", page.getTotalElements());
        r.put("totalPages",    page.getTotalPages());
        r.put("page",          page.getNumber());
        r.put("size",          page.getSize());
        return r;
    }

    private LocalDateTime parseDate(String s, LocalDateTime fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return LocalDate.parse(s).atStartOfDay();
        } catch (DateTimeParseException e) {
            try { return LocalDateTime.parse(s); } catch (Exception ex) { return fallback; }
        }
    }
}

