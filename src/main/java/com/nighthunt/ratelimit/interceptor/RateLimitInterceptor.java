package com.nighthunt.ratelimit.interceptor;

import com.nighthunt.ratelimit.service.RateLimitService;
import com.nighthunt.security.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rate limiting interceptor
 * Intercepts HTTP requests and applies rate limiting
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {
    
    private final RateLimitService rateLimitService;
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Use getServletPath() (without context-path prefix) so rules like /auth/login match correctly
        String endpoint = request.getServletPath();
        String method = request.getMethod();
        
        // Get identifier based on authentication
        String identifier;
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId != null) {
            identifier = String.valueOf(userId);
        } else {
            // Use IP address if not authenticated
            identifier = getClientIpAddress(request);
        }
        
        try {
            // Check rate limit
            rateLimitService.checkRateLimit(endpoint, method, identifier);
            return true;
        } catch (Exception e) {
            // Rate limit exceeded
            log.warn("Rate limit exceeded: endpoint={}, method={}, identifier={}",
                    endpoint, method, identifier);

            // Parse retry-after from exception message if available (e.g. "per 60 seconds")
            int retryAfterSeconds = 60;
            try {
                String msg = e.getMessage();
                if (msg != null) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("per (\\d+) seconds").matcher(msg);
                    if (m.find()) {
                        retryAfterSeconds = Integer.parseInt(m.group(1));
                    }
                }
            } catch (Exception ignored) {}

            response.setStatus(429); // HTTP 429 Too Many Requests
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("X-RateLimit-Limit", "Exceeded");
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            try {
                response.getWriter().write(
                    "{\"success\":false,\"data\":null,\"message\":\"Too many requests. Please try again later.\",\"errorCode\":\"RATE_LIMIT_EXCEEDED\"}"
                );
            } catch (Exception writeEx) {
                log.error("Failed to write rate limit error response: {}", writeEx.getMessage());
            }
            return false;
        }
    }
    
    private String getClientIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip != null ? ip : "unknown";
    }
}

