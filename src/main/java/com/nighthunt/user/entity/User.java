package com.nighthunt.user.entity;

import com.nighthunt.rbac.enums.UserRole;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_username", columnList = "username", unique = true),
        @Index(name = "idx_email", columnList = "email", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    // ── RBAC (Role-Based Access Control) ──────────────────────────────────────

    /**
     * User role for admin dashboard access control.
     * USER (default), SUPPORT, MODERATOR, ADMIN
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    @Builder.Default
    private UserRole role = UserRole.USER;

    /**
     * When the current role was assigned.
     */
    @Column(name = "role_assigned_at")
    private LocalDateTime roleAssignedAt;

    /**
     * User ID of admin who assigned the role.
     */
    @Column(name = "role_assigned_by")
    private Long roleAssignedBy;

    /**
     * Quick flag for dashboard filtering (denormalized from bans table).
     */
    @Column(name = "is_banned", nullable = false)
    @Builder.Default
    private Boolean isBanned = false;

    /**
     * Reason for ban (if is_banned=true).
     */
    @Column(name = "ban_reason", length = 500)
    private String banReason;

    // ── ELO / ranked stats ────────────────────────────────────────────────────

    /** Current ELO rating. Default 1000 (matches V8 migration default). */
    @Column(nullable = false)
    @Builder.Default
    private int elo = 1000;

    /** Human-readable tier derived from ELO. */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String tier = "BRONZE";

    @Column(name = "total_wins", nullable = false)
    @Builder.Default
    private int totalWins = 0;

    @Column(name = "total_losses", nullable = false)
    @Builder.Default
    private int totalLosses = 0;

    @Column(name = "total_draws", nullable = false)
    @Builder.Default
    private int totalDraws = 0;

    // ── Character selection ──────────────────────────────────────────────────

    /**
     * String ID of the character model the player has selected (e.g. "character_01").
     * Matches CharacterDefinition.CharacterId used by the Unity client.
     * NULL means the player has never picked a character → client defaults to index 0.
     */
    @Column(name = "selected_character_id", length = 64)
    private String selectedCharacterId;

    // ── Social System (Friend, Party, Status) ─────────────────────────────────

    /**
     * Online status of the user: ONLINE, OFFLINE, AWAY, IN_GAME.
     * Used by friend system to show friend status.
     */
    @Column(name = "online_status", nullable = false, length = 20)
    @Builder.Default
    private String onlineStatus = "OFFLINE";

    /**
     * Current party ID if user is in a party, NULL otherwise.
     * Foreign key to parties table.
     */
    @Column(name = "current_party_id")
    private Long currentPartyId;

    /**
     * Current room ID if user is in a custom lobby, NULL otherwise.
     * Foreign key to rooms table.
     */
    @Column(name = "current_room_id")
    private Long currentRoomId;

    // ── Coins / Economy ───────────────────────────────────────────────────────

    /** In-game currency earned from match rewards. */
    @Column(nullable = false)
    @Builder.Default
    private long coins = 0;

    // ── Platform / Device ─────────────────────────────────────────────────────

    /**
     * Device platform reported by the client at queue time.
     * Values: "MOBILE" | "PC" | null (unknown / not yet reported).
     */
    @Column(name = "platform", length = 20)
    private String platform;

    /**
     * Last activity timestamp when user went offline.
     * Used to display "Last seen X minutes ago" in friend list.
     */
    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    // ── Timestamps ────────────────────────────────────────────────────────────

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

