package com.nighthunt.ban.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Failed login attempt tracking
 * Tracks failed login attempts for auto-ban functionality
 */
@Entity
@Table(name = "failed_login_attempts", indexes = {
    @Index(name = "idx_identifier", columnList = "identifier"),
    @Index(name = "idx_ip_address", columnList = "ip_address"),
    @Index(name = "idx_device_fingerprint", columnList = "device_fingerprint"),
    @Index(name = "idx_last_attempt_at", columnList = "last_attempt_at"),
    @Index(name = "idx_is_banned", columnList = "is_banned")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FailedLoginAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "identifier", nullable = false, length = 255)
    private String identifier; // username or email

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Column(name = "device_fingerprint", length = 255)
    private String deviceFingerprint;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 1;

    @Column(name = "first_attempt_at", nullable = false, updatable = false)
    private LocalDateTime firstAttemptAt;

    @Column(name = "last_attempt_at", nullable = false)
    private LocalDateTime lastAttemptAt;

    @Column(name = "is_banned", nullable = false)
    @Builder.Default
    private Boolean isBanned = false;

    @Column(name = "ban_id")
    private Long banId; // Reference to bans table

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (firstAttemptAt == null) {
            firstAttemptAt = now;
        }
        if (lastAttemptAt == null) {
            lastAttemptAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        lastAttemptAt = LocalDateTime.now();
    }
}

