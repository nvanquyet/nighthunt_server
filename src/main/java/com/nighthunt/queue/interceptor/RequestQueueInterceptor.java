package com.nighthunt.queue.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nighthunt.queue.processor.RequestQueueProcessor;
import com.nighthunt.queue.service.RequestQueueService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.BufferedReader;
import java.util.stream.Collectors;

/**
 * Request Queue Interceptor
 * Intercepts requests and queues them if server is overloaded
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestQueueInterceptor implements HandlerInterceptor {
    
    private final RequestQueueService requestQueueService;
    private final RequestQueueProcessor requestQueueProcessor;
    private final ObjectMapper objectMapper;
    
    // Thresholds for queue activation
    private static final int QUEUE_THRESHOLD_REQUESTS_PER_SECOND = 100; // Start queuing if > 100 req/s
    private static final double QUEUE_THRESHOLD_CPU_USAGE = 0.80; // Start queuing if CPU > 80%
    
    // Track request rate
    private volatile long requestCount = 0;
    private volatile long lastResetTime = System.currentTimeMillis();
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Use getServletPath() to stay consistent with RateLimitInterceptor
        String endpoint = request.getServletPath();
        String method = request.getMethod();
        
        // Skip queue for certain endpoints (health checks, static resources, etc.)
        if (shouldSkipQueue(endpoint)) {
            return true;
        }
        
        // Check if we should queue requests
        if (shouldQueueRequests(endpoint)) {
            return handleQueuedRequest(request, response, endpoint, method);
        }
        
        // Rate limiting is handled exclusively by RateLimitInterceptor (order=1).
        // Do NOT call checkRateLimit here — it would double-count every request.
        return true;
    }
    
    /**
     * Handle request queuing
     */
    private boolean handleQueuedRequest(HttpServletRequest request, HttpServletResponse response, 
                                       String endpoint, String method) {
        try {
            // Read request body
            String requestBody = readRequestBody(request);
            String identifier = getIdentifier(request);
            
            // Determine priority (can be based on user type, endpoint, etc.)
            int priority = determinePriority(request, endpoint);
            
            // Enqueue request
            String requestId = requestQueueService.enqueueRequest(endpoint, method, identifier, requestBody, priority);
            
            // Register endpoint for processing
            requestQueueProcessor.registerEndpoint(endpoint);
            
            // Return queue status to client
            response.setStatus(HttpServletResponse.SC_ACCEPTED); // 202 Accepted
            response.setContentType("application/json");
            response.getWriter().write(String.format(
                    "{\"success\":false,\"message\":\"Request queued. Please wait.\",\"errorCode\":\"REQUEST_QUEUED\",\"requestId\":\"%s\",\"estimatedWaitTime\":%d}",
                    requestId, requestQueueService.getEstimatedWaitTime(endpoint)
            ));
            
            log.info("Request queued: requestId={}, endpoint={}, method={}, priority={}", 
                    requestId, endpoint, method, priority);
            
            return false; // Don't continue processing
        } catch (Exception e) {
            log.error("Error queuing request: {}", e.getMessage());
            // If queue fails, allow normal processing (graceful degradation)
            return true;
        }
    }
    
    /**
     * Check if requests should be queued
     */
    private boolean shouldQueueRequests(String endpoint) {
        // Check request rate
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastResetTime > 1000) { // Reset every second
            requestCount = 0;
            lastResetTime = currentTime;
        }
        requestCount++;
        
        // Queue if request rate is too high
        if (requestCount > QUEUE_THRESHOLD_REQUESTS_PER_SECOND) {
            return true;
        }
        
        // Could also check CPU usage, memory, etc.
        // For now, just use request rate
        
        return false;
    }
    
    /**
     * Check if endpoint should skip queue
     */
    private boolean shouldSkipQueue(String endpoint) {
        // Skip queue for health checks, static resources, etc.
        if (endpoint.startsWith("/actuator") ||
               endpoint.startsWith("/dashboard") ||
               endpoint.startsWith("/static") ||
               endpoint.equals("/health")) {
            return true;
        }
        // Auth endpoints need synchronous response (JWT token, credentials).
        // Queueing them returns 202 with no token, breaking all downstream calls.
        if (endpoint.startsWith("/auth/")) {
            return true;
        }
        // Read-heavy endpoints: always serve synchronously to avoid 202 confusing clients.
        if (endpoint.startsWith("/profile") ||
               endpoint.startsWith("/friends") ||
               endpoint.startsWith("/game-modes") ||
               endpoint.startsWith("/match/") ||
               endpoint.startsWith("/rooms") ||
               endpoint.startsWith("/matchmaking")) {
            return true;
        }
        return false;
    }
    
    /**
     * Read request body
     */
    private String readRequestBody(HttpServletRequest request) {
        try {
            BufferedReader reader = request.getReader();
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Get identifier (user ID, IP address, etc.)
     */
    private String getIdentifier(HttpServletRequest request) {
        // Try to get user ID from authentication
        String userId = request.getHeader("X-User-Id");
        if (userId != null) {
            return "user:" + userId;
        }
        
        // Fallback to IP address
        return "ip:" + request.getRemoteAddr();
    }
    
    /**
     * Determine request priority
     */
    private int determinePriority(HttpServletRequest request, String endpoint) {
        // High priority for critical endpoints
        if (endpoint.contains("/auth/login") || endpoint.contains("/auth/auto-login")) {
            return 1; // High priority
        }
        
        // Urgent priority for game-critical endpoints
        if (endpoint.contains("/rooms/") && (endpoint.contains("/start") || endpoint.contains("/ready"))) {
            return 2; // Urgent
        }
        
        // Normal priority for everything else
        return 0;
    }
}

