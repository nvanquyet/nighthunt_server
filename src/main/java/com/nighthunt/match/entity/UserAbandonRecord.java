package com.nighthunt.match.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Persisted record of a mid-match AFK or intentional-abandon event.
 * Used for:
 * <ul>
 *   <li>ELO penalty history + audit trail</li>
 *   <li>Repeat-offender detection (N abandons in M days)</li>
 *   <li>Admin review</li>
 * </ul>
 *
 * @see com.nighthunt.match.service.AbandonPenaltyService
 */
@Entity
@Table(name = "user_abandon_records",
        indexes = {
                @Index(name = "idx_abandon_user_id",     columnList = "user_id"),
                @Index(name = "idx_abandon_match_id",    columnList = "match_id"),
                @Index(name = "idx_abandon_recorded_at", columnList = "recorded_at")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAbandonRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK → users.id — the player who abandoned. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** The matchId string (Room.matchId). */
    @Column(name = "match_id", nullable = false, length = 64)
    private String matchId;

    /** FK → rooms.id — the room where the match was played. */
    @Column(name = "room_id", nullable = false)
    private Long roomId;

    /**
     * Why the player was removed.
     * One of: AFK_TIMEOUT | INTENTIONAL_LEAVE | LOGOUT | SESSION_EXPIRED | FORCE_LOGOUT
     */
    @Column(name = "reason", nullable = false, length = 50)
    private String reason;

    /** ELO before the penalty was applied. */
    @Column(name = "elo_before", nullable = false)
    private int eloBefore;

    /**
     * ELO change from this event (negative = penalty, 0 = penalty not applied
     * e.g. because the match had not started yet or user is in a grace period).
     */
    @Column(name = "elo_change", nullable = false)
    private int eloChange;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private LocalDateTime recordedAt;

    @PrePersist
    protected void onCreate() {
        if (recordedAt == null) {
            recordedAt = LocalDateTime.now();
        }
    }
}
