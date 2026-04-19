package com.nighthunt.ratelimit.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Rate limit rule configuration
 * Defines rate limiting rules for different endpoints
 */
@Entity
@Table(name = "rate_limit_rules", indexes = {
    @Index(name = "idx_endpoint_pattern", columnList = "endpoint_pattern"),
    @Index(name = "idx_is_active", columnList = "is_active"),
    @Index(name = "idx_scope", columnList = "scope")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RateLimitRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "endpoint_pattern", nullable = false, unique = true, length = 255)
    private String endpointPattern; // e.g., '/auth/login', '/rooms/*'

    @Column(name = "method", length = 10)
    private String method; // 'GET', 'POST', 'PUT', 'DELETE', '*' for all

    @Column(name = "limit_type", nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
    @Enumerated(EnumType.STRING)
    private LimitType limitType; // FIXED_WINDOW, SLIDING_WINDOW, TOKEN_BUCKET

    @Column(name = "max_requests", nullable = false)
    private Integer maxRequests;

    @Column(name = "window_seconds", nullable = false)
    private Integer windowSeconds; // Time window in seconds

    @Column(name = "refill_rate")
    private Integer refillRate; // For TOKEN_BUCKET: tokens per second

    @Column(name = "bucket_size")
    private Integer bucketSize; // For TOKEN_BUCKET: max tokens

    @Column(name = "scope", nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Scope scope = Scope.USER; // USER, IP, GLOBAL

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum LimitType {
        FIXED_WINDOW,    // Fixed time window
        SLIDING_WINDOW,  // Sliding time window
        TOKEN_BUCKET     // Token bucket algorithm
    }

    public enum Scope {
        USER,   // Rate limit per user
        IP,     // Rate limit per IP address
        GLOBAL  // Global rate limit
    }

    public boolean matchesEndpoint(String endpoint, String httpMethod) {
        boolean endpointMatches = matchesPattern(endpointPattern, endpoint);
        boolean methodMatches   = method == null || method.equals("*") || method.equalsIgnoreCase(httpMethod);
        return endpointMatches && methodMatches;
    }

    /**
     * Simple glob-style path matching that supports '*' wildcards at any position.
     * Examples:
     *   /*              matches everything
     *   /rooms/*&#47;ready  matches /rooms/abc123/ready
     *   /auth/login     matches exactly /auth/login
     */
    private boolean matchesPattern(String pattern, String path) {
        if ("*".equals(pattern)) return true;
        if (pattern.equals(path))  return true;

        // Split on '*' and verify each segment is present in order
        String[] parts = pattern.split("\\*", -1);
        int pos = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                // Leading/trailing/consecutive '*' — skip
                if (i == parts.length - 1) return true; // trailing '*' matches rest
                continue;
            }
            int idx = path.indexOf(part, pos);
            if (idx == -1) return false;
            // First segment must start at position 0
            if (i == 0 && idx != 0) return false;
            pos = idx + part.length();
        }
        // All parts matched; if last char of pattern is '*', any suffix is fine
        return pattern.endsWith("*") || pos == path.length();
    }
}

