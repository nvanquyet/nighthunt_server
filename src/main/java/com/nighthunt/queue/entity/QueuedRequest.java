package com.nighthunt.queue.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Represents a queued request waiting to be processed
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueuedRequest {
    private String requestId;
    private String endpoint;
    private String method;
    private String identifier; // user_id, ip_address, etc.
    private LocalDateTime queuedAt;
    private LocalDateTime expiresAt;
    private int priority; // Higher priority = processed first (0 = normal, 1 = high, 2 = urgent)
    private String requestData; // JSON string of request body
    private RequestStatus status;
    
    public enum RequestStatus {
        QUEUED,      // Waiting in queue
        PROCESSING,  // Currently being processed
        COMPLETED,   // Successfully processed
        FAILED,      // Processing failed
        EXPIRED      // Expired before processing
    }
}

