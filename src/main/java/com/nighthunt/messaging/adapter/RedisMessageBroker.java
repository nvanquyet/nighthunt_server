package com.nighthunt.messaging.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nighthunt.messaging.dto.Message;
import com.nighthunt.messaging.port.MessagePublisher;
import com.nighthunt.messaging.port.MessageSubscriber;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis-based Message Broker implementation
 * Uses Redis Pub/Sub for message distribution
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisMessageBroker implements MessagePublisher {
    
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisMessageListenerContainer messageListenerContainer;
    private final ObjectMapper objectMapper;
    
    // Store subscribers by topic
    private final Map<String, MessageSubscriber> subscribers = new ConcurrentHashMap<>();
    
    // Store message listeners by topic
    private final Map<String, MessageListener> listeners = new ConcurrentHashMap<>();
    
    @Override
    public void publish(String topic, Message message) {
        publish(topic, message, 0);
    }
    
    @Override
    public void publish(String topic, String messageType, Object payload) {
        Message message = Message.builder()
                .id(UUID.randomUUID().toString())
                .type(messageType)
                .topic(topic)
                .payload(convertToMap(payload))
                .timestamp(LocalDateTime.now())
                .source(getServiceName())
                .priority(0)
                .build();
        publish(topic, message);
    }
    
    @Override
    public void publish(String topic, Message message, int priority) {
        try {
            // Set priority if not set
            if (message.getPriority() == null) {
                message.setPriority(priority);
            }
            
            // Ensure message has ID and timestamp
            if (message.getId() == null) {
                message.setId(UUID.randomUUID().toString());
            }
            if (message.getTimestamp() == null) {
                message.setTimestamp(LocalDateTime.now());
            }
            if (message.getSource() == null) {
                message.setSource(getServiceName());
            }
            
            // Serialize message to JSON
            String messageJson = objectMapper.writeValueAsString(message);
            
            // Publish to Redis channel — use StringRedisTemplate so the
            // already-serialized JSON string is sent as raw bytes without
            // double-serialization by GenericJackson2JsonRedisSerializer.
            stringRedisTemplate.convertAndSend(topic, messageJson);
            
            log.debug("Published message: topic={}, type={}, id={}", topic, message.getType(), message.getId());
        } catch (Exception e) {
            log.error("Error publishing message to topic {}: {}", topic, e.getMessage());
            throw new RuntimeException("Failed to publish message", e);
        }
    }
    
    /**
     * Subscribe to topic
     * @param topic Topic/channel name
     * @param subscriber Message handler
     */
    public void subscribe(String topic, MessageSubscriber subscriber) {
        if (subscribers.containsKey(topic)) {
            log.warn("Already subscribed to topic: {}", topic);
            return;
        }
        
        subscribers.put(topic, subscriber);
        
        // Create Redis message listener
        MessageListener listener = (redisMessage, pattern) -> {
            try {
                String messageJson = new String(redisMessage.getBody());
                Message message = objectMapper.readValue(messageJson, Message.class);
                
                log.debug("Received message: topic={}, type={}, id={}", topic, message.getType(), message.getId());
                
                // Handle message
                subscriber.handle(message);
            } catch (Exception e) {
                log.error("Error handling message from topic {}: {}", topic, e.getMessage());
            }
        };
        
        listeners.put(topic, listener);
        
        // Register listener with Redis
        messageListenerContainer.addMessageListener(listener, new ChannelTopic(topic));
        
        log.info("Subscribed to topic: {}", topic);
    }
    
    /**
     * Unsubscribe from topic
     */
    public void unsubscribe(String topic) {
        MessageListener listener = listeners.remove(topic);
        if (listener != null) {
            messageListenerContainer.removeMessageListener(listener, new ChannelTopic(topic));
        }
        subscribers.remove(topic);
        log.info("Unsubscribed from topic: {}", topic);
    }
    
    /**
     * Subscribe to multiple topics with pattern
     * @param pattern Topic pattern (e.g., "room.*", "auth.*")
     * @param subscriber Message handler
     */
    public void subscribePattern(String pattern, MessageSubscriber subscriber) {
        MessageListener listener = (redisMessage, matchedPattern) -> {
            try {
                String topic = matchedPattern.toString();
                String messageJson = new String(redisMessage.getBody());
                Message message = objectMapper.readValue(messageJson, Message.class);
                
                log.debug("Received message: pattern={}, topic={}, type={}, id={}", 
                        pattern, topic, message.getType(), message.getId());
                
                subscriber.handle(message);
            } catch (Exception e) {
                log.error("Error handling message from pattern {}: {}", pattern, e.getMessage());
            }
        };
        
        messageListenerContainer.addMessageListener(listener, new org.springframework.data.redis.listener.PatternTopic(pattern));
        log.info("Subscribed to pattern: {}", pattern);
    }
    
    private Map<String, Object> convertToMap(Object payload) {
        try {
            if (payload instanceof Map) {
                return (Map<String, Object>) payload;
            }
            return objectMapper.convertValue(payload, Map.class);
        } catch (Exception e) {
            log.error("Error converting payload to map: {}", e.getMessage());
            return Map.of("data", payload.toString());
        }
    }
    
    private String getServiceName() {
        return "night-hunt-server";
    }
}

