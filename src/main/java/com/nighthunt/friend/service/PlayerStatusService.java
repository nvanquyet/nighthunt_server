package com.nighthunt.friend.service;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.friend.repository.FriendRepository;
import com.nighthunt.messaging.service.MessageBrokerService;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Player Status Service - Tracks and broadcasts online status changes.
 * 
 * Status types:
 * - ONLINE: User is logged in and active
 * - OFFLINE: User is logged out
 * - AWAY: User is idle (future feature)
 * - IN_GAME: User is playing a match
 * 
 * When status changes, all friends are notified via WebSocket.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlayerStatusService {

    private final UserRepository userRepository;
    private final FriendRepository friendRepository;
    private final MessageBrokerService messageBrokerService;

    // ──────────────────────────────────────────────────────────────────────────
    // STATUS CHANGE METHODS
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Set user online status (called on login).
     */
    @Transactional
    public void setOnline(Long userId) {
        User user = findUser(userId);
        String oldStatus = user.getOnlineStatus();
        
        user.setOnlineStatus("ONLINE");
        user.setLastSeenAt(null); // Clear last seen when online
        userRepository.save(user);
        
        log.info("User {} status changed: {} → ONLINE", userId, oldStatus);
        
        // Broadcast to all friends
        broadcastStatusChange(userId, oldStatus, "ONLINE", user.getCurrentPartyId(), user.getCurrentRoomId());
    }

    /**
     * Set user offline status (called on logout).
     */
    @Transactional
    public void setOffline(Long userId) {
        User user = findUser(userId);
        String oldStatus = user.getOnlineStatus();
        
        user.setOnlineStatus("OFFLINE");
        user.setLastSeenAt(LocalDateTime.now());
        userRepository.save(user);
        
        log.info("User {} status changed: {} → OFFLINE", userId, oldStatus);
        
        // Broadcast to all friends
        broadcastStatusChange(userId, oldStatus, "OFFLINE", null, null);
    }

    /**
     * Set user in-game status (called when match starts).
     */
    @Transactional
    public void setInGame(Long userId) {
        User user = findUser(userId);
        String oldStatus = user.getOnlineStatus();
        
        user.setOnlineStatus("IN_GAME");
        userRepository.save(user);
        
        log.info("User {} status changed: {} → IN_GAME", userId, oldStatus);
        
        // Broadcast to all friends
        broadcastStatusChange(userId, oldStatus, "IN_GAME", user.getCurrentPartyId(), user.getCurrentRoomId());
    }

    /**
     * Set user back to online (called when match ends).
     */
    @Transactional
    public void setBackToOnline(Long userId) {
        User user = findUser(userId);
        String oldStatus = user.getOnlineStatus();
        
        user.setOnlineStatus("ONLINE");
        userRepository.save(user);
        
        log.info("User {} status changed: {} → ONLINE", userId, oldStatus);
        
        // Broadcast to all friends
        broadcastStatusChange(userId, oldStatus, "ONLINE", user.getCurrentPartyId(), user.getCurrentRoomId());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PARTY / ROOM TRACKING
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Update user's current party (called when joining/leaving party).
     */
    @Transactional
    public void updateCurrentParty(Long userId, Long partyId) {
        User user = findUser(userId);
        user.setCurrentPartyId(partyId);
        userRepository.save(user);
        
        log.info("User {} current party updated: {}", userId, partyId);
        
        // Broadcast status change to friends (party info updated)
        broadcastStatusChange(userId, user.getOnlineStatus(), user.getOnlineStatus(), partyId, user.getCurrentRoomId());
    }

    /**
     * Update user's current room (called when joining/leaving custom lobby).
     */
    @Transactional
    public void updateCurrentRoom(Long userId, Long roomId) {
        User user = findUser(userId);
        user.setCurrentRoomId(roomId);
        userRepository.save(user);
        
        log.info("User {} current room updated: {}", userId, roomId);
        
        // Broadcast status change to friends (room info updated)
        broadcastStatusChange(userId, user.getOnlineStatus(), user.getOnlineStatus(), user.getCurrentPartyId(), roomId);
    }

    /**
     * Clear user's current party and room (called when match starts or party disbands).
     */
    @Transactional
    public void clearCurrentActivity(Long userId) {
        User user = findUser(userId);
        user.setCurrentPartyId(null);
        user.setCurrentRoomId(null);
        userRepository.save(user);
        
        log.info("User {} current activity cleared", userId);
        
        // Broadcast status change to friends
        broadcastStatusChange(userId, user.getOnlineStatus(), user.getOnlineStatus(), null, null);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // STATUS QUERY METHODS
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Get current online status of a user.
     */
    @Transactional(readOnly = true)
    public String getOnlineStatus(Long userId) {
        User user = findUser(userId);
        return user.getOnlineStatus();
    }

    /**
     * Check if user is online.
     */
    @Transactional(readOnly = true)
    public boolean isOnline(Long userId) {
        User user = findUser(userId);
        return "ONLINE".equals(user.getOnlineStatus()) || "IN_GAME".equals(user.getOnlineStatus());
    }

    /**
     * Get user's current party ID (null if not in party).
     */
    @Transactional(readOnly = true)
    public Long getCurrentPartyId(Long userId) {
        User user = findUser(userId);
        return user.getCurrentPartyId();
    }

    /**
     * Get user's current room ID (null if not in room).
     */
    @Transactional(readOnly = true)
    public Long getCurrentRoomId(Long userId) {
        User user = findUser(userId);
        return user.getCurrentRoomId();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // HELPER METHODS
    // ──────────────────────────────────────────────────────────────────────────

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.USER_NOT_FOUND, "User not found: " + userId));
    }

    /**
     * Broadcast status change to all friends of the user.
     */
    private void broadcastStatusChange(Long userId, String oldStatus, String newStatus, Long currentPartyId, Long currentRoomId) {
        // Get all friend IDs
        List<Long> friendIds = friendRepository.findFriendIdsByUserId(userId);
        
        // Broadcast to each friend (message broker will handle routing)
        if (!friendIds.isEmpty()) {
            messageBrokerService.publishFriendStatusChanged(
                userId, 
                oldStatus, 
                newStatus, 
                currentPartyId, 
                currentRoomId
            );
            
            log.debug("Broadcasted status change for user {} to {} friends", userId, friendIds.size());
        }
    }
}
