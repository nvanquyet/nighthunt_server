package com.nighthunt.room.service;

import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.room.entity.Room;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RoomCleanupService - Automatically cleanup empty rooms
 * 
 * Logic:
 * - Task 1 (5 phút): Cleanup các phòng WAITING trống và đã tồn tại > 5 phút
 * - Task 2 (30 phút): Cleanup các phòng WAITING trống và đã tồn tại > 30 phút (backup check)
 * 
 * Lý do có thời gian chờ:
 * - Tránh cleanup quá sớm khi player vừa rời phòng nhưng có thể reconnect
 * - Cho phép owner có thời gian quay lại phòng sau khi disconnect
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoomCleanupService {
    private final RoomRepository roomRepository;
    private final RoomPlayerRepository roomPlayerRepository;

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
}

