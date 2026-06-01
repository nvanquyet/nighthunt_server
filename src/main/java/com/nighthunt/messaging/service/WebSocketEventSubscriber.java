package com.nighthunt.messaging.service;

import com.nighthunt.friend.service.FriendCacheService;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.messaging.adapter.RedisMessageBroker;
import com.nighthunt.messaging.constants.MessageTopics;
import com.nighthunt.messaging.dto.Message;
import com.nighthunt.party.repository.PartyMemberRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * WebSocketEventSubscriber — bridges Redis Pub/Sub events to WebSocket clients.
 *
 * <p>Architecture flow:</p>
 * <pre>
 * Business Services (Friend/Party/Auth)
 *   → MessageBrokerService.publish()
 *   → Redis Pub/Sub channel
 *   → RedisMessageBroker (listener callback, Redis thread)
 *   → WebSocketEventSubscriber.handle*() — dispatched via @Async("wsEventExecutor")
 *   → ConnectionManager.sendToUser()
 *   → WebSocket → Unity Client
 * </pre>
 *
 * <p>All handler methods are annotated with {@code @Async("wsEventExecutor")} so that
 * Redis listener threads are never blocked by JPA queries or WebSocket I/O.
 * Any DB call runs on the {@code wsEventExecutor} pool, not on the Redis I/O thread.</p>
 *
 * <p>Friend-ID lookups are served from {@link FriendCacheService} (Redis, TTL 60 s)
 * to avoid a DB round-trip on every status-change broadcast.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketEventSubscriber {

    private final RedisMessageBroker messageBroker;
    private final ConnectionManager connectionManager;
    private final FriendCacheService friendCacheService;
    private final PartyMemberRepository partyMemberRepository;

    // ──────────────────────────────────────────────────────────────────────────
    // INIT
    // ──────────────────────────────────────────────────────────────────────────

    @PostConstruct
    public void initializeSubscriptions() {
        log.info("Initializing WebSocket event subscriptions...");
        subscribeFriendEvents();
        subscribePartyEvents();
        log.info("WebSocket event subscriptions initialized successfully");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // FRIEND SUBSCRIPTIONS
    // ──────────────────────────────────────────────────────────────────────────

    private void subscribeFriendEvents() {
        messageBroker.subscribe(MessageTopics.FRIEND_STATUS_CHANGED,    this::handleFriendStatusChanged);
        messageBroker.subscribe(MessageTopics.FRIEND_REQUEST_RECEIVED,  this::handleFriendRequestReceived);
        messageBroker.subscribe(MessageTopics.FRIEND_REQUEST_ACCEPTED,  this::handleFriendRequestAccepted);
        messageBroker.subscribe(MessageTopics.FRIEND_REQUEST_DECLINED,  this::handleFriendRequestDeclined);
        messageBroker.subscribe(MessageTopics.FRIEND_REQUEST_CANCELLED, this::handleFriendRequestCancelled);
        messageBroker.subscribe(MessageTopics.FRIEND_REMOVED,           this::handleFriendRemoved);
        messageBroker.subscribe(MessageTopics.FRIEND_BLOCKED,           this::handleFriendBlocked);
        log.info("Subscribed to friend system events");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PARTY SUBSCRIPTIONS
    // ──────────────────────────────────────────────────────────────────────────

    private void subscribePartyEvents() {
        messageBroker.subscribe(MessageTopics.PARTY_CREATED,              this::handlePartyCreated);
        messageBroker.subscribe(MessageTopics.PARTY_INVITATION_RECEIVED,  this::handlePartyInvitationReceived);
        messageBroker.subscribe(MessageTopics.PARTY_INVITATION_DECLINED,  this::handlePartyInvitationDeclined);
        messageBroker.subscribe(MessageTopics.PARTY_INVITATION_CANCELLED, this::handlePartyInvitationCancelled);
        messageBroker.subscribe(MessageTopics.PARTY_INVITATION_EXPIRED,   this::handlePartyInvitationExpired);
        messageBroker.subscribe(MessageTopics.PARTY_MEMBER_JOINED,        this::handlePartyMemberJoined);
        messageBroker.subscribe(MessageTopics.PARTY_MEMBER_LEFT,          this::handlePartyMemberLeft);
        messageBroker.subscribe(MessageTopics.PARTY_MEMBER_KICKED,        this::handlePartyMemberKicked);
        messageBroker.subscribe(MessageTopics.PARTY_DISBANDED,            this::handlePartyDisbanded);
        messageBroker.subscribe(MessageTopics.PARTY_HOST_CHANGED,         this::handlePartyHostChanged);
        messageBroker.subscribe(MessageTopics.PARTY_STATUS_CHANGED,       this::handlePartyStatusChanged);
        log.info("Subscribed to party system events");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FRIEND EVENT HANDLERS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Broadcast friend status change (online/offline/in-game) to all friends.
     * Friend IDs are served from Redis cache (TTL 60 s) to avoid repeated DB queries.
     */
    @Async("wsEventExecutor")
    public void handleFriendStatusChanged(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long userId        = getLong(payload, "userId");
            String oldStatus   = getString(payload, "oldStatus");
            String newStatus   = getString(payload, "newStatus");
            Long currentPartyId = getLong(payload, "currentPartyId");
            Long currentRoomId  = getLong(payload, "currentRoomId");

            List<Long> friendIds = friendCacheService.getFriendIds(userId);

            Map<String, Object> eventData = Map.of(
                "userId",         userId,
                "oldStatus",      oldStatus != null ? oldStatus : "",
                "newStatus",      newStatus != null ? newStatus : "",
                "currentPartyId", currentPartyId != null ? currentPartyId : 0,
                "currentRoomId",  currentRoomId  != null ? currentRoomId  : 0
            );

            for (Long friendId : friendIds) {
                connectionManager.sendToUser(friendId, "friend_status_changed", eventData);
            }

            log.debug("Broadcasted friend_status_changed for userId={} to {} friends", userId, friendIds.size());
        } catch (Exception e) {
            log.error("Error handling friend_status_changed: {}", e.getMessage(), e);
        }
    }

    @Async("wsEventExecutor")
    public void handleFriendRequestReceived(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long addresseeUserId    = getLong(payload,   "addresseeUserId");
            Long requesterUserId    = getLong(payload,   "requesterUserId");
            String requesterUsername = getString(payload, "requesterUsername");
            Long requestId          = getLong(payload,   "requestId");

            Map<String, Object> eventData = Map.of(
                "addresseeUserId",   addresseeUserId,
                "requesterUserId",   requesterUserId,
                "requesterUsername", requesterUsername != null ? requesterUsername : "",
                "requestId",         requestId
            );
            connectionManager.sendToUser(addresseeUserId, "friend_request_received", eventData);
            log.debug("Sent friend_request_received to userId={}", addresseeUserId);
        } catch (Exception e) {
            log.error("Error handling friend_request_received: {}", e.getMessage(), e);
        }
    }

    @Async("wsEventExecutor")
    public void handleFriendRequestAccepted(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long requesterUserId     = getLong(payload,   "requesterUserId");
            Long addresseeUserId     = getLong(payload,   "addresseeUserId");
            String addresseeUsername = getString(payload, "addresseeUsername");

            Map<String, Object> eventData = Map.of(
                "requesterUserId",   requesterUserId,
                "addresseeUserId",   addresseeUserId,
                "addresseeUsername", addresseeUsername != null ? addresseeUsername : ""
            );
            connectionManager.sendToUser(requesterUserId, "friend_request_accepted", eventData);
            log.debug("Sent friend_request_accepted to userId={}", requesterUserId);
        } catch (Exception e) {
            log.error("Error handling friend_request_accepted: {}", e.getMessage(), e);
        }
    }

    @Async("wsEventExecutor")
    public void handleFriendRequestDeclined(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long requesterUserId = getLong(payload, "requesterUserId");
            Long addresseeUserId = getLong(payload, "addresseeUserId");

            Map<String, Object> eventData = Map.of(
                "requesterUserId", requesterUserId,
                "addresseeUserId", addresseeUserId
            );
            connectionManager.sendToUser(requesterUserId, "friend_request_declined", eventData);
            log.debug("Sent friend_request_declined to userId={}", requesterUserId);
        } catch (Exception e) {
            log.error("Error handling friend_request_declined: {}", e.getMessage(), e);
        }
    }

    @Async("wsEventExecutor")
    public void handleFriendRequestCancelled(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long requesterUserId = getLong(payload, "requesterUserId");
            Long addresseeUserId = getLong(payload, "addresseeUserId");

            Map<String, Object> eventData = Map.of(
                "requesterUserId", requesterUserId,
                "addresseeUserId", addresseeUserId
            );
            connectionManager.sendToUser(addresseeUserId, "friend_request_cancelled", eventData);
            log.debug("Sent friend_request_cancelled to userId={}", addresseeUserId);
        } catch (Exception e) {
            log.error("Error handling friend_request_cancelled: {}", e.getMessage(), e);
        }
    }

    @Async("wsEventExecutor")
    public void handleFriendRemoved(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long userId       = getLong(payload, "userId");
            Long friendUserId = getLong(payload, "friendUserId");

            Map<String, Object> eventData = Map.of(
                "userId",       userId,
                "friendUserId", friendUserId
            );
            connectionManager.sendToUser(userId, "friend_removed", eventData);
            log.debug("Sent friend_removed to userId={}", userId);
        } catch (Exception e) {
            log.error("Error handling friend_removed: {}", e.getMessage(), e);
        }
    }

    @Async("wsEventExecutor")
    public void handleFriendBlocked(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long blockerUserId = getLong(payload, "blockerUserId");
            Long blockedUserId = getLong(payload, "blockedUserId");

            Map<String, Object> eventData = Map.of(
                "blockerUserId", blockerUserId,
                "blockedUserId", blockedUserId
            );
            connectionManager.sendToUser(blockedUserId, "friend_blocked", eventData);
            log.debug("Sent friend_blocked to userId={}", blockedUserId);
        } catch (Exception e) {
            log.error("Error handling friend_blocked: {}", e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PARTY EVENT HANDLERS
    // ══════════════════════════════════════════════════════════════════════════

    @Async("wsEventExecutor")
    public void handlePartyCreated(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long partyId      = getLong(payload,   "partyId");
            Long hostUserId   = getLong(payload,   "hostUserId");
            String hostUsername = getString(payload, "hostUsername");

            Map<String, Object> eventData = Map.of(
                "partyId",      partyId,
                "hostUserId",   hostUserId,
                "hostUsername", hostUsername != null ? hostUsername : ""
            );
            connectionManager.sendToUser(hostUserId, "party_created", eventData);
            log.debug("Sent party_created to hostUserId={}", hostUserId);
        } catch (Exception e) {
            log.error("Error handling party_created: {}", e.getMessage(), e);
        }
    }

    @Async("wsEventExecutor")
    public void handlePartyInvitationReceived(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long partyId        = getLong(payload,   "partyId");
            Long inviteeUserId  = getLong(payload,   "inviteeUserId");
            Long inviterUserId  = getLong(payload,   "inviterUserId");
            String inviterUsername = getString(payload, "inviterUsername");
            Long invitationId   = getLong(payload,   "invitationId");

            Map<String, Object> eventData = Map.of(
                "partyId",        partyId,
                "inviteeUserId",  inviteeUserId,
                "inviterUserId",  inviterUserId,
                "inviterUsername", inviterUsername != null ? inviterUsername : "",
                "invitationId",   invitationId
            );
            connectionManager.sendToUser(inviteeUserId, "party_invitation_received", eventData);
            log.debug("Sent party_invitation_received to userId={}", inviteeUserId);
        } catch (Exception e) {
            log.error("Error handling party_invitation_received: {}", e.getMessage(), e);
        }
    }

    @Async("wsEventExecutor")
    public void handlePartyMemberJoined(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long partyId   = getLong(payload,   "partyId");
            Long userId    = getLong(payload,   "userId");
            String username = getString(payload, "username");
            Integer joinOrder = getInteger(payload, "joinOrder");

            List<Long> memberIds = partyMemberRepository.findUserIdsByPartyId(partyId);
            Map<String, Object> eventData = Map.of(
                "partyId",   partyId,
                "userId",    userId,
                "username",  username != null ? username : "",
                "joinOrder", joinOrder != null ? joinOrder : 0
            );
            for (Long memberId : memberIds) {
                connectionManager.sendToUser(memberId, "party_member_joined", eventData);
            }
            log.debug("Broadcasted party_member_joined for userId={} to {} members", userId, memberIds.size());
        } catch (Exception e) {
            log.error("Error handling party_member_joined: {}", e.getMessage(), e);
        }
    }

    @Async("wsEventExecutor")
    public void handlePartyMemberLeft(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long partyId = getLong(payload, "partyId");
            Long userId  = getLong(payload, "userId");

            List<Long> memberIds = partyMemberRepository.findUserIdsByPartyId(partyId);
            Map<String, Object> eventData = Map.of("partyId", partyId, "userId", userId);

            for (Long memberId : memberIds) {
                connectionManager.sendToUser(memberId, "party_member_left", eventData);
            }
            connectionManager.sendToUser(userId, "party_member_left", eventData);
            log.debug("Broadcasted party_member_left for userId={} to {} members", userId, memberIds.size());
        } catch (Exception e) {
            log.error("Error handling party_member_left: {}", e.getMessage(), e);
        }
    }

    @Async("wsEventExecutor")
    public void handlePartyMemberKicked(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long partyId      = getLong(payload, "partyId");
            Long kickedUserId = getLong(payload, "kickedUserId");
            Long kickerUserId = getLong(payload, "kickerUserId");

            List<Long> memberIds = partyMemberRepository.findUserIdsByPartyId(partyId);
            Map<String, Object> eventData = Map.of(
                "partyId",      partyId,
                "kickedUserId", kickedUserId,
                "kickerUserId", kickerUserId
            );
            for (Long memberId : memberIds) {
                connectionManager.sendToUser(memberId, "party_member_kicked", eventData);
            }
            connectionManager.sendToUser(kickedUserId, "party_member_kicked", eventData);
            log.debug("Broadcasted party_member_kicked for userId={}", kickedUserId);
        } catch (Exception e) {
            log.error("Error handling party_member_kicked: {}", e.getMessage(), e);
        }
    }

    @Async("wsEventExecutor")
    public void handlePartyDisbanded(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long partyId    = getLong(payload, "partyId");
            Long hostUserId = getLong(payload, "hostUserId");

            @SuppressWarnings("unchecked")
            List<?> rawIds = payload.get("memberIds") instanceof List
                    ? (List<?>) payload.get("memberIds") : List.of();

            Map<String, Object> eventData = Map.of("partyId", partyId, "hostUserId", hostUserId);

            if (rawIds.isEmpty()) {
                connectionManager.sendToUser(hostUserId, "party_disbanded", eventData);
            } else {
                for (Object rawId : rawIds) {
                    Long memberId = rawId instanceof Number
                            ? ((Number) rawId).longValue()
                            : getLong(Map.of("v", rawId), "v");
                    connectionManager.sendToUser(memberId, "party_disbanded", eventData);
                }
            }
            log.debug("Broadcasted party_disbanded for partyId={} to {} member(s)", partyId, rawIds.size());
        } catch (Exception e) {
            log.error("Error handling party_disbanded: {}", e.getMessage(), e);
        }
    }

    @Async("wsEventExecutor")
    public void handlePartyHostChanged(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long partyId      = getLong(payload, "partyId");
            Long oldHostUserId = getLong(payload, "oldHostUserId");
            Long newHostUserId = getLong(payload, "newHostUserId");

            List<Long> memberIds = partyMemberRepository.findUserIdsByPartyId(partyId);
            Map<String, Object> eventData = Map.of(
                "partyId",       partyId,
                "oldHostUserId", oldHostUserId,
                "newHostUserId", newHostUserId
            );
            for (Long memberId : memberIds) {
                connectionManager.sendToUser(memberId, "party_host_changed", eventData);
            }
            log.debug("Broadcasted party_host_changed for partyId={}", partyId);
        } catch (Exception e) {
            log.error("Error handling party_host_changed: {}", e.getMessage(), e);
        }
    }

    @Async("wsEventExecutor")
    public void handlePartyStatusChanged(Message message) {
        try {
            Map<String, Object> payload = message.getPayload();
            Long partyId   = getLong(payload,   "partyId");
            String oldStatus = getString(payload, "oldStatus");
            String newStatus = getString(payload, "newStatus");

            List<Long> memberIds = partyMemberRepository.findUserIdsByPartyId(partyId);
            Map<String, Object> eventData = Map.of(
                "partyId",   partyId,
                "oldStatus", oldStatus,
                "newStatus", newStatus
            );
            for (Long memberId : memberIds) {
                connectionManager.sendToUser(memberId, "party_status_changed", eventData);
            }
            log.debug("Broadcasted party_status_changed ({} → {}) for partyId={}", oldStatus, newStatus, partyId);
        } catch (Exception e) {
            log.error("Error handling party_status_changed: {}", e.getMessage(), e);
        }
    }

    @Async("wsEventExecutor")
    public void handlePartyInvitationDeclined(Message message) {
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
            connectionManager.sendToUser(inviterUserId, "party_invitation_declined", eventData);
            log.debug("Sent party_invitation_declined to userId={}", inviterUserId);
        } catch (Exception e) {
            log.error("Error handling party_invitation_declined: {}", e.getMessage(), e);
        }
    }

    @Async("wsEventExecutor")
    public void handlePartyInvitationCancelled(Message message) {
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
            connectionManager.sendToUser(inviteeUserId, "party_invitation_cancelled", eventData);
            log.debug("Sent party_invitation_cancelled to userId={}", inviteeUserId);
        } catch (Exception e) {
            log.error("Error handling party_invitation_cancelled: {}", e.getMessage(), e);
        }
    }

    @Async("wsEventExecutor")
    public void handlePartyInvitationExpired(Message message) {
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
            connectionManager.sendToUser(inviteeUserId, "party_invitation_expired", eventData);
            connectionManager.sendToUser(inviterUserId, "party_invitation_expired", eventData);
            log.debug("Sent party_invitation_expired to invitee={} and inviter={}", inviteeUserId, inviterUserId);
        } catch (Exception e) {
            log.error("Error handling party_invitation_expired: {}", e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ══════════════════════════════════════════════════════════════════════════

    private Long getLong(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null)              return 0L;
        if (value instanceof Long)      return (Long) value;
        if (value instanceof Integer)   return ((Integer) value).longValue();
        if (value instanceof String)    {
            try { return Long.parseLong((String) value); } catch (NumberFormatException e) { return 0L; }
        }
        return 0L;
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : "";
    }

    private Integer getInteger(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null)            return 0;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Long)    return ((Long) value).intValue();
        if (value instanceof String)  {
            try { return Integer.parseInt((String) value); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }
}
