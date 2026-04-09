package com.nighthunt.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Persistent refresh token record.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Created on every successful login.</li>
 *   <li>Queried / rotated on {@code POST /auth/refresh-token}.</li>
 *   <li>Revoked (revoked=true) on logout or token rotation (old token marked revoked,
 *       new token created).</li>
 * </ol>
 */
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_rt_user_id", columnList = "user_id"),
        @Index(name = "idx_rt_token",   columnList = "token", unique = true),
        @Index(name = "idx_rt_expiry",  columnList = "expiry_date")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to users.id – cascade-deleted when user is deleted. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * Opaque token value (UUID-based, stored in plain text).
     * Only the hash needs to be compared, so no extra hashing layer is added here
     * (the token is long-lived random UUID, not a password).
     */
    @Column(nullable = false, unique = true, length = 512)
    private String token;

    /** Absolute point-in-time expiry. Reject after this timestamp. */
    @Column(name = "expiry_date", nullable = false)
    private LocalDateTime expiryDate;

    /**
     * {@code true} after the token has been consumed (rotated) or the user logged out.
     * The row is kept for auditing; a scheduled job may purge old revoked rows.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean revoked = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    /** Convenience: returns true when the token is still usable. */
    public boolean isValid() {
        return !revoked && LocalDateTime.now().isBefore(expiryDate);
    }
}
