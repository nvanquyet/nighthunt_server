package com.nighthunt.analytics.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "server_metrics_snapshots", indexes = {
        @Index(name = "idx_snapshot_at", columnList = "snapshotAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServerMetricsSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_at", nullable = false)
    private LocalDateTime snapshotAt;

    /** Live authenticated users (Redis session keys count). */
    @Column(name = "online_users", nullable = false)
    @Builder.Default
    private int onlineUsers = 0;

    /** Matchmaking queue depth (SEARCHING entries). */
    @Column(name = "queue_depth", nullable = false)
    @Builder.Default
    private int queueDepth = 0;

    @Column(name = "active_rooms", nullable = false)
    @Builder.Default
    private int activeRooms = 0;

    @Column(name = "in_game_rooms", nullable = false)
    @Builder.Default
    private int inGameRooms = 0;

    @Column(name = "waiting_rooms", nullable = false)
    @Builder.Default
    private int waitingRooms = 0;

    @Column(name = "active_ds", nullable = false)
    @Builder.Default
    private int activeDs = 0;

    @Column(name = "in_game_ds", nullable = false)
    @Builder.Default
    private int inGameDs = 0;

    @Column(name = "players_in_ds", nullable = false)
    @Builder.Default
    private int playersInDs = 0;

    /** Matches finished in the last 60 minutes (rolling window, not cumulative). */
    @Column(name = "matches_last_hour", nullable = false)
    @Builder.Default
    private int matchesLastHour = 0;
}
