package com.nighthunt.room.service;

import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.match.entity.Match;
import com.nighthunt.match.repository.MatchRepository;
import com.nighthunt.room.entity.Room;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * RoomCleanupService - Automatically cleanup empty/stale rooms and matches.
 *
 * Logic:
 * - Task 1 (5 phút):  Cleanup WAITING rooms trống > 5 phút
 * - Task 2 (30 phút): Cleanup WAITING rooms trống > 30 phút (backup)
 * - Task 3 (15 phút): Cleanup IN_GAME/WAITING rooms mà tất cả players offline > 30 phút
 * - Task 4 (15 phút): Cleanup matches LOBBY/IN_GAME không còn room active > 60 phút
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoomCleanupService {
    private final RoomRepository roomRepository;
    private final RoomPlayerRepository roomPlayerRepository;
    private final MatchRepository matchRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Cleanup empty rooms every 5 minutes
     * Cleanup các phòng WAITING không còn player và đã tồn tại > 5 phút
     */
    @Scheduled(fixedRate = 300000) // 5 minutes in milliseconds
    @Transactional
    public void cleanupEmptyRooms() {
        log.debug("Starting room cleanup task (5 min check)...");
        
        LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);
        
        // Find all WAITING rooms
        List<Room> waitingRooms = roomRepository.findByStatus(GameConstants.ROOM_STATUS_WAITING);
        
        int cleanedCount = 0;
        for (Room room : waitingRooms) {
            // Check if room has any players
            int playerCount = roomPlayerRepository.countByRoomId(room.getId());
            
            if (playerCount == 0) {
                // Only cleanup if room has been empty for at least 5 minutes
                if (room.getCreatedAt() != null && room.getCreatedAt().isBefore(fiveMinutesAgo)) {
                    log.info("Cleaning up empty room: {} (code: {}, created: {}, empty for >5min)", 
                            room.getId(), room.getRoomCode(), room.getCreatedAt());
                    
                    // Update room status to CLOSED
                    room.setStatus(GameConstants.ROOM_STATUS_CLOSED);
                    roomRepository.save(room);
                    
                    cleanedCount++;
                } else {
                    log.debug("Room {} is empty but too new (<5min), skipping cleanup", room.getRoomCode());
                }
            }
        }
        
        if (cleanedCount > 0) {
            log.info("Room cleanup (5min) completed. Cleaned {} empty rooms", cleanedCount);
        } else {
            log.debug("Room cleanup (5min) completed. No empty rooms found");
        }
    }

    /**
     * Cleanup stale rooms every 30 minutes
     * Cleanup các phòng WAITING không còn player và đã tồn tại > 30 phút
     * Đây là backup check để đảm bảo không có phòng nào bị sót
     */
    @Scheduled(fixedRate = 1800000) // 30 minutes in milliseconds
    @Transactional
    public void cleanupStaleRooms() {
        log.debug("Starting stale room cleanup task (30 min check)...");
        
        LocalDateTime thirtyMinutesAgo = LocalDateTime.now().minusMinutes(30);
        
        // Find all WAITING rooms
        List<Room> waitingRooms = roomRepository.findByStatus(GameConstants.ROOM_STATUS_WAITING);
        
        int cleanedCount = 0;
        for (Room room : waitingRooms) {
            // Check if room has any players
            int playerCount = roomPlayerRepository.countByRoomId(room.getId());
            
            if (playerCount == 0) {
                // Only cleanup if room has been empty for at least 30 minutes
                if (room.getCreatedAt() != null && room.getCreatedAt().isBefore(thirtyMinutesAgo)) {
                    log.info("Cleaning up stale empty room: {} (code: {}, created: {}, empty for >30min)", 
                            room.getId(), room.getRoomCode(), room.getCreatedAt());
                    
                    room.setStatus(GameConstants.ROOM_STATUS_CLOSED);
                    roomRepository.save(room);
                    
                    cleanedCount++;
                }
            }
        }
        
        if (cleanedCount > 0) {
            log.info("Stale room cleanup (30min) completed. Cleaned {} stale empty rooms", cleanedCount);
        } else {
            log.debug("Stale room cleanup (30min) completed. No stale empty rooms found");
        }
    }

    /**
     * Every 15 minutes: close any WAITING/IN_GAME room where ALL players have
     * no active Redis session AND the room has been inactive for >30 minutes.
     * This handles the case where players crashed/disconnected without leaving.
     */
    @Scheduled(fixedRate = 900_000) // 15 minutes
    @Transactional
    public void cleanupOfflinePlayerRooms() {
        log.debug("Starting offline-player room cleanup...");

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);

        List<Room> activeRooms = roomRepository.findByStatus(GameConstants.ROOM_STATUS_WAITING);
        activeRooms.addAll(roomRepository.findByStatus(GameConstants.ROOM_STATUS_IN_GAME));

        int closedCount = 0;
        for (Room room : activeRooms) {
            if (room.getCreatedAt() == null || room.getCreatedAt().isAfter(cutoff)) {
                continue; // too recent, skip
            }

            List<RoomPlayer> players = roomPlayerRepository.findByRoomId(room.getId());
            if (players.isEmpty()) {
                continue; // handled by cleanupEmptyRooms / cleanupStaleRooms
            }

            boolean anyOnline = players.stream().anyMatch(rp -> isOnline(rp.getUserId()));
            if (!anyOnline) {
                log.warn("[Cleanup] Closing room {} (code={}, status={}) — all {} players offline >30min",
                        room.getId(), room.getRoomCode(), room.getStatus(), players.size());
                room.setStatus(GameConstants.ROOM_STATUS_CLOSED);
                roomRepository.save(room);
                roomPlayerRepository.deleteAll(players);
                closedCount++;
            }
        }

        if (closedCount > 0) {
            log.info("Offline-player cleanup: closed {} rooms", closedCount);
        }
    }

    /**
     * Every 15 minutes: mark any Match stuck in LOBBY or IN_GAME as FINISHED/ABANDONED
     * if its room is no longer active OR it has been running for >2 hours.
     */
    @Scheduled(fixedRate = 900_000) // 15 minutes
    @Transactional
    public void cleanupStaleMatches() {
        log.debug("Starting stale match cleanup...");

        LocalDateTime twoHoursAgo = LocalDateTime.now().minusHours(2);

        List<Match> stale = new ArrayList<>();
        stale.addAll(matchRepository.findByStatus(GameConstants.MATCH_STATUS_LOBBY));
        stale.addAll(matchRepository.findByStatus(GameConstants.MATCH_STATUS_IN_GAME));

        int fixedCount = 0;
        for (Match match : stale) {
            if (match.getCreatedAt() == null || match.getCreatedAt().isAfter(twoHoursAgo)) {
                continue;
            }

            // Check if linked room is still active
            boolean roomActive = roomRepository.findById(match.getRoomId())
                    .map(r -> GameConstants.ROOM_STATUS_WAITING.equals(r.getStatus())
                            || GameConstants.ROOM_STATUS_IN_GAME.equals(r.getStatus()))
                    .orElse(false);

            if (!roomActive) {
                log.warn("[Cleanup] Marking stale match {} as FINISHED (room inactive/missing, age >2h)",
                        match.getMatchId());
                match.setStatus(GameConstants.MATCH_STATUS_FINISHED);
                match.setEndReason("ABANDONED_CLEANUP");
                match.setFinishedAt(LocalDateTime.now());
                matchRepository.save(match);
                fixedCount++;
            }
        }

        if (fixedCount > 0) {
            log.info("Stale match cleanup: fixed {} matches", fixedCount);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isOnline(Long userId) {
        try {
            String key = GameConstants.REDIS_KEY_SESSION_PREFIX + userId;
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            return false;
        }
    }
}

