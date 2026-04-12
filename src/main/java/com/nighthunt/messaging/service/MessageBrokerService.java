package com.nighthunt.messaging.service;

import com.nighthunt.messaging.adapter.RedisMessageBroker;
import com.nighthunt.messaging.constants.MessageTopics;
import com.nighthunt.messaging.dto.Message;
import com.nighthunt.messaging.port.MessagePublisher;
import com.nighthunt.messaging.port.MessageSubscriber;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Message Broker Service
 * High-level service for publishing and subscribing to messages
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageBrokerService implements MessagePublisher {
    
    private final RedisMessageBroker messageBroker;
    
    @PostConstruct
    public void init() {
        log.info("Message Broker Service initialized");
    }
    
    @PreDestroy
    public void cleanup() {
        log.info("Message Broker Service cleanup");
    }
    
    // ==================== Publishing Methods ====================
    
    @Override
    public void publish(String topic, Message message) {
        messageBroker.publish(topic, message);
    }
    
    @Override
    public void publish(String topic, String messageType, Object payload) {
        messageBroker.publish(topic, messageType, payload);
    }
    
    @Override
    public void publish(String topic, Message message, int priority) {
        messageBroker.publish(topic, message, priority);
    }
    
    // ==================== Convenience Publishing Methods ====================
    
    /**
     * Publish user login event
     */
    public void publishUserLogin(Long userId, String username, String ipAddress) {
        Map<String, Object> payload = Map.of(
                "userId", userId,
                "username", username,
                "ipAddress", ipAddress
        );
        publish(MessageTopics.AUTH_USER_LOGIN, "user.login", payload);
    }
    
    /**
     * Publish user logout event
     */
    public void publishUserLogout(Long userId) {
        Map<String, Object> payload = Map.of("userId", userId);
        publish(MessageTopics.AUTH_USER_LOGOUT, "user.logout", payload);
    }
    
    /**
     * Publish room created event
     */
    public void publishRoomCreated(Long roomId, Long ownerId, String roomCode) {
        Map<String, Object> payload = Map.of(
                "roomId", roomId,
                "ownerId", ownerId,
                "roomCode", roomCode
        );
        publish(MessageTopics.ROOM_CREATED, "room.created", payload);
    }
    
    /**
     * Publish room updated event
     */
    public void publishRoomUpdated(Long roomId, Map<String, Object> changes) {
        Map<String, Object> payload = Map.of(
                "roomId", roomId,
                "changes", changes
        );
        publish(MessageTopics.ROOM_UPDATED, "room.updated", payload);
    }
    
    /**
     * Publish player joined event
     */
    public void publishPlayerJoined(Long roomId, Long userId, String username) {
        Map<String, Object> payload = Map.of(
                "roomId", roomId,
                "userId", userId,
                "username", username
        );
        publish(MessageTopics.ROOM_PLAYER_JOINED, "player.joined", payload);
    }
    
    /**
     * Publish player left event
     */
    public void publishPlayerLeft(Long roomId, Long userId) {
        Map<String, Object> payload = Map.of(
                "roomId", roomId,
                "userId", userId
        );
        publish(MessageTopics.ROOM_PLAYER_LEFT, "player.left", payload);
    }
    
    /**
     * Publish player ready event
     */
    public void publishPlayerReady(Long roomId, Long userId, boolean isReady) {
        Map<String, Object> payload = Map.of(
                "roomId", roomId,
                "userId", userId,
                "isReady", isReady
        );
        publish(MessageTopics.ROOM_PLAYER_READY, "player.ready", payload);
    }
    
    /**
     * Publish room status changed event
     */
    public void publishRoomStatusChanged(Long roomId, String oldStatus, String newStatus) {
        Map<String, Object> payload = Map.of(
                "roomId", roomId,
                "oldStatus", oldStatus,
                "newStatus", newStatus
        );
        publish(MessageTopics.ROOM_STATUS_CHANGED, "room.status.changed", payload);
    }
    
    // ==================== Friend System Events ====================
    
    /**
     * Publish friend request received event
     */
    public void publishFriendRequestReceived(Long addresseeUserId, Long requesterUserId, String requesterUsername, Long requestId) {
        Map<String, Object> payload = Map.of(
                "addresseeUserId", addresseeUserId,
                "requesterUserId", requesterUserId,
                "requesterUsername", requesterUsername,
                "requestId", requestId
        );
        publish(MessageTopics.FRIEND_REQUEST_RECEIVED, "friend.request.received", payload);
    }
    
    /**
     * Publish friend request accepted event
     */
    public void publishFriendRequestAccepted(Long requesterUserId, Long addresseeUserId, String addresseeUsername) {
        Map<String, Object> payload = Map.of(
                "requesterUserId", requesterUserId,
                "addresseeUserId", addresseeUserId,
                "addresseeUsername", addresseeUsername
        );
        publish(MessageTopics.FRIEND_REQUEST_ACCEPTED, "friend.request.accepted", payload);
    }
    
    /**
     * Publish friend request declined event
     */
    public void publishFriendRequestDeclined(Long requesterUserId, Long addresseeUserId) {
        Map<String, Object> payload = Map.of(
                "requesterUserId", requesterUserId,
                "addresseeUserId", addresseeUserId
        );
        publish(MessageTopics.FRIEND_REQUEST_DECLINED, "friend.request.declined", payload);
    }

    /**
     * Publish friend request cancelled event (requester withdrew the request).
     * Sent to the ADDRESSEE so their incoming-requests list refreshes in real time.
     */
    public void publishFriendRequestCancelled(Long requesterUserId, Long addresseeUserId) {
        Map<String, Object> payload = Map.of(
                "requesterUserId", requesterUserId,
                "addresseeUserId", addresseeUserId
        );
        publish(MessageTopics.FRIEND_REQUEST_CANCELLED, "friend.request.cancelled", payload);
    }
    
    /**
     * Publish friend removed event
     */
    public void publishFriendRemoved(Long userId, Long friendUserId) {
        Map<String, Object> payload = Map.of(
                "userId", userId,
                "friendUserId", friendUserId
        );
        publish(MessageTopics.FRIEND_REMOVED, "friend.removed", payload);
    }
    
    /**
     * Publish friend status changed event (online/offline/in-game)
     */
    public void publishFriendStatusChanged(Long userId, String oldStatus, String newStatus, Long currentPartyId, Long currentRoomId) {
        Map<String, Object> payload = Map.of(
                "userId", userId,
                "oldStatus", oldStatus,
                "newStatus", newStatus,
                "currentPartyId", currentPartyId != null ? currentPartyId : 0,
                "currentRoomId", currentRoomId != null ? currentRoomId : 0
        );
        publish(MessageTopics.FRIEND_STATUS_CHANGED, "friend.status.changed", payload);
    }
    
    /**
     * Publish friend blocked event
     */
    public void publishFriendBlocked(Long blockerUserId, Long blockedUserId) {
        Map<String, Object> payload = Map.of(
                "blockerUserId", blockerUserId,
                "blockedUserId", blockedUserId
        );
        publish(MessageTopics.FRIEND_BLOCKED, "friend.blocked", payload);
    }
    
    // ==================== Party System Events ====================
    
    /**
     * Publish party created event
     */
    public void publishPartyCreated(Long partyId, Long hostUserId, String hostUsername) {
        Map<String, Object> payload = Map.of(
                "partyId", partyId,
                "hostUserId", hostUserId,
                "hostUsername", hostUsername
        );
        publish(MessageTopics.PARTY_CREATED, "party.created", payload);
    }
    
    /**
     * Publish party invitation received event
     */
    public void publishPartyInvitationReceived(Long partyId, Long inviteeUserId, Long inviterUserId, String inviterUsername, Long invitationId) {
        Map<String, Object> payload = Map.of(
                "partyId", partyId,
                "inviteeUserId", inviteeUserId,
                "inviterUserId", inviterUserId,
                "inviterUsername", inviterUsername,
                "invitationId", invitationId
        );
        publish(MessageTopics.PARTY_INVITATION_RECEIVED, "party.invitation.received", payload);
    }
    
    /**
     * Publish party member joined event
     */
    public void publishPartyMemberJoined(Long partyId, Long userId, String username, int joinOrder) {
        Map<String, Object> payload = Map.of(
                "partyId", partyId,
                "userId", userId,
                "username", username,
                "joinOrder", joinOrder
        );
        publish(MessageTopics.PARTY_MEMBER_JOINED, "party.member.joined", payload);
    }
    
    /**
     * Publish party member left event
     */
    public void publishPartyMemberLeft(Long partyId, Long userId) {
        Map<String, Object> payload = Map.of(
                "partyId", partyId,
                "userId", userId
        );
        publish(MessageTopics.PARTY_MEMBER_LEFT, "party.member.left", payload);
    }
    
    /**
     * Publish party member kicked event
     */
    public void publishPartyMemberKicked(Long partyId, Long kickedUserId, Long kickerUserId) {
        Map<String, Object> payload = Map.of(
                "partyId", partyId,
                "kickedUserId", kickedUserId,
                "kickerUserId", kickerUserId
        );
        publish(MessageTopics.PARTY_MEMBER_KICKED, "party.member.kicked", payload);
    }
    
    /**
     * Publish party disbanded event.
     * memberIds must be captured BEFORE members are deleted from the DB,
     * so the subscriber can notify every member (not just the host).
     */
    public void publishPartyDisbanded(Long partyId, Long hostUserId, List<Long> memberIds) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("partyId", partyId);
        payload.put("hostUserId", hostUserId);
        payload.put("memberIds", memberIds);
        publish(MessageTopics.PARTY_DISBANDED, "party.disbanded", payload);
    }

    /** Overload kept for backward compat — no memberIds, only host notified (legacy). */
    public void publishPartyDisbanded(Long partyId, Long hostUserId) {
        publishPartyDisbanded(partyId, hostUserId, List.of(hostUserId));
    }
    
    /**
     * Publish party host changed event
     */
    public void publishPartyHostChanged(Long partyId, Long oldHostUserId, Long newHostUserId) {
        Map<String, Object> payload = Map.of(
                "partyId", partyId,
                "oldHostUserId", oldHostUserId,
                "newHostUserId", newHostUserId
        );
        publish(MessageTopics.PARTY_HOST_CHANGED, "party.host.changed", payload);
    }
    
    /**
     * Publish party status changed event
     */
    public void publishPartyStatusChanged(Long partyId, String oldStatus, String newStatus) {
        Map<String, Object> payload = Map.of(
                "partyId", partyId,
                "oldStatus", oldStatus,
                "newStatus", newStatus
        );
        publish(MessageTopics.PARTY_STATUS_CHANGED, "party.status.changed", payload);
    }
    
    /**
     * Publish party invitation declined event (invitee declined, notify inviter).
     */
    public void publishPartyInvitationDeclined(Long partyId, Long inviterUserId, Long inviteeUserId, Long invitationId) {
        Map<String, Object> payload = Map.of(
                "partyId", partyId,
                "inviterUserId", inviterUserId,
                "inviteeUserId", inviteeUserId,
                "invitationId", invitationId
        );
        publish(MessageTopics.PARTY_INVITATION_DECLINED, "party.invitation.declined", payload);
    }

    /**
     * Publish party invitation cancelled event (inviter withdrew it, notify invitee).
     */
    public void publishPartyInvitationCancelled(Long partyId, Long inviterUserId, Long inviteeUserId, Long invitationId) {
        Map<String, Object> payload = Map.of(
                "partyId", partyId,
                "inviterUserId", inviterUserId,
                "inviteeUserId", inviteeUserId,
                "invitationId", invitationId
        );
        publish(MessageTopics.PARTY_INVITATION_CANCELLED, "party.invitation.cancelled", payload);
    }

    /**
     * Publish party invitation expired event (invitee never responded — notify both sides).
     */
    public void publishPartyInvitationExpired(Long partyId, Long inviterUserId, Long inviteeUserId, Long invitationId) {
        Map<String, Object> payload = Map.of(
                "partyId", partyId,
                "inviterUserId", inviterUserId,
                "inviteeUserId", inviteeUserId,
                "invitationId", invitationId
        );
        publish(MessageTopics.PARTY_INVITATION_EXPIRED, "party.invitation.expired", payload);
    }
    
    // ==================== Subscription Methods ====================
    
    /**
     * Subscribe to topic
     */
    public void subscribe(String topic, MessageSubscriber subscriber) {
        messageBroker.subscribe(topic, subscriber);
    }
    
    /**
     * Subscribe to topic pattern
     */
    public void subscribePattern(String pattern, MessageSubscriber subscriber) {
        messageBroker.subscribePattern(pattern, subscriber);
    }
    
    /**
     * Unsubscribe from topic
     */
    public void unsubscribe(String topic) {
        messageBroker.unsubscribe(topic);
    }
}

