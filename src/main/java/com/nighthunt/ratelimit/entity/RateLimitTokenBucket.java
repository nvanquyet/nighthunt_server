package com.nighthunt.ratelimit.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Token bucket state for token bucket rate limiting
 */
@Entity
@Table(name = "rate_limit_token_buckets", indexes = {
    @Index(name = "idx_identifier", columnList = "identifier")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RateLimitTokenBucket {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "identifier", nullable = false, length = 255)
    private String identifier; // user_id, ip_address, or 'global'

    @Column(name = "tokens", nullable = false)
    private Integer tokens;

    @Column(name = "last_refill_at", nullable = false)
    private LocalDateTime lastRefillAt;

    @PrePersist
    protected void onCreate() {
        if (lastRefillAt == null) {
            lastRefillAt = LocalDateTime.now();
        }
    }
}

