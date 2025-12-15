package com.nighthunt.ban.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Concurrent login attempt tracking
 * Tracks multiple login attempts from same IP/device in short time window
 */
@Entity
@Table(name = "concurrent_login_attempts", indexes = {
    @Index(name = "idx_ip_address", columnList = "ip_address"),
    @Index(name = "idx_device_fingerprint", columnList = "device_fingerprint"),
    @Index(name = "idx_window_end", columnList = "window_end"),
    @Index(name = "idx_is_banned", columnList = "is_banned")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConcurrentLoginAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Column(name = "device_fingerprint", length = 255)
    private String deviceFingerprint;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 1;

    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDateTime windowEnd;

    @Column(name = "is_banned", nullable = false)
    @Builder.Default
    private Boolean isBanned = false;

    @Column(name = "ban_id")
    private Long banId; // Reference to bans table

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (windowStart == null) {
            windowStart = now;
        }
    }
}

