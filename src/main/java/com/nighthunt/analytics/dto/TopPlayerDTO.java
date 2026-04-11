package com.nighthunt.analytics.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TopPlayerDTO {
    private long   userId;
    private String username;
    private int    elo;
    private String tier;
    private int    totalWins;
    private int    totalLosses;
    private int    totalDraws;
    private int    totalMatches;
    private int    totalKills;
    private int    totalDeaths;
    private double kda;
    private boolean isOnline;
    private boolean isBanned;
}
