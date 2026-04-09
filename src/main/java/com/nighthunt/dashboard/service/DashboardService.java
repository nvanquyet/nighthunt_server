package com.nighthunt.dashboard.service;

import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.dashboard.dto.DashboardStatsDTO;
import com.nighthunt.dashboard.dto.PlayerDetailDTO;
import com.nighthunt.dashboard.dto.RoomDetailDTO;
import com.nighthunt.gamemode.service.GameModeService;
import com.nighthunt.room.entity.Room;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.session.port.SessionStore;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dashboard Service - Provides statistics and monitoring data
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {
    
    private final RoomRepository roomRepository;
    private final RoomPlayerRepository roomPlayerRepository;
    private final UserRepository userRepository;
    private final SessionStore sessionStore;
    private final ConnectionManager connectionManager;
    private final GameModeService gameModeService;
    
    /**
     * Get comprehensive dashboard statistics
     */
    @Transactional(readOnly = true)
    public DashboardStatsDTO getDashboardStats() {
        log.debug("Generating dashboard statistics...");
        
        // Get all active rooms (WAITING and IN_GAME)
        List<Room> waitingRooms = roomRepository.findByStatus(GameConstants.ROOM_STATUS_WAITING);
        List<Room> inGameRooms = roomRepository.findByStatus(GameConstants.ROOM_STATUS_IN_GAME);
        
        // Combine active rooms
        List<Room> allActiveRooms = waitingRooms.stream()
                .collect(Collectors.toList());
        allActiveRooms.addAll(inGameRooms);
        
        // Count statistics
        long totalActiveRooms = allActiveRooms.size();
        long totalWaitingRooms = waitingRooms.size();
        long totalInGameRooms = inGameRooms.size();
        
        // Count users in rooms
        long totalUsersInRooms = roomPlayerRepository.findAll().stream()
                .mapToLong(rp -> 1L)
                .sum();
        
        // Count online users (users with active sessions)
        long totalOnlineUsers = countOnlineUsers();
        
        // Get room details
        List<RoomDetailDTO> roomDetails = allActiveRooms.stream()
                .map(this::buildRoomDetail)
                .collect(Collectors.toList());
        
        return DashboardStatsDTO.builder()
                .totalActiveRooms(totalActiveRooms)
                .totalOnlineUsers(totalOnlineUsers)
                .totalUsersInRooms(totalUsersInRooms)
                .totalWaitingRooms(totalWaitingRooms)
                .totalInGameRooms(totalInGameRooms)
                .activeRooms(roomDetails)
                .build();
    }
    
    /**
     * Count online users (users with active sessions in Redis)
     * Note: This counts users in rooms as online, which is a reasonable estimate
     * For more accurate count, you would need to track all active sessions in Redis
     */
    private long countOnlineUsers() {
        // Count unique users in active rooms
        // This gives a good estimate of online users
        Set<Long> usersInRooms = roomPlayerRepository.findAll().stream()
                .map(RoomPlayer::getUserId)
                .collect(Collectors.toSet());
        
        // You could also check Redis for active sessions, but that requires
        // iterating through all possible user IDs or maintaining a separate set
        // For now, users in rooms are considered online
        return usersInRooms.size();
    }
    
    /**
     * Build room detail DTO from Room entity
     */
    private RoomDetailDTO buildRoomDetail(Room room) {
        // Get players in room
        List<RoomPlayer> players = roomPlayerRepository.findByRoomId(room.getId());
        
        // Get player details
        List<PlayerDetailDTO> playerDetails = players.stream()
                .map(rp -> {
                    User user = userRepository.findById(rp.getUserId()).orElse(null);
                    return PlayerDetailDTO.builder()
                            .userId(rp.getUserId())
                            .username(user != null ? user.getUsername() : "Unknown")
                            .team(rp.getTeam())
                            .slot(rp.getSlot())
                            .isReady(rp.getIsReady())
                            .isOwner(room.getOwnerId().equals(rp.getUserId()))
                            .build();
                })
                .collect(Collectors.toList());
        
        // Get owner username
        User owner = userRepository.findById(room.getOwnerId()).orElse(null);
        String ownerUsername = owner != null ? owner.getUsername() : "Unknown";
        
        // Get WebSocket connection count for this room
        int activeWebSocketConnections = getWebSocketConnectionCount(room.getId());
        
        // Calculate max players based on mode (simplified)
        int maxPlayers = calculateMaxPlayers(room.getMode());
        
        return RoomDetailDTO.builder()
                .roomId(room.getId())
                .roomCode(room.getRoomCode())
                .status(room.getStatus())
                .mode(room.getMode())
                .isPublic(room.getIsPublic())
                .isLocked(room.getIsLocked())
                .ownerId(room.getOwnerId())
                .ownerUsername(ownerUsername)
                .createdAt(room.getCreatedAt())
                .lastActivity(room.getCreatedAt()) // You might want to add lastActivity field to Room entity
                .playerCount(players.size())
                .maxPlayers(maxPlayers)
                .players(playerDetails)
                .activeWebSocketConnections(activeWebSocketConnections)
                .build();
    }
    
    /**
     * Get WebSocket connection count for a room
     */
    private int getWebSocketConnectionCount(Long roomId) {
        try {
            // Get total active connections (GameWebSocketHandler tracks all user sessions)
            // For room-specific count, we can count users in that room who have active WebSocket
            // For now, return total active connections as estimate
            return connectionManager.getActiveConnectionCount();
        } catch (Exception e) {
            log.warn("Error getting WebSocket connection count for room {}: {}", roomId, e.getMessage());
            return 0;
        }
    }
    
    /**
     * Calculate max players based on game mode
     */
    private int calculateMaxPlayers(String mode) {
        return gameModeService.getTotalPlayers(mode);
    }
}

