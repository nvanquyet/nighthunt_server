package com.nighthunt.match.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "matches", indexes = {
        @Index(name = "idx_match_id", columnList = "matchId", unique = true),
        @Index(name = "idx_room_id", columnList = "roomId"),
        @Index(name = "idx_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Match {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_id", nullable = false, unique = true, length = 50)
    private String matchId; // unique UUID identifier

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(nullable = false, length = 20)
    private String status; // LOBBY, IN_GAME, FINISHED

    /** -1 means DRAW. Null until match finished. */
    @Column(name = "winner_team_id")
    private Integer winnerTeamId;

    /** TEAM_ELIMINATED | TIMER_EXPIRED | DRAW — null until match finished. */
    @Column(name = "end_reason", length = 30)
    private String endReason;

    /** Denormalized game mode for reporting. */
    @Column(name = "game_mode", length = 20)
    private String gameMode;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

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

