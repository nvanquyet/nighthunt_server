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

