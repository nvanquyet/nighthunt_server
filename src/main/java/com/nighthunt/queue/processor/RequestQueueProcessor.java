package com.nighthunt.queue.processor;

import com.nighthunt.queue.entity.QueuedRequest;
import com.nighthunt.queue.service.RequestQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request Queue Processor
 * Processes queued requests in background
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestQueueProcessor {
    
    private final RequestQueueService requestQueueService;
    
    // Track active endpoints that need queue processing
    private final Set<String> activeEndpoints = ConcurrentHashMap.newKeySet();
    
    /**
     * Process queue for all active endpoints
     * Runs every 100ms to process requests quickly
     */
    @Scheduled(fixedDelay = 100) // Process every 100ms
    public void processQueues() {
        for (String endpoint : activeEndpoints) {
            try {
                QueuedRequest request = requestQueueService.dequeueRequest(endpoint);
                if (request != null) {
                    // Request will be processed by the interceptor/filter
                    // This just moves requests from queue to processing
                    log.debug("Processing queued request: requestId={}, endpoint={}", 
                            request.getRequestId(), endpoint);
                }
            } catch (Exception e) {
                log.error("Error processing queue for endpoint {}: {}", endpoint, e.getMessage());
            }
        }
    }
    
    /**
     * Register endpoint for queue processing
     */
    public void registerEndpoint(String endpoint) {
        activeEndpoints.add(endpoint);
    }
    
    /**
     * Unregister endpoint from queue processing
     */
    public void unregisterEndpoint(String endpoint) {
        activeEndpoints.remove(endpoint);
    }
    
    /**
     * Cleanup expired requests (runs every minute)
     */
    @Scheduled(fixedDelay = 60000) // Every minute
    public void cleanupExpiredRequests() {
        requestQueueService.cleanupExpiredRequests();
    }
}

