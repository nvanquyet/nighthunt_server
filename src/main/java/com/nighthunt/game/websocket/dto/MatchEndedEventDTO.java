package com.nighthunt.game.websocket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * BE-31 — WebSocket event DTO: {@code match_ended}.
 *
 * <p>Sent to every participant after {@code POST /api/match/end} processes
 * the results. The Unity client's {@code ResultsView} listens for this event
 * to show the scoreboard and ELO changes.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchEndedEventDTO {

    private final String event = "match_ended";

    private String matchId;

    /** -1 = DRAW. */
    private int winnerTeamId;

    /** TEAM_ELIMINATED | TIMER_EXPIRED | DRAW */
    private String endReason;

    private List<PlayerResult> playerResults;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlayerResult {
        private Long   userId;
        private String displayName;
        private int    teamId;
        private int    kills;
        private int    deaths;
        private int    score;
        private int    eloBefore;
        private int    eloAfter;
        private int    eloChange;
        private String tier;
    }
}
