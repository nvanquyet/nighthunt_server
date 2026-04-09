package com.nighthunt.friend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Friend Request entity - Manages friend request lifecycle.
 * Statuses: PENDING, ACCEPTED, DECLINED, CANCELLED
 */
@Entity
@Table(name = "friend_requests", 
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_friend_request", columnNames = {"requester_user_id", "addressee_user_id"})
    },
    indexes = {
        @Index(name = "idx_friend_requests_addressee", columnList = "addressee_user_id, request_status"),
        @Index(name = "idx_friend_requests_requester", columnList = "requester_user_id"),
        @Index(name = "idx_friend_requests_expires", columnList = "expires_at")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FriendRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * User who sent the friend request.
     */
    @Column(name = "requester_user_id", nullable = false)
    private Long requesterUserId;

    /**
     * User who receives the friend request.
     */
    @Column(name = "addressee_user_id", nullable = false)
    private Long addresseeUserId;

    /**
     * Request status:
     * - PENDING: Waiting for response
     * - ACCEPTED: Addressee accepted → friendship created
     * - DECLINED: Addressee declined
     * - CANCELLED: Requester cancelled before acceptance
     */
    @Column(name = "request_status", nullable = false, length = 20)
    @Builder.Default
    private String requestStatus = "PENDING";

    /**
     * Optional expiry date (e.g. 30 days).
     * NULL = never expires.
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

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
