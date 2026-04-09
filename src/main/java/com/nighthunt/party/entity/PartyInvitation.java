package com.nighthunt.party.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Party Invitation entity - Manages party invitation lifecycle.
 * Invitations expire after 30 seconds (configurable).
 */
@Entity
@Table(name = "party_invitations", 
    indexes = {
        @Index(name = "idx_party_invitations_invitee", columnList = "invitee_user_id, invitation_status"),
        @Index(name = "idx_party_invitations_expires", columnList = "expires_at")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartyInvitation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Party ID.
     */
    @Column(name = "party_id", nullable = false)
    private Long partyId;

    /**
     * User who sent the invitation (party member).
     */
    @Column(name = "inviter_user_id", nullable = false)
    private Long inviterUserId;

    /**
     * User who receives the invitation.
     */
    @Column(name = "invitee_user_id", nullable = false)
    private Long inviteeUserId;

    /**
     * Invitation status:
     * - PENDING: Waiting for response
     * - ACCEPTED: Invitee joined party
     * - DECLINED: Invitee declined invitation
     * - EXPIRED: Invitation timed out (30 seconds)
     * - CANCELLED: Inviter or host cancelled invitation
     */
    @Column(name = "invitation_status", nullable = false, length = 20)
    @Builder.Default
    private String invitationStatus = "PENDING";

    /**
     * Expiration timestamp (30 seconds after creation).
     */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        // Default expiry: 30 seconds from creation
        if (expiresAt == null) {
            expiresAt = createdAt.plusSeconds(30);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
