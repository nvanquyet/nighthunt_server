package com.nighthunt.ratelimit.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Rate limit tracking for fixed/sliding window
 * Tracks request counts for rate limiting
 */
@Entity
@Table(name = "rate_limit_tracking", indexes = {
    @Index(name = "idx_rule_identifier", columnList = "rule_id,identifier"),
    @Index(name = "idx_window_end", columnList = "window_end"),
    @Index(name = "idx_identifier", columnList = "identifier")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RateLimitTracking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "identifier", nullable = false, length = 255)
    private String identifier; // user_id, ip_address, or 'global'

    @Column(name = "request_count", nullable = false)
    @Builder.Default
    private Integer requestCount = 1;

    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDateTime windowEnd;

    @Column(name = "last_request_at", nullable = false)
    private LocalDateTime lastRequestAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (windowStart == null) {
            windowStart = now;
        }
        if (lastRequestAt == null) {
            lastRequestAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        lastRequestAt = LocalDateTime.now();
    }
}

