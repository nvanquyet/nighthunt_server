package com.nighthunt.party.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Party entity - Pre-match squad system (PUBG-style).
 * Players can form a party before joining matchmaking or custom lobby.
 */
@Entity
@Table(name = "parties", 
    indexes = {
        @Index(name = "idx_parties_status", columnList = "partyStatus"),
        @Index(name = "idx_parties_host_user_id", columnList = "hostUserId")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Party {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Party host (creator).
     * Host can:
     * - Kick members
     * - Disband party
     * - Start matchmaking
     * - Join custom lobby (brings all members)
     */
    @Column(name = "host_user_id", nullable = false)
    private Long hostUserId;

    /**
     * Party status (lifecycle state):
     * - IDLE:      In main menu, no active context
     * - IN_QUEUE:  Searching for ranked match (partyMode=RANKED)
     * - IN_ROOM:   In custom lobby (partyMode=CUSTOM)
     * - IN_GAME:   Playing a match
     * - DISBANDED: Party deleted (soft delete marker)
     *
     * See also: {@link #partyMode} for mutual-exclusivity enforcement.
     */
    @Column(name = "party_status", nullable = false, length = 20)
    @Builder.Default
    private String partyStatus = "IDLE";

    /**
     * Current room ID if party is in custom lobby.
     * NULL if not in room.
     */
    @Column(name = "current_room_id")
    private Long currentRoomId;

    /**
     * Current matchmaking entry ID if party is in ranked queue.
     * Reserved for future FK relationship.
     */
    @Column(name = "current_matchmaking_id")
    private Long currentMatchmakingId;

    /**
     * Party mode — tracks the CONTEXT of the current activity.
     * Enforces mutual exclusivity: RANKED and CUSTOM cannot coexist.
     *
     * <ul>
     *   <li>{@code NONE}   — party is idle, no active context</li>
     *   <li>{@code RANKED} — party entered ranked matchmaking queue</li>
     *   <li>{@code CUSTOM} — party joined a custom lobby room</li>
     * </ul>
     *
     * <p>Transitions:
     * <pre>
     *   NONE  →(queueParty)→     RANKED
     *   NONE  →(joinRoom)→       CUSTOM
     *   RANKED →(cancelQueue)→   NONE
     *   RANKED →(matchReady)→    RANKED (stays, match is ranked)
     *   CUSTOM →(leaveRoom)→     NONE  (when last member leaves)
     * </pre>
     */
    @Column(name = "party_mode", nullable = false, length = 10)
    @Builder.Default
    private String partyMode = "NONE";

    /**
     * Maximum party size (configurable).
     * Default: 4 (for 4v4 mode)
     * Depends on selected game mode (2v2=2, 3v3=3, 4v4=4, 5v5=5)
     */
    @Column(name = "max_members", nullable = false)
    @Builder.Default
    private int maxMembers = 4;

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
