package com.nighthunt.messaging.port;

import com.nighthunt.messaging.dto.Message;

/**
 * Message Subscriber interface
 * Subscribes to topics/channels and handles messages
 */
@FunctionalInterface
public interface MessageSubscriber {
    /**
     * Handle received message
     * @param message Received message
     */
    void handle(Message message);
}

