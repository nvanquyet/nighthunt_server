package com.nighthunt.room.service;

import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.room.entity.Room;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Service to automatically transfer room ownership when owner disconnects
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoomOwnerTransferService {
    private final RoomRepository roomRepository;
    private final RoomPlayerRepository roomPlayerRepository;
    private final ConnectionManager connectionManager;
    private final RoomResponseAssembler roomResponseAssembler;

    // Timeout: 30 seconds - if owner hasn't been seen for 30s, consider disconnected
    private static final int OWNER_DISCONNECT_TIMEOUT_SECONDS = 30;

    /**
     * Check for disconnected owners and transfer ownership to next available player
     * Runs every 30 seconds (lighter load at scale)
     */
    @Scheduled(fixedRate = 30000) // 30 seconds
    @Transactional
    public void checkAndTransferOwnership() {
        log.trace("Starting owner transfer check...");
        
        LocalDateTime timeoutThreshold = LocalDateTime.now().minusSeconds(OWNER_DISCONNECT_TIMEOUT_SECONDS);
        
        // Find all WAITING rooms
        List<Room> waitingRooms = roomRepository.findByStatus(GameConstants.ROOM_STATUS_WAITING);
        
        int transferredCount = 0;
        for (Room room : waitingRooms) {
            // Skip ranked rooms that have a matchId set — they are managed by the matchmaking
            // pipeline and should NOT be disbanded here even if owner's lastSeenAt is stale.
            // (Ranked rooms are now set to IN_GAME before match_ready is sent, so this is a
            //  defence-in-depth guard for any race condition where status update is delayed.)
            if (room.getMatchId() != null && room.getIsLocked() != null && room.getIsLocked()) {
                log.trace("Room {} has matchId={} and is locked — skipping (ranked match)", room.getId(), room.getMatchId());
                continue;
            }

            if (connectionManager.isUserConnected(room.getOwnerId())) {
                log.trace("Room {} owner {} still has an active WebSocket. Skipping ownership transfer.",
                        room.getId(), room.getOwnerId());
                continue;
            }

            // Get owner player
            RoomPlayer ownerPlayer = roomPlayerRepository.findByRoomIdAndUserId(room.getId(), room.getOwnerId())
                    .orElse(null);
            
            // If owner not found in room (shouldn't happen, but handle it)
            if (ownerPlayer == null) {
                log.warn("Room {} owner {} not found in room players. Transferring ownership.", 
                        room.getId(), room.getOwnerId());
                transferOwnershipToNextPlayer(room);
                transferredCount++;
                continue;
            }
            
            // Check if owner's lastSeenAt is older than timeout threshold
            if (ownerPlayer.getLastSeenAt() == null || 
                ownerPlayer.getLastSeenAt().isBefore(timeoutThreshold)) {
                log.info("Room {} owner {} disconnected (lastSeen: {}). Transferring ownership.", 
                        room.getId(), room.getOwnerId(), ownerPlayer.getLastSeenAt());
                transferOwnershipToNextPlayer(room);
                transferredCount++;
            }
        }
        
        if (transferredCount > 0) {
            log.info("Owner transfer check completed. Transferred {} rooms", transferredCount);
        } else {
            log.trace("Owner transfer check completed. No transfers needed");
        }
    }
    
    /**
     * Transfer ownership to the next available player (first player in room, excluding current owner)
     */
    private void transferOwnershipToNextPlayer(Room room) {
        // Get all players in room, ordered by joinedAt (first joined = first in list)
        List<RoomPlayer> players = roomPlayerRepository.findByRoomId(room.getId());
        
        // Find first player that is not the current owner
        RoomPlayer newOwner = players.stream()
                .filter(p -> !p.getUserId().equals(room.getOwnerId()))
                .findFirst()
                .orElse(null);
        
        if (newOwner == null) {
            // No other players in room — broadcast room_disbanded and mark CLOSED
            log.warn("Room {} has no other players. Ownership cannot be transferred. Room will be cleaned up.",
                    room.getId());
            connectionManager.broadcastToRoom(room.getId(), "room_disbanded",
                    Map.of("roomId", room.getId(), "reason", "owner_disconnected"));
            room.setStatus(GameConstants.ROOM_STATUS_CLOSED);
            roomRepository.save(room);
            return;
        }
        
        // Transfer ownership
        Long oldOwnerId = room.getOwnerId();
        room.setOwnerId(newOwner.getUserId());
        roomRepository.save(room);

        // Auto-ready new owner (host)
        if (!Boolean.TRUE.equals(newOwner.getIsReady())) {
            newOwner.setIsReady(true);
            roomPlayerRepository.save(newOwner);
        }
        
        log.info("Room {} ownership transferred from user {} to user {}", 
                room.getId(), oldOwnerId, newOwner.getUserId());

        // Broadcast updated room state to all players so client sees the new owner
        connectionManager.broadcastToRoom(room.getId(), "room_updated",
                roomResponseAssembler.toResponseById(room.getId()));
    }
}

