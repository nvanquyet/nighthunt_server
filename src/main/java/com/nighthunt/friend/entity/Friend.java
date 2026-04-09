package com.nighthunt.friend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Friend entity - Bidirectional friendship relationship.
 * When user A adds user B as friend, two rows are created: (A→B) and (B→A).
 * This allows efficient queries like "get all friends of user X".
 */
@Entity
@Table(name = "friends", 
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_friend", columnNames = {"user_id", "friend_user_id"})
    },
    indexes = {
        @Index(name = "idx_friends_user_id", columnList = "user_id"),
        @Index(name = "idx_friends_friend_user_id", columnList = "friend_user_id"),
        @Index(name = "idx_friends_status", columnList = "friendshipStatus")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Friend {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Owner of this friendship record.
     * User who views their friend list will see friend_user_id in the list.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * The friend user.
     * This is the ID shown in user_id's friend list.
     */
    @Column(name = "friend_user_id", nullable = false)
    private Long friendUserId;

    /**
     * Friendship status: ACTIVE or BLOCKED.
     * ACTIVE = normal friendship
     * BLOCKED = user_id blocked friend_user_id (deprecated - use blocked_users table instead)
     */
    @Column(name = "friendship_status", nullable = false, length = 20)
    @Builder.Default
    private String friendshipStatus = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
