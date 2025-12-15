package com.nighthunt.ban.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Ban record entity
 * Stores ban information for users, IPs, or devices
 */
@Entity
@Table(name = "bans", indexes = {
    @Index(name = "idx_user_id", columnList = "user_id"),
    @Index(name = "idx_ip_address", columnList = "ip_address"),
    @Index(name = "idx_device_fingerprint", columnList = "device_fingerprint"),
    @Index(name = "idx_expires_at", columnList = "expires_at"),
    @Index(name = "idx_is_active", columnList = "is_active"),
    @Index(name = "idx_ban_type", columnList = "ban_type")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ban {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "ban_type", nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
    @Enumerated(EnumType.STRING)
    private BanType banType; // USER, IP, DEVICE

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "device_fingerprint", length = 255)
    private String deviceFingerprint;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Column(name = "ban_duration_minutes", nullable = false)
    private Integer banDurationMinutes; // 0 = permanent

    @Column(name = "banned_at", nullable = false, updatable = false)
    private LocalDateTime bannedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt; // NULL = permanent

    @Column(name = "banned_by")
    private Long bannedBy; // Admin user ID, NULL = auto-ban

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "auto_unbanned", nullable = false)
    @Builder.Default
    private Boolean autoUnbanned = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (bannedAt == null) {
            bannedAt = now;
        }
        if (expiresAt == null && banDurationMinutes > 0) {
            expiresAt = now.plusMinutes(banDurationMinutes);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum BanType {
        USER,   // Ban by user ID
        IP,     // Ban by IP address
        DEVICE  // Ban by device fingerprint
    }

    public boolean isExpired() {
        if (expiresAt == null) {
            return false; // Permanent ban
        }
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isPermanent() {
        return (banDurationMinutes != null && banDurationMinutes == 0) || expiresAt == null;
    }
}

