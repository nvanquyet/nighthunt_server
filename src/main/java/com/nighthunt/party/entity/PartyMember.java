package com.nighthunt.party.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Party Member entity - Tracks party membership.
 * Each row represents one user in a party.
 */
@Entity
@Table(name = "party_members", 
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_party_user", columnNames = {"party_id", "user_id"})
    },
    indexes = {
        @Index(name = "idx_party_members_user_id", columnList = "user_id"),
        @Index(name = "idx_party_members_party_id", columnList = "party_id")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartyMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Party ID.
     */
    @Column(name = "party_id", nullable = false)
    private Long partyId;

    /**
     * User ID of the party member.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * Join order in the party:
     * - 0 = Host (first member)
     * - 1, 2, 3, ... = Guests (subsequent members)
     * 
     * Used for UI display order.
     */
    @Column(name = "join_order", nullable = false)
    private int joinOrder;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private LocalDateTime joinedAt;

    @PrePersist
    protected void onCreate() {
        joinedAt = LocalDateTime.now();
    }
}
