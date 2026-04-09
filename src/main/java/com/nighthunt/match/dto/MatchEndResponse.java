package com.nighthunt.match.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Response from {@code POST /api/match/end} — echoes persisted results. */
@Data
@Builder
public class MatchEndResponse {
    private String matchId;
    private int    winnerTeamId;
    private String endReason;
    private List<PlayerResultRow> playerResults;

    @Data
    @Builder
    public static class PlayerResultRow {
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
