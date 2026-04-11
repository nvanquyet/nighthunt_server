package com.nighthunt.analytics.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class MatchmakingStatsDTO {
    private int totalInQueue;
    private Map<String, Integer> queueByMode;
    private int avgWaitSeconds;
    private int matchesLastHour;
}
