package com.nighthunt.analytics.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class EloDistributionDTO {
    private Map<String, Integer> buckets;
    private int totalPlayers;
    private int averageElo;
}
