package com.nighthunt.matchmaking.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A player's pending entry in the ranked matchmaking queue.
 * Written by {@link com.nighthunt.matchmaking.service.MatchmakingQueueService}
 * and consumed by the scheduled matcher.
 */
@Entity
@Table(name = "matchmaking_queue", indexes = {
        @Index(name = "idx_mmq_user_id",   columnList = "userId",   unique = true),
        @Index(name = "idx_mmq_game_mode", columnList = "gameMode"),
        @Index(name = "idx_mmq_status",    columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MatchmakingEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false)
    @Builder.Default
    private int elo = 1000;

    @Column(name = "game_mode", nullable = false, length = 20)
    private String gameMode;  // 2v2, 3v3, 5v5

    @Column(name = "queued_at", nullable = false)
    private LocalDateTime queuedAt;

    /** Current ELO range floor (expands every expansion tick). */
    @Column(name = "search_min_elo", nullable = false)
    @Builder.Default
    private int searchMinElo = 0;

    /** Current ELO range ceiling. */
    @Column(name = "search_max_elo", nullable = false)
    @Builder.Default
    private int searchMaxElo = 9999;

    /** MapEntry.mapId the player requested, null = any map. */
    @Column(name = "map_id", length = 50)
    private String mapId;

    /** SEARCHING | MATCHED | CANCELLED */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "SEARCHING";

    /** Shared token for all players in the same matched group. Null while SEARCHING. */
    @Column(name = "lobby_token", length = 64)
    private String lobbyToken;

    /** Per-player accept state once matched: PENDING | ACCEPTED | DECLINED */
    @Column(name = "accept_status", length = 20)
    @Builder.Default
    private String acceptStatus = "PENDING";

    @PrePersist
    protected void onCreate() {
        if (queuedAt == null) queuedAt = LocalDateTime.now();
    }
}
