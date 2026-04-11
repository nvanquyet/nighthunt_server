package com.nighthunt.analytics.service;

import com.nighthunt.analytics.entity.ServerMetricsSnapshot;
import com.nighthunt.analytics.repository.ServerMetricsSnapshotRepository;
import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.dedicatedserver.repository.DedicatedServerRepository;
import com.nighthunt.match.repository.MatchRepository;
import com.nighthunt.matchmaking.repository.MatchmakingEntryRepository;
import com.nighthunt.room.repository.RoomRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Writes one {@link ServerMetricsSnapshot} row per minute and prunes rows
 * older than 7 days to keep the table lean.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsCollectorService {

    private final ServerMetricsSnapshotRepository snapshotRepo;
    private final RedisTemplate<String, Object> redisTemplate;
    private final MatchmakingEntryRepository matchmakingEntryRepository;
    private final RoomRepository roomRepository;
    private final DedicatedServerRepository dsRepository;
    private final MatchRepository matchRepository;

    /** Write a new snapshot every 60 seconds. */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void collect() {
        try {
            LocalDateTime now = LocalDateTime.now();

            int onlineUsers = countOnlineUsers();
            int queueDepth  = (int) matchmakingEntryRepository.countSearching();

            long waitingRooms = roomRepository.findByStatus(GameConstants.ROOM_STATUS_WAITING).size();
            long inGameRooms  = roomRepository.findByStatus(GameConstants.ROOM_STATUS_IN_GAME).size();
            long activeRooms  = waitingRooms + inGameRooms;

            var allDs    = dsRepository.findAll();
            var activeDs = allDs.stream().filter(s -> !"stopped".equals(s.getStatus())).toList();
            int inGameDs = (int) activeDs.stream().filter(s -> "in_game".equals(s.getStatus())).count();
            int playersInDs = activeDs.stream()
                    .mapToInt(s -> s.getCurrentPlayers() != null ? s.getCurrentPlayers() : 0).sum();

            int matchesLastHour = (int) matchRepository.countByFinishedAtAfter(now.minusHours(1));

            snapshotRepo.save(ServerMetricsSnapshot.builder()
                    .snapshotAt(now)
                    .onlineUsers(onlineUsers)
                    .queueDepth(queueDepth)
                    .activeRooms((int) activeRooms)
                    .inGameRooms((int) inGameRooms)
                    .waitingRooms((int) waitingRooms)
                    .activeDs(activeDs.size())
                    .inGameDs(inGameDs)
                    .playersInDs(playersInDs)
                    .matchesLastHour(matchesLastHour)
                    .build());

        } catch (Exception e) {
            log.error("MetricsCollector error: {}", e.getMessage(), e);
        }
    }

    /** Prune snapshots older than 7 days at midnight each day. */
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void prune() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        snapshotRepo.deleteBySnapshotAtBefore(cutoff);
        log.info("Pruned metrics snapshots older than {}", cutoff);
    }

    private int countOnlineUsers() {
        try {
            Set<String> keys = redisTemplate.keys(GameConstants.REDIS_KEY_SESSION_PREFIX + "*");
            return keys != null ? keys.size() : 0;
        } catch (Exception e) {
            log.warn("Redis unavailable for online count in collector: {}", e.getMessage());
            return 0;
        }
    }
}
