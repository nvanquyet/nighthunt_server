package com.nighthunt.friend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Blocked User entity - Manages user blocking relationships.
 * If user A blocks user B:
 * - A cannot see B's activities or status
 * - B cannot send friend requests to A
 * - B cannot invite A to party
 * - If they were friends, friendship is deleted
 */
@Entity
@Table(name = "blocked_users", 
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_blocker_blocked", columnNames = {"blocker_user_id", "blocked_user_id"})
    },
    indexes = {
        @Index(name = "idx_blocked_users_blocker", columnList = "blocker_user_id"),
        @Index(name = "idx_blocked_users_blocked", columnList = "blocked_user_id")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BlockedUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * User who blocked someone.
     */
    @Column(name = "blocker_user_id", nullable = false)
    private Long blockerUserId;

    /**
     * User who got blocked.
     */
    @Column(name = "blocked_user_id", nullable = false)
    private Long blockedUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
