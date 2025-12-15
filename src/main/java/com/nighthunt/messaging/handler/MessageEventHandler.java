package com.nighthunt.messaging.handler;

import com.nighthunt.messaging.constants.MessageTopics;
import com.nighthunt.messaging.dto.Message;
import com.nighthunt.messaging.port.MessageSubscriber;
import com.nighthunt.messaging.service.MessageBrokerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;

/**
 * Message Event Handler
 * Subscribes to message topics and handles events
 * Can be extended to handle specific events
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageEventHandler implements MessageSubscriber {
    
    private final MessageBrokerService messageBroker;
    
    @PostConstruct
    public void init() {
        // Subscribe to all room events
        messageBroker.subscribePattern(MessageTopics.PATTERN_ROOM_ALL, this);
        
        // Subscribe to all auth events
        messageBroker.subscribePattern(MessageTopics.PATTERN_AUTH_ALL, this);
        
        // Subscribe to all match events
        messageBroker.subscribePattern(MessageTopics.PATTERN_MATCH_ALL, this);
        
        log.info("Message Event Handler initialized and subscribed to topics");
    }
    
    @Override
    public void handle(Message message) {
        try {
            log.debug("Handling message: topic={}, type={}, id={}", 
                    message.getTopic(), message.getType(), message.getId());
            
            // Route message to appropriate handler based on topic
            switch (message.getTopic()) {
                case MessageTopics.ROOM_CREATED:
                    handleRoomCreated(message);
                    break;
                case MessageTopics.ROOM_UPDATED:
                    handleRoomUpdated(message);
                    break;
                case MessageTopics.ROOM_PLAYER_JOINED:
                    handlePlayerJoined(message);
                    break;
                case MessageTopics.ROOM_PLAYER_LEFT:
                    handlePlayerLeft(message);
                    break;
                case MessageTopics.AUTH_USER_LOGIN:
                    handleUserLogin(message);
                    break;
                case MessageTopics.AUTH_USER_LOGOUT:
                    handleUserLogout(message);
                    break;
                default:
                    log.debug("No specific handler for topic: {}", message.getTopic());
            }
        } catch (Exception e) {
            log.error("Error handling message: topic={}, type={}, error={}", 
                    message.getTopic(), message.getType(), e.getMessage());
        }
    }
    
    private void handleRoomCreated(Message message) {
        Map<String, Object> payload = message.getPayload();
        Long roomId = getLong(payload, "roomId");
        Long ownerId = getLong(payload, "ownerId");
        log.info("Room created event: roomId={}, ownerId={}", roomId, ownerId);
        // Can add additional processing here (e.g., analytics, notifications)
    }
    
    private void handleRoomUpdated(Message message) {
        Map<String, Object> payload = message.getPayload();
        Long roomId = getLong(payload, "roomId");
        log.debug("Room updated event: roomId={}", roomId);
        // Can add additional processing here
    }
    
    private void handlePlayerJoined(Message message) {
        Map<String, Object> payload = message.getPayload();
        Long roomId = getLong(payload, "roomId");
        Long userId = getLong(payload, "userId");
        log.debug("Player joined event: roomId={}, userId={}", roomId, userId);
        // Can add additional processing here (e.g., update analytics, send notifications)
    }
    
    private void handlePlayerLeft(Message message) {
        Map<String, Object> payload = message.getPayload();
        Long roomId = getLong(payload, "roomId");
        Long userId = getLong(payload, "userId");
        log.debug("Player left event: roomId={}, userId={}", roomId, userId);
        // Can add additional processing here
    }
    
    private void handleUserLogin(Message message) {
        Map<String, Object> payload = message.getPayload();
        Long userId = getLong(payload, "userId");
        String username = getString(payload, "username");
        log.info("User login event: userId={}, username={}", userId, username);
        // Can add additional processing here (e.g., update user activity, analytics)
    }
    
    private void handleUserLogout(Message message) {
        Map<String, Object> payload = message.getPayload();
        Long userId = getLong(payload, "userId");
        log.info("User logout event: userId={}", userId);
        // Can add additional processing here
    }
    
    // Helper methods
    private Long getLong(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }
    
    private String getString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value != null ? value.toString() : null;
    }
}

