package com.nighthunt.match.dto;

import lombok.Data;

import java.util.List;

/**
 * Request body for {@code POST /api/match/end}.
 *
 * <p>Sent by the Dedicated Server (for Ranked) or the relay host (for Custom)
 * after a match concludes. Contains per-player stats that are persisted and
 * used for ELO calculation.</p>
 */
@Data
public class MatchEndRequest {

    /** The match UUID that was issued at room-start time. */
    private String matchId;

    /**
     * ID of the winning team (0-indexed).
     * {@code -1} indicates a DRAW.
     */
    private int winnerTeamId;

    /**
     * Why the match ended.
     * One of: {@code TEAM_ELIMINATED}, {@code TIMER_EXPIRED}, {@code DRAW}.
     */
    private String endReason;

    /** Per-player result rows. */
    private List<PlayerResultEntry> playerResults;

    @Data
    public static class PlayerResultEntry {
        /** Backend user ID (long as string for JSON safety). */
        private Long userId;
        private int  teamId;
        private String displayName;
        private int  kills;
        private int  deaths;
        private int  score;
    }
}
