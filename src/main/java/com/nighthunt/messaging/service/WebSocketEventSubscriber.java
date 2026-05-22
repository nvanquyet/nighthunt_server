package com.nighthunt.messaging.service;

import com.nighthunt.friend.repository.FriendRepository;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.messaging.adapter.RedisMessageBroker;
import com.nighthunt.messaging.constants.MessageTopics;
import com.nighthunt.messaging.dto.Message;
import com.nighthunt.party.repository.PartyMemberRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * WebSocket Event Subscriber Service
 * 
 * Subscribes to Redis Pub/Sub topics and forwards events to WebSocket clients.
 * This is the bridge between Redis message broker and WebSocket connections.
 * 
 * Architecture:
 * Services (Friend/Party/Auth) 
 *   → MessageBrokerService.publish() 
 *   → Redis Pub/Sub 
 *   → WebSocketEventSubscriber (THIS CLASS)
 *   → ConnectionManager.sendToUser() 
 *   → WebSocket → Unity Client
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketEventSubscriber {

    private final RedisMessageBroker messageBroker;
    private final ConnectionManager connectionManager;
    private final FriendRepository friendRepository;
    private final PartyMemberRepository partyMemberRepository;

    /**
     * Initialize subscriptions to all friend and party topics.
     * Called automatically after bean construction.
     */
    @PostConstruct
    public void initializeSubscriptions() {
        log.info("Initializing WebSocket event subscriptions...");
        
        // Subscribe to friend system events
        subscribeFriendEvents();
        
        // Subscribe to party system events
        subscribePartyEvents();
        
        log.info("WebSocket event subscriptions initialized successfully");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FRIEND SYSTEM EVENT SUBSCRIPTIONS
    // ══════════════════════════════════════════════════════════════════════════

    private void subscribeFriendEvents() {
        // Friend status changed (online/offline/in-game)
        messageBroker.subscribe(MessageTopics.FRIEND_STATUS_CHANGED, this::handleFriendStatusChanged);
        
        // Friend request received
        messageBroker.subscribe(MessageTopics.FRIEND_REQUEST_RECEIVED, this::handleFriendRequestReceived);
        
        // Friend request accepted
        messageBroker.subscribe(MessageTopics.FRIEND_REQUEST_ACCEPTED, this::handleFriendRequestAccepted);
        
        // Friend request declined
        messageBroker.subscribe(MessageTopics.FRIEND_REQUEST_DECLINED, this::handleFriendRequestDeclined);
        
        // Friend request cancelled (requester withdrew before addressee responded)
        messageBroker.subscribe(MessageTopics.FRIEND_REQUEST_CANCELLED, this::handleFriendRequestCancelled);
        
        // Friend removed (already sent to both users in service, but we can log)
        messageBroker.subscribe(MessageTopics.FRIEND_REMOVED, this::handleFriendRemoved);
        
        // Friend blocked
        messageBroker.subscribe(MessageTopics.FRIEND_BLOCKED, this::handleFriendBlocked);
        
        log.info("Subscribed to friend system events");
    }

    /**
     * Handle friend status changed event.
     * Broadcast to all friends when a user's online status changes.
     */
    private void handleFriendStatusChanged(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long userId = getLong(payload, "userId");
            String oldStatus = getString(payload, "oldStatus");
            String newStatus = getString(payload, "newStatus");
            Long currentPartyId = getLong(payload, "currentPartyId");
            Long currentRoomId = getLong(payload, "currentRoomId");
            
            // Get all friends of the user
            List<Long> friendIds = friendRepository.findFriendIdsByUserId(userId);
            
            // Send event to each friend
            Map<String, Object> eventData = Map.of(
                "userId", userId,
                "oldStatus", oldStatus != null ? oldStatus : "",
                "newStatus", newStatus != null ? newStatus : "",
                "currentPartyId", currentPartyId != null ? currentPartyId : 0,
                "currentRoomId", currentRoomId != null ? currentRoomId : 0
            );
            
            for (Long friendId : friendIds) {
                connectionManager.sendToUser(friendId, "friend_status_changed", eventData);
            }
            
            log.debug("Broadcasted friend status change for user {} to {} friends", userId, friendIds.size());
        } catch (Exception e) {
            log.error("Error handling friend status changed event: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle friend request received event.
     * Send notification to the addressee (person receiving the request).
     */
    private void handleFriendRequestReceived(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long addresseeUserId = getLong(payload, "addresseeUserId");
            Long requesterUserId = getLong(payload, "requesterUserId");
            String requesterUsername = getString(payload, "requesterUsername");
            Long requestId = getLong(payload, "requestId");
            
            Map<String, Object> eventData = Map.of(
                "addresseeUserId", addresseeUserId,
                "requesterUserId", requesterUserId,
                "requesterUsername", requesterUsername != null ? requesterUsername : "",
                "requestId", requestId
            );
            
            connectionManager.sendToUser(addresseeUserId, "friend_request_received", eventData);
            
            log.debug("Sent friend request notification to user {} from user {}", 
                addresseeUserId, requesterUserId);
        } catch (Exception e) {
            log.error("Error handling friend request received event: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle friend request accepted event.
     * Send notification to the requester (person who sent the request).
     */
    private void handleFriendRequestAccepted(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long requesterUserId = getLong(payload, "requesterUserId");
            Long addresseeUserId = getLong(payload, "addresseeUserId");
            String addresseeUsername = getString(payload, "addresseeUsername");
            
            Map<String, Object> eventData = Map.of(
                "requesterUserId", requesterUserId,
                "addresseeUserId", addresseeUserId,
                "addresseeUsername", addresseeUsername != null ? addresseeUsername : ""
            );
            
            connectionManager.sendToUser(requesterUserId, "friend_request_accepted", eventData);
            
            log.debug("Sent friend request accepted notification to user {} (accepted by {})", 
                requesterUserId, addresseeUserId);
        } catch (Exception e) {
            log.error("Error handling friend request accepted event: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle friend request declined event.
     * Send notification to the requester.
     */
    private void handleFriendRequestDeclined(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long requesterUserId = getLong(payload, "requesterUserId");
            Long addresseeUserId = getLong(payload, "addresseeUserId");
            
            Map<String, Object> eventData = Map.of(
                "requesterUserId", requesterUserId,
                "addresseeUserId", addresseeUserId
            );
            
            connectionManager.sendToUser(requesterUserId, "friend_request_declined", eventData);
            
            log.debug("Sent friend request declined notification to user {}", requesterUserId);
        } catch (Exception e) {
            log.error("Error handling friend request declined event: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle friend request cancelled event.
     * Notify the addressee so their incoming-requests list removes the item in real time.
     */
    private void handleFriendRequestCancelled(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long requesterUserId = getLong(payload, "requesterUserId");
            Long addresseeUserId = getLong(payload, "addresseeUserId");

            Map<String, Object> eventData = Map.of(
                "requesterUserId", requesterUserId,
                "addresseeUserId", addresseeUserId
            );

            connectionManager.sendToUser(addresseeUserId, "friend_request_cancelled", eventData);

            log.debug("Sent friend request cancelled notification to user {} (cancelled by {})",
                addresseeUserId, requesterUserId);
        } catch (Exception e) {
            log.error("Error handling friend request cancelled event: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle friend removed event.
     * Note: Service already sends to both users, this is just for logging.
     */
    private void handleFriendRemoved(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long userId = getLong(payload, "userId");
            Long friendUserId = getLong(payload, "friendUserId");
            
            Map<String, Object> eventData = Map.of(
                "userId", userId,
                "friendUserId", friendUserId
            );
            
            connectionManager.sendToUser(userId, "friend_removed", eventData);
            
            log.debug("Sent friend removed notification to user {}", userId);
        } catch (Exception e) {
            log.error("Error handling friend removed event: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle friend blocked event.
     * Send notification to the blocked user (if they're friends).
     */
    private void handleFriendBlocked(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long blockerUserId = getLong(payload, "blockerUserId");
            Long blockedUserId = getLong(payload, "blockedUserId");
            
            Map<String, Object> eventData = Map.of(
                "blockerUserId", blockerUserId,
                "blockedUserId", blockedUserId
            );
            
            connectionManager.sendToUser(blockedUserId, "friend_blocked", eventData);
            
            log.debug("Sent friend blocked notification to user {}", blockedUserId);
        } catch (Exception e) {
            log.error("Error handling friend blocked event: {}", e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PARTY SYSTEM EVENT SUBSCRIPTIONS
    // ══════════════════════════════════════════════════════════════════════════

    private void subscribePartyEvents() {
        // Party created
        messageBroker.subscribe(MessageTopics.PARTY_CREATED, this::handlePartyCreated);
        
        // Party invitation received
        messageBroker.subscribe(MessageTopics.PARTY_INVITATION_RECEIVED, this::handlePartyInvitationReceived);
        
        // Party invitation declined (invitee declined — notify inviter)
        messageBroker.subscribe(MessageTopics.PARTY_INVITATION_DECLINED, this::handlePartyInvitationDeclined);

        // Party invitation cancelled (inviter withdrew — notify invitee)
        messageBroker.subscribe(MessageTopics.PARTY_INVITATION_CANCELLED, this::handlePartyInvitationCancelled);

        // Party invitation expired (nobody responded in time — notify both)
        messageBroker.subscribe(MessageTopics.PARTY_INVITATION_EXPIRED, this::handlePartyInvitationExpired);
        
        // Party member joined
        messageBroker.subscribe(MessageTopics.PARTY_MEMBER_JOINED, this::handlePartyMemberJoined);
        
        // Party member left
        messageBroker.subscribe(MessageTopics.PARTY_MEMBER_LEFT, this::handlePartyMemberLeft);
        
        // Party member kicked
        messageBroker.subscribe(MessageTopics.PARTY_MEMBER_KICKED, this::handlePartyMemberKicked);
        
        // Party disbanded
        messageBroker.subscribe(MessageTopics.PARTY_DISBANDED, this::handlePartyDisbanded);
        
        // Party host changed
        messageBroker.subscribe(MessageTopics.PARTY_HOST_CHANGED, this::handlePartyHostChanged);

        // Party status changed (IDLE → QUEUING → IN_MATCH etc.)
        messageBroker.subscribe(MessageTopics.PARTY_STATUS_CHANGED, this::handlePartyStatusChanged);

        // NOTE: PARTY_INVITATION_EXPIRED is already subscribed above — do NOT add it again here.

        log.info("Subscribed to party system events");
    }

    /**
     * Handle party created event.
     * Send notification to the host.
     */
    private void handlePartyCreated(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long partyId = getLong(payload, "partyId");
            Long hostUserId = getLong(payload, "hostUserId");
            String hostUsername = getString(payload, "hostUsername");
            
            Map<String, Object> eventData = Map.of(
                "partyId", partyId,
                "hostUserId", hostUserId,
                "hostUsername", hostUsername != null ? hostUsername : ""
            );
            
            connectionManager.sendToUser(hostUserId, "party_created", eventData);
            
            log.debug("Sent party created notification to host {}", hostUserId);
        } catch (Exception e) {
            log.error("Error handling party created event: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle party invitation received event.
     * Send notification to the invitee.
     */
    private void handlePartyInvitationReceived(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long partyId = getLong(payload, "partyId");
            Long inviteeUserId = getLong(payload, "inviteeUserId");
            Long inviterUserId = getLong(payload, "inviterUserId");
            String inviterUsername = getString(payload, "inviterUsername");
            Long invitationId = getLong(payload, "invitationId");
            
            Map<String, Object> eventData = Map.of(
                "partyId", partyId,
                "inviteeUserId", inviteeUserId,
                "inviterUserId", inviterUserId,
                "inviterUsername", inviterUsername != null ? inviterUsername : "",
                "invitationId", invitationId
            );
            
            connectionManager.sendToUser(inviteeUserId, "party_invitation_received", eventData);
            
            log.debug("Sent party invitation notification to user {} from user {}", 
                inviteeUserId, inviterUserId);
        } catch (Exception e) {
            log.error("Error handling party invitation received event: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle party member joined event.
     * Broadcast to all party members.
     */
    private void handlePartyMemberJoined(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long partyId = getLong(payload, "partyId");
            Long userId = getLong(payload, "userId");
            String username = getString(payload, "username");
            Integer joinOrder = getInteger(payload, "joinOrder");
            
            // Get all party members
            List<Long> memberIds = partyMemberRepository.findUserIdsByPartyId(partyId);
            
            Map<String, Object> eventData = Map.of(
                "partyId", partyId,
                "userId", userId,
                "username", username != null ? username : "",
                "joinOrder", joinOrder != null ? joinOrder : 0
            );
            
            // Broadcast to all party members
            for (Long memberId : memberIds) {
                connectionManager.sendToUser(memberId, "party_member_joined", eventData);
            }
            
            log.debug("Broadcasted party member joined for user {} to {} members", 
                userId, memberIds.size());
        } catch (Exception e) {
            log.error("Error handling party member joined event: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle party member left event.
     * Broadcast to all remaining party members.
     */
    private void handlePartyMemberLeft(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long partyId = getLong(payload, "partyId");
            Long userId = getLong(payload, "userId");
            
            // Get all remaining party members (excluding the one who left)
            List<Long> memberIds = partyMemberRepository.findUserIdsByPartyId(partyId);
            
            Map<String, Object> eventData = Map.of(
                "partyId", partyId,
                "userId", userId
            );
            
            // Broadcast to all remaining members
            for (Long memberId : memberIds) {
                connectionManager.sendToUser(memberId, "party_member_left", eventData);
            }
            
            // Also send to the user who left
            connectionManager.sendToUser(userId, "party_member_left", eventData);
            
            log.debug("Broadcasted party member left for user {} to {} members", 
                userId, memberIds.size());
        } catch (Exception e) {
            log.error("Error handling party member left event: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle party member kicked event.
     * Broadcast to all party members and the kicked user.
     */
    private void handlePartyMemberKicked(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long partyId = getLong(payload, "partyId");
            Long kickedUserId = getLong(payload, "kickedUserId");
            Long kickerUserId = getLong(payload, "kickerUserId");
            
            // Get all remaining party members
            List<Long> memberIds = partyMemberRepository.findUserIdsByPartyId(partyId);
            
            Map<String, Object> eventData = Map.of(
                "partyId", partyId,
                "kickedUserId", kickedUserId,
                "kickerUserId", kickerUserId
            );
            
            // Broadcast to all party members
            for (Long memberId : memberIds) {
                connectionManager.sendToUser(memberId, "party_member_kicked", eventData);
            }
            
            // Send to kicked user
            connectionManager.sendToUser(kickedUserId, "party_member_kicked", eventData);
            
            log.debug("Broadcasted party member kicked for user {} to {} members", 
                kickedUserId, memberIds.size());
        } catch (Exception e) {
            log.error("Error handling party member kicked event: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle party disbanded event.
     * Broadcast to ALL members whose IDs were captured BEFORE the delete.
     */
    private void handlePartyDisbanded(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long partyId = getLong(payload, "partyId");
            Long hostUserId = getLong(payload, "hostUserId");

            // memberIds are embedded in the event payload (captured before DB delete)
            @SuppressWarnings("unchecked")
            List<?> rawIds = payload.get("memberIds") instanceof List
                    ? (List<?>) payload.get("memberIds") : List.of();
            
            Map<String, Object> eventData = Map.of(
                "partyId", partyId,
                "hostUserId", hostUserId
            );

            // Notify every member; fallback to host-only if list is empty
            if (rawIds.isEmpty()) {
                connectionManager.sendToUser(hostUserId, "party_disbanded", eventData);
            } else {
                for (Object rawId : rawIds) {
                    Long memberId = rawId instanceof Number ? ((Number) rawId).longValue() : getLong(Map.of("v", rawId), "v");
                    connectionManager.sendToUser(memberId, "party_disbanded", eventData);
                }
            }

            log.debug("Broadcasted party disbanded for party {} to {} member(s)", partyId, rawIds.size());
        } catch (Exception e) {
            log.error("Error handling party disbanded event: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle party host changed event.
     * Broadcast to all party members.
     */
    private void handlePartyHostChanged(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long partyId = getLong(payload, "partyId");
            Long oldHostUserId = getLong(payload, "oldHostUserId");
            Long newHostUserId = getLong(payload, "newHostUserId");
            
            // Get all party members
            List<Long> memberIds = partyMemberRepository.findUserIdsByPartyId(partyId);
            
            Map<String, Object> eventData = Map.of(
                "partyId", partyId,
                "oldHostUserId", oldHostUserId,
                "newHostUserId", newHostUserId
            );
            
            // Broadcast to all party members
            for (Long memberId : memberIds) {
                connectionManager.sendToUser(memberId, "party_host_changed", eventData);
            }
            
            log.debug("Broadcasted party host changed for party {} to {} members", 
                partyId, memberIds.size());
        } catch (Exception e) {
            log.error("Error handling party host changed event: {}", e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Handle party status changed event (IDLE → QUEUING → IN_MATCH etc.).
     * Broadcast to all current party members.
     */
    private void handlePartyStatusChanged(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long partyId = getLong(payload, "partyId");
            String oldStatus = getString(payload, "oldStatus");
            String newStatus = getString(payload, "newStatus");

            List<Long> memberIds = partyMemberRepository.findUserIdsByPartyId(partyId);

            Map<String, Object> eventData = Map.of(
                "partyId", partyId,
                "oldStatus", oldStatus,
                "newStatus", newStatus
            );

            for (Long memberId : memberIds) {
                connectionManager.sendToUser(memberId, "party_status_changed", eventData);
            }

            log.debug("Broadcasted party_status_changed ({} → {}) for party {} to {} members",
                    oldStatus, newStatus, partyId, memberIds.size());
        } catch (Exception e) {
            log.error("Error handling party status changed event: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle party invitation declined event.
     * Notify the INVITER so they can remove the pending-invite spinner on that friend's row.
     */
    private void handlePartyInvitationDeclined(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long partyId      = getLong(payload, "partyId");
            Long inviterUserId = getLong(payload, "inviterUserId");
            Long inviteeUserId = getLong(payload, "inviteeUserId");
            Long invitationId  = getLong(payload, "invitationId");

            Map<String, Object> eventData = Map.of(
                "partyId",       partyId,
                "inviterUserId", inviterUserId,
                "inviteeUserId", inviteeUserId,
                "invitationId",  invitationId
            );

            connectionManager.sendToUser(inviterUserId, "party_invitation_declined", eventData);

            log.debug("Sent party_invitation_declined to inviter {} (declined by {})",
                inviterUserId, inviteeUserId);
        } catch (Exception e) {
            log.error("Error handling party invitation declined event: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle party invitation cancelled event.
     * Notify the INVITEE so their countdown popup is dismissed immediately.
     */
    private void handlePartyInvitationCancelled(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long partyId       = getLong(payload, "partyId");
            Long inviterUserId  = getLong(payload, "inviterUserId");
            Long inviteeUserId  = getLong(payload, "inviteeUserId");
            Long invitationId   = getLong(payload, "invitationId");

            Map<String, Object> eventData = Map.of(
                "partyId",       partyId,
                "inviterUserId", inviterUserId,
                "inviteeUserId", inviteeUserId,
                "invitationId",  invitationId
            );

            connectionManager.sendToUser(inviteeUserId, "party_invitation_cancelled", eventData);

            log.debug("Sent party_invitation_cancelled to invitee {} (cancelled by {})",
                inviteeUserId, inviterUserId);
        } catch (Exception e) {
            log.error("Error handling party invitation cancelled event: {}", e.getMessage(), e);
        }
    }

    /**
     * Handle party invitation expired event.
     * Notify the invitee that their invitation is no longer valid, AND notify the
     * inviter so their pending-invite spinner is cleared.
     */
    private void handlePartyInvitationExpired(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long partyId       = getLong(payload, "partyId");
            Long inviterUserId = getLong(payload, "inviterUserId");
            Long inviteeUserId = getLong(payload, "inviteeUserId");
            Long invitationId  = getLong(payload, "invitationId");

            Map<String, Object> eventData = Map.of(
                "partyId",       partyId,
                "inviterUserId", inviterUserId,
                "inviteeUserId", inviteeUserId,
                "invitationId",  invitationId
            );

            // Notify invitee — dismiss any still-open countdown popup.
            connectionManager.sendToUser(inviteeUserId, "party_invitation_expired", eventData);
            // Notify inviter — clear the pending-invite spinner on that friend's row.
            connectionManager.sendToUser(inviterUserId, "party_invitation_expired", eventData);

            log.debug("Sent party_invitation_expired to invitee {} and inviter {} for party {}",
                inviteeUserId, inviterUserId, partyId);
        } catch (Exception e) {
            log.error("Error handling party invitation expired event: {}", e.getMessage(), e);
        }
    }

    private Long getLong(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return 0L;
        if (value instanceof Long) return (Long) value;
        if (value instanceof Integer) return ((Integer) value).longValue();
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : "";
    }

    private Integer getInteger(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return 0;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Long) return ((Long) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
