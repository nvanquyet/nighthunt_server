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
        String endpoint = request.getRequestURI();
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
            
            response.setStatus(429); // HTTP 429 Too Many Requests
            response.setHeader("X-RateLimit-Limit", "Exceeded");
            response.setHeader("Retry-After", "60"); // Suggest retry after 60 seconds
            
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

