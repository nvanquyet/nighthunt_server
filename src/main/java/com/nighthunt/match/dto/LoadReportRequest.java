package com.nighthunt.match.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoadReportRequest {
    private Integer currentMatches;
    private Double cpuUsage;
    private Double memoryUsage;
}

