package com.nighthunt.analytics.service;

import com.nighthunt.analytics.dto.*;
import com.nighthunt.analytics.entity.ServerMetricsSnapshot;
import com.nighthunt.analytics.repository.ServerMetricsSnapshotRepository;
import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.match.entity.MatchPlayerResult;
import com.nighthunt.match.repository.MatchPlayerResultRepository;
import com.nighthunt.match.repository.MatchRepository;
import com.nighthunt.matchmaking.entity.MatchmakingEntry;
import com.nighthunt.matchmaking.repository.MatchmakingEntryRepository;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsService {

    private final ServerMetricsSnapshotRepository snapshotRepo;
    private final MatchmakingEntryRepository matchmakingEntryRepo;
    private final MatchRepository matchRepo;
    private final MatchPlayerResultRepository matchPlayerResultRepo;
    private final UserRepository userRepo;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final DateTimeFormatter LABEL_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_FMT   = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    // ─── Time-series (snapshot history) ─────────────────────────────────────

    /**
     * Returns time-series rows for the last {@code hours} hours, sampled every
     * minute (raw snapshots — downsampled on the client via Chart.js).
     */
    public TimeSeriesDTO getOnlineHistory(int hours) {
        LocalDateTime from = LocalDateTime.now().minusHours(hours);
        List<ServerMetricsSnapshot> snaps =
                snapshotRepo.findBySnapshotAtBetweenOrderBySnapshotAtAsc(from, LocalDateTime.now());

        List<String> labels    = new ArrayList<>();
        List<Integer> online   = new ArrayList<>();
        List<Integer> queue    = new ArrayList<>();
        List<Integer> inGame   = new ArrayList<>();
        List<Integer> dsActive = new ArrayList<>();

        DateTimeFormatter fmt = hours <= 3 ? LABEL_FMT : DAY_FMT;
        for (ServerMetricsSnapshot s : snaps) {
            labels.add(s.getSnapshotAt().format(fmt));
            online.add(s.getOnlineUsers());
            queue.add(s.getQueueDepth());
            inGame.add(s.getInGameRooms());
            dsActive.add(s.getActiveDs());
        }

        return TimeSeriesDTO.builder()
                .labels(labels)
                .onlineUsers(online)
                .queueDepth(queue)
                .inGameRooms(inGame)
                .activeDsServers(dsActive)
                .build();
    }

    // ─── Matchmaking live stats ───────────────────────────────────────────────

    public MatchmakingStatsDTO getMatchmakingStats() {
        List<Object[]> byMode = matchmakingEntryRepo.countSearchingByMode();
        Map<String, Integer> queueByMode = new LinkedHashMap<>();
        int total = 0;
        for (Object[] row : byMode) {
            String mode = (String) row[0];
            int    cnt  = ((Number) row[1]).intValue();
            queueByMode.put(mode, cnt);
            total += cnt;
        }

        // Average wait time for currently searching entries
        List<MatchmakingEntry> searching = matchmakingEntryRepo.findAll().stream()
                .filter(e -> "SEARCHING".equals(e.getStatus()))
                .collect(Collectors.toList());

        double avgWaitSeconds = searching.stream()
                .mapToLong(e -> java.time.Duration.between(e.getQueuedAt(), LocalDateTime.now()).getSeconds())
                .average()
                .orElse(0);

        // Matches found in last hour (as a throughput proxy)
        long matchesLastHour = matchRepo.countByFinishedAtAfter(LocalDateTime.now().minusHours(1));

        return MatchmakingStatsDTO.builder()
                .totalInQueue(total)
                .queueByMode(queueByMode)
                .avgWaitSeconds((int) avgWaitSeconds)
                .matchesLastHour((int) matchesLastHour)
                .build();
    }

    // ─── Match history charts ─────────────────────────────────────────────────

    /**
     * Matches finished per hour over the last {@code hours} hours, broken down by mode.
     */
    public MatchStatsDTO getMatchStats(int hours) {
        LocalDateTime from = LocalDateTime.now().minusHours(hours);
        List<Object[]> byMode = matchRepo.countFinishedByModeAfter(from);

        Map<String, Integer> matchesByMode = new LinkedHashMap<>();
        int total = 0;
        for (Object[] row : byMode) {
            String mode = (String) row[0];
            int    cnt  = ((Number) row[1]).intValue();
            matchesByMode.put(mode, cnt);
            total += cnt;
        }

        long totalUsers    = userRepo.count();
        long newUsersToday = userRepo.countCreatedAfter(LocalDateTime.now().withHour(0).withMinute(0).withSecond(0));

        return MatchStatsDTO.builder()
                .totalMatchesInPeriod(total)
                .matchesByMode(matchesByMode)
                .totalRegisteredUsers(totalUsers)
                .newUsersToday(newUsersToday)
                .build();
    }

    // ─── ELO distribution ────────────────────────────────────────────────────

    /**
     * Returns bucket counts for the ELO histogram:
     * 0-999 (Bronze), 1000-1199 (Silver), 1200-1399 (Gold),
     * 1400-1599 (Platinum), 1600-1799 (Diamond), 1800+ (Master)
     */
    public EloDistributionDTO getEloDistribution() {
        List<User> allUsers = userRepo.findAll();
        Map<String, Integer> buckets = new LinkedHashMap<>();
        buckets.put("Bronze (<1000)",  0);
        buckets.put("Silver (1000-1199)", 0);
        buckets.put("Gold (1200-1399)",   0);
        buckets.put("Platinum (1400-1599)", 0);
        buckets.put("Diamond (1600-1799)",  0);
        buckets.put("Master (1800+)",       0);

        for (User u : allUsers) {
            int elo = u.getElo();
            String key;
            if      (elo < 1000) key = "Bronze (<1000)";
            else if (elo < 1200) key = "Silver (1000-1199)";
            else if (elo < 1400) key = "Gold (1200-1399)";
            else if (elo < 1600) key = "Platinum (1400-1599)";
            else if (elo < 1800) key = "Diamond (1600-1799)";
            else                  key = "Master (1800+)";
            buckets.merge(key, 1, Integer::sum);
        }

        return EloDistributionDTO.builder()
                .buckets(buckets)
                .totalPlayers(allUsers.size())
                .averageElo((int) allUsers.stream().mapToInt(User::getElo).average().orElse(1000))
                .build();
    }

    // ─── Top players leaderboard ──────────────────────────────────────────────

    public List<TopPlayerDTO> getTopPlayers(int limit) {
        List<User> top = userRepo.findTop10ByOrderByEloDesc();

        Set<String> onlineKeys;
        try {
            onlineKeys = redisTemplate.keys(GameConstants.REDIS_KEY_SESSION_PREFIX + "*");
        } catch (Exception e) {
            onlineKeys = Collections.emptySet();
        }
        final Set<String> finalOnlineKeys = onlineKeys != null ? onlineKeys : Collections.emptySet();

        return top.stream()
                .limit(limit)
                .map(u -> {
                    String sessionKey = GameConstants.REDIS_KEY_SESSION_PREFIX + u.getId();
                    boolean isOnline = finalOnlineKeys.contains(sessionKey);

                    List<MatchPlayerResult> results = matchPlayerResultRepo.findByUserId(u.getId());
                    int totalKills  = results.stream().mapToInt(MatchPlayerResult::getKills).sum();
                    int totalDeaths = results.stream().mapToInt(MatchPlayerResult::getDeaths).sum();
                    double kda = totalDeaths > 0 ? (double) totalKills / totalDeaths : totalKills;

                    return TopPlayerDTO.builder()
                            .userId(u.getId())
                            .username(u.getUsername())
                            .elo(u.getElo())
                            .tier(u.getTier())
                            .totalWins(u.getTotalWins())
                            .totalLosses(u.getTotalLosses())
                            .totalDraws(u.getTotalDraws())
                            .totalMatches(u.getTotalWins() + u.getTotalLosses() + u.getTotalDraws())
                            .totalKills(totalKills)
                            .totalDeaths(totalDeaths)
                            .kda(Math.round(kda * 100.0) / 100.0)
                            .isOnline(isOnline)
                            .isBanned(Boolean.TRUE.equals(u.getIsBanned()))
                            .build();
                })
                .collect(Collectors.toList());
    }

    // ─── Live player status ───────────────────────────────────────────────────

    /**
     * Returns per-player live status for the most recently active users.
     * Status: ONLINE | IN_QUEUE | IN_GAME | OFFLINE
     */
    public List<PlayerStatusDTO> getPlayerStatuses(int limit) {
        Set<String> onlineKeys;
        try {
            onlineKeys = redisTemplate.keys(GameConstants.REDIS_KEY_SESSION_PREFIX + "*");
        } catch (Exception e) {
            onlineKeys = Collections.emptySet();
        }
        final Set<String> finalOnlineKeys = onlineKeys != null ? onlineKeys : Collections.emptySet();

        // Parse online user IDs from Redis keys
        Set<Long> onlineUserIds = finalOnlineKeys.stream()
                .map(k -> {
                    try { return Long.parseLong(k.substring(GameConstants.REDIS_KEY_SESSION_PREFIX.length())); }
                    catch (NumberFormatException ex) { return null; }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Players currently in matchmaking queue
        Set<Long> inQueueIds = matchmakingEntryRepo.findAll().stream()
                .filter(e -> "SEARCHING".equals(e.getStatus()))
                .map(MatchmakingEntry::getUserId)
                .collect(Collectors.toSet());

        // Build result for the most recently online users (up to limit)
        return onlineUserIds.stream()
                .limit(limit)
                .map(uid -> {
                    User u = userRepo.findById(uid).orElse(null);
                    if (u == null) return null;

                    String status;
                    if (inQueueIds.contains(uid))  status = "IN_QUEUE";
                    else                            status = "ONLINE";

                    return PlayerStatusDTO.builder()
                            .userId(uid)
                            .username(u.getUsername())
                            .elo(u.getElo())
                            .tier(u.getTier())
                            .status(status)
                            .isBanned(Boolean.TRUE.equals(u.getIsBanned()))
                            .build();
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(PlayerStatusDTO::getStatus)
                        .thenComparing(PlayerStatusDTO::getUsername))
                .collect(Collectors.toList());
    }
}
