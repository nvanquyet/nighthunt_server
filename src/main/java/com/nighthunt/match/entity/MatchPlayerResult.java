package com.nighthunt.match.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Per-player result row for one finished match.
 * Written by {@link com.nighthunt.match.service.MatchResultService} on
 * {@code POST /api/match/end}.
 */
@Entity
@Table(name = "match_player_results", indexes = {
        @Index(name = "idx_mpr_match_id", columnList = "matchId"),
        @Index(name = "idx_mpr_user_id",  columnList = "userId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MatchPlayerResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_id", nullable = false, length = 50)
    private String matchId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "team_id", nullable = false)
    private int teamId;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false)
    @Builder.Default
    private int kills = 0;

    @Column(nullable = false)
    @Builder.Default
    private int deaths = 0;

    @Column(nullable = false)
    @Builder.Default
    private int score = 0;

    @Column(name = "elo_before", nullable = false)
    @Builder.Default
    private int eloBefore = 1000;

    @Column(name = "elo_after", nullable = false)
    @Builder.Default
    private int eloAfter = 1000;

    @Column(name = "elo_change", nullable = false)
    @Builder.Default
    private int eloChange = 0;

    /** 1 = winner, 2 = loser, 0 = draw. */
    @Column(nullable = false)
    @Builder.Default
    private int placement = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
