package com.nighthunt.queue.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.queue.entity.QueuedRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Request Queue Service
 * Manages request queue using Redis for distributed systems
 * Uses priority queue (ZSet) to process high-priority requests first
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestQueueService {
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    
    private static final String QUEUE_KEY_PREFIX = "request_queue:";
    private static final String QUEUE_PROCESSING_KEY_PREFIX = "request_queue_processing:";
    private static final int DEFAULT_QUEUE_TIMEOUT_SECONDS = 30; // Request expires after 30 seconds
    private static final int MAX_QUEUE_SIZE = 10000; // Maximum requests in queue
    
    /**
     * Add request to queue
     * @param endpoint API endpoint
     * @param method HTTP method
     * @param identifier User ID, IP address, etc.
     * @param requestData Request body as JSON string
     * @param priority Request priority (0 = normal, 1 = high, 2 = urgent)
     * @return Request ID
     */
    public String enqueueRequest(String endpoint, String method, String identifier, 
                                 String requestData, int priority) {
        // Check queue size
        String queueKey = getQueueKey(endpoint);
        Long queueSize = redisTemplate.opsForZSet().count(queueKey, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        
        if (queueSize != null && queueSize >= MAX_QUEUE_SIZE) {
            log.warn("Request queue is full for endpoint: {}", endpoint);
            throw new BusinessException(ErrorCodes.RATE_LIMIT_EXCEEDED,
                    "Server is currently overloaded. Please try again later.");
        }
        
        // Create queued request
        String requestId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusSeconds(DEFAULT_QUEUE_TIMEOUT_SECONDS);
        
        QueuedRequest queuedRequest = QueuedRequest.builder()
                .requestId(requestId)
                .endpoint(endpoint)
                .method(method)
                .identifier(identifier)
                .queuedAt(now)
                .expiresAt(expiresAt)
                .priority(priority)
                .requestData(requestData)
                .status(QueuedRequest.RequestStatus.QUEUED)
                .build();
        
        // Add to priority queue (ZSet)
        // Score = priority (higher = processed first) + timestamp (older = processed first)
        // Formula: score = (priority * 1000000000) - timestamp_seconds
        // This ensures high priority requests are processed first, and within same priority, older requests first
        double score = (priority * 1_000_000_000.0) - (now.toEpochSecond(ZoneOffset.UTC));
        
        // Serialize QueuedRequest to JSON string for Redis storage
        try {
            String requestJson = objectMapper.writeValueAsString(queuedRequest);
            redisTemplate.opsForZSet().add(queueKey, requestJson, score);
        } catch (Exception e) {
            log.error("Error serializing queued request: {}", e.getMessage());
            throw new RuntimeException("Failed to queue request", e);
        }
        redisTemplate.expire(queueKey, DEFAULT_QUEUE_TIMEOUT_SECONDS + 60, TimeUnit.SECONDS);
        
        log.debug("Request queued: requestId={}, endpoint={}, priority={}, queueSize={}", 
                requestId, endpoint, priority, queueSize);
        
        return requestId;
    }
    
    /**
     * Get next request from queue (FIFO with priority)
     * @param endpoint API endpoint
     * @return Next queued request, or null if queue is empty
     */
    public QueuedRequest dequeueRequest(String endpoint) {
        String queueKey = getQueueKey(endpoint);
        
        // Get highest priority request (highest score = highest priority)
        Set<Object> requests = redisTemplate.opsForZSet().reverseRange(queueKey, 0, 0);
        
        if (requests == null || requests.isEmpty()) {
            return null;
        }
        
        // Deserialize request from JSON string
        String requestJson = (String) requests.iterator().next();
        QueuedRequest request;
        try {
            request = objectMapper.readValue(requestJson, QueuedRequest.class);
        } catch (Exception e) {
            log.error("Error deserializing queued request: {}", e.getMessage());
            // Remove invalid request from queue
            redisTemplate.opsForZSet().remove(queueKey, requestJson);
            return null;
        }
        
        // Check if expired
        if (request.getExpiresAt().isBefore(LocalDateTime.now())) {
        // Remove expired request
        redisTemplate.opsForZSet().remove(queueKey, requestJson);
            log.debug("Removed expired request from queue: requestId={}", request.getRequestId());
            return null;
        }
        
        // Move to processing queue
        request.setStatus(QueuedRequest.RequestStatus.PROCESSING);
        String processingKey = getProcessingKey(endpoint, request.getRequestId());
        try {
            String requestJsonProcessing = objectMapper.writeValueAsString(request);
            redisTemplate.opsForValue().set(processingKey, requestJsonProcessing, DEFAULT_QUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Error serializing request for processing queue: {}", e.getMessage());
        }
        
        // Remove from main queue
        redisTemplate.opsForZSet().remove(queueKey, requestJson);
        
        log.debug("Request dequeued: requestId={}, endpoint={}", request.getRequestId(), endpoint);
        
        return request;
    }
    
    /**
     * Mark request as completed and remove from processing queue
     */
    public void completeRequest(String endpoint, String requestId) {
        String processingKey = getProcessingKey(endpoint, requestId);
        redisTemplate.delete(processingKey);
        log.debug("Request completed: requestId={}, endpoint={}", requestId, endpoint);
    }
    
    /**
     * Mark request as failed and remove from processing queue
     */
    public void failRequest(String endpoint, String requestId) {
        String processingKey = getProcessingKey(endpoint, requestId);
        redisTemplate.delete(processingKey);
        log.debug("Request failed: requestId={}, endpoint={}", requestId, endpoint);
    }
    
    /**
     * Get queue size for endpoint
     */
    public long getQueueSize(String endpoint) {
        String queueKey = getQueueKey(endpoint);
        Long size = redisTemplate.opsForZSet().count(queueKey, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        return size != null ? size : 0;
    }
    
    /**
     * Get estimated wait time in seconds for endpoint
     * @param endpoint API endpoint
     * @return Estimated wait time in seconds
     */
    public int getEstimatedWaitTime(String endpoint) {
        long queueSize = getQueueSize(endpoint);
        // Assume average processing time of 100ms per request
        return (int) (queueSize * 0.1);
    }
    
    /**
     * Check if request is still in queue
     */
    public boolean isRequestQueued(String endpoint, String requestId) {
        String queueKey = getQueueKey(endpoint);
        Double score = redisTemplate.opsForZSet().score(queueKey, requestId);
        return score != null;
    }
    
    /**
     * Cleanup expired requests from queue (scheduled task)
     */
    public void cleanupExpiredRequests() {
        // This would be called by a scheduled task
        // For now, expiration is handled during dequeue
        log.debug("Cleaning up expired requests from queue");
    }
    
    private String getQueueKey(String endpoint) {
        return QUEUE_KEY_PREFIX + endpoint.replace("/", "_");
    }
    
    private String getProcessingKey(String endpoint, String requestId) {
        return QUEUE_PROCESSING_KEY_PREFIX + endpoint.replace("/", "_") + ":" + requestId;
    }
}

