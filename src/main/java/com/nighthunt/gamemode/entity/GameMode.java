package com.nighthunt.gamemode.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Game Mode entity - Configurable game modes (2v2, 3v3, 4v4, 5v5).
 * Allows server to dynamically enable/disable modes without code changes.
 */
@Entity
@Table(name = "game_modes", 
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_mode_key", columnNames = {"mode_key"})
    },
    indexes = {
        @Index(name = "idx_game_modes_status", columnList = "mode_status, is_active"),
        @Index(name = "idx_game_modes_order", columnList = "display_order")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameMode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Unique mode identifier: "2v2", "3v3", "4v4", "5v5"
     */
    @Column(name = "mode_key", nullable = false, unique = true, length = 20)
    private String modeKey;

    /**
     * Human-readable display name: "2 vs 2", "3 vs 3"
     */
    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    /**
     * Number of players per team: 2, 3, 4, 5
     */
    @Column(name = "players_per_team", nullable = false)
    private int playersPerTeam;

    /**
     * Total players in match: 4, 6, 8, 10
     * (playersPerTeam * 2)
     */
    @Column(name = "total_players", nullable = false)
    private int totalPlayers;

    /**
     * Mode status:
     * - AVAILABLE: Players can select and play this mode
     * - LOCKED: Mode appears but cannot be selected (show lock icon)
     * - COMING_SOON: Mode appears with "Coming Soon" badge
     * - DISABLED: Mode hidden from UI completely
     */
    @Column(name = "mode_status", nullable = false, length = 20)
    @Builder.Default
    private String modeStatus = "AVAILABLE";

    @Column(name = "description", length = 120)
    private String description;

    /** If true, backend may fill empty team slots with solo players. */
    @Column(name = "allow_fill", nullable = false)
    @Builder.Default
    private boolean allowFill = false;

    /** If false, ranked queue for this mode is closed (no new entries accepted). */
    @Column(name = "matchmaking_enabled", nullable = false)
    @Builder.Default
    private boolean matchmakingEnabled = true;

    @Column(name = "min_elo", nullable = false)
    @Builder.Default
    private int minElo = 0;

    @Column(name = "max_elo", nullable = false)
    @Builder.Default
    private int maxElo = 9999;

    /**
     * Display order in UI (ascending).
     * Lower number = appears first.
     */
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    /**
     * Soft delete flag.
     * false = deleted, true = active
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    /**
     * Dev/test mode flag.
     * true  = (1) excluded from client-facing GET /api/game-modes;
     *         (2) matchmaking tick allows a single-player lobby so the
     *             DS container boot + client join flow can be tested
     *             without filling a full lobby.
     * false = normal production mode.
     */
    @Column(name = "is_dev_mode", nullable = false)
    @Builder.Default
    private boolean isDevMode = false;

    /**
     * Platform restriction for this mode's ranked matchmaking queue.
     * <ul>
     *   <li>ALL    — any platform may queue (default)</li>
     *   <li>MOBILE — only MOBILE clients may queue</li>
     *   <li>PC     — only PC clients may queue</li>
     * </ul>
     * When set to MOBILE or PC, only same-platform players will be matched together.
     */
    @Column(name = "platform_filter", nullable = false, length = 20)
    @Builder.Default
    private String platformFilter = "ALL";

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
