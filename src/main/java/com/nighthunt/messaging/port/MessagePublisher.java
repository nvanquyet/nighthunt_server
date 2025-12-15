package com.nighthunt.messaging.port;

import com.nighthunt.messaging.dto.Message;

/**
 * Message Publisher interface
 * Publishes messages to topics/channels
 */
public interface MessagePublisher {
    /**
     * Publish message to topic
     * @param topic Topic/channel name
     * @param message Message to publish
     */
    void publish(String topic, Message message);
    
    /**
     * Publish message to topic (convenience method)
     * @param topic Topic/channel name
     * @param messageType Message type
     * @param payload Message payload
     */
    void publish(String topic, String messageType, Object payload);
    
    /**
     * Publish message with priority
     * @param topic Topic/channel name
     * @param message Message to publish
     * @param priority Message priority
     */
    void publish(String topic, Message message, int priority);
}

