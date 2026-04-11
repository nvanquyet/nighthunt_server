package com.nighthunt.dashboard.controller;

import com.nighthunt.analytics.dto.*;
import com.nighthunt.analytics.service.AnalyticsService;
import com.nighthunt.common.ApiResponse;
import com.nighthunt.dashboard.dto.DashboardStatsDTO;
import com.nighthunt.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Dashboard Controller
 * Provides REST API endpoints for monitoring dashboard
 */
@Slf4j
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {
    
    private final DashboardService dashboardService;
    private final AnalyticsService analyticsService;
    
    /**
     * GET /dashboard/stats — current live snapshot
     */
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<DashboardStatsDTO>> getDashboardStats() {
        try {
            DashboardStatsDTO stats = dashboardService.getDashboardStats();
            return ResponseEntity.ok(ApiResponse.ok(stats));
        } catch (Exception e) {
            log.error("Error getting dashboard stats: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Failed to get dashboard statistics: " + e.getMessage()));
        }
    }

    /**
     * GET /dashboard/analytics/online-history?hours=24
     * Time-series: online users, queue depth, in-game rooms, DS servers.
     */
    @GetMapping("/analytics/online-history")
    public ResponseEntity<ApiResponse<TimeSeriesDTO>> getOnlineHistory(
            @RequestParam(defaultValue = "24") int hours) {
        hours = Math.min(Math.max(hours, 1), 168); // clamp 1h–7d
        return ResponseEntity.ok(ApiResponse.ok(analyticsService.getOnlineHistory(hours)));
    }

    /**
     * GET /dashboard/analytics/matchmaking
     * Live matchmaking queue breakdown + avg wait + throughput.
     */
    @GetMapping("/analytics/matchmaking")
    public ResponseEntity<ApiResponse<MatchmakingStatsDTO>> getMatchmakingStats() {
        return ResponseEntity.ok(ApiResponse.ok(analyticsService.getMatchmakingStats()));
    }

    /**
     * GET /dashboard/analytics/matches?hours=24
     * Matches per mode in the last N hours + user registration counts.
     */
    @GetMapping("/analytics/matches")
    public ResponseEntity<ApiResponse<MatchStatsDTO>> getMatchStats(
            @RequestParam(defaultValue = "24") int hours) {
        hours = Math.min(Math.max(hours, 1), 168);
        return ResponseEntity.ok(ApiResponse.ok(analyticsService.getMatchStats(hours)));
    }

    /**
     * GET /dashboard/analytics/elo-distribution
     * ELO histogram (Bronze → Master buckets).
     */
    @GetMapping("/analytics/elo-distribution")
    public ResponseEntity<ApiResponse<EloDistributionDTO>> getEloDistribution() {
        return ResponseEntity.ok(ApiResponse.ok(analyticsService.getEloDistribution()));
    }

    /**
     * GET /dashboard/analytics/top-players?limit=20
     * Top players by ELO with online status.
     */
    @GetMapping("/analytics/top-players")
    public ResponseEntity<ApiResponse<List<TopPlayerDTO>>> getTopPlayers(
            @RequestParam(defaultValue = "20") int limit) {
        limit = Math.min(Math.max(limit, 1), 100);
        return ResponseEntity.ok(ApiResponse.ok(analyticsService.getTopPlayers(limit)));
    }

    /**
     * GET /dashboard/analytics/player-statuses?limit=50
     * Live status of currently online players.
     */
    @GetMapping("/analytics/player-statuses")
    public ResponseEntity<ApiResponse<List<PlayerStatusDTO>>> getPlayerStatuses(
            @RequestParam(defaultValue = "50") int limit) {
        limit = Math.min(Math.max(limit, 1), 200);
        return ResponseEntity.ok(ApiResponse.ok(analyticsService.getPlayerStatuses(limit)));
    }
}

