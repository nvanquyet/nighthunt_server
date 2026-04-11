package com.nighthunt.analytics.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class MatchStatsDTO {
    private int totalMatchesInPeriod;
    private Map<String, Integer> matchesByMode;
    private long totalRegisteredUsers;
    private long newUsersToday;
}
