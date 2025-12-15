package com.nighthunt.messaging.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Standard message format for Message Broker
 * Used for pub/sub, event streaming, and async processing
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {
    @JsonProperty("id")
    private String id; // Unique message ID
    
    @JsonProperty("type")
    private String type; // Message type (event name)
    
    @JsonProperty("topic")
    private String topic; // Topic/channel name
    
    @JsonProperty("payload")
    private Map<String, Object> payload; // Message data
    
    @JsonProperty("timestamp")
    private LocalDateTime timestamp; // Message timestamp
    
    @JsonProperty("source")
    private String source; // Source service (e.g., "room-service", "auth-service")
    
    @JsonProperty("correlationId")
    private String correlationId; // For request-response correlation
    
    @JsonProperty("retryCount")
    private Integer retryCount; // For retry mechanism
    
    @JsonProperty("priority")
    private Integer priority; // Message priority (0 = normal, 1 = high, 2 = urgent)
}

