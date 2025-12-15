package com.nighthunt.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Dashboard Statistics DTO
 * Contains all metrics for the monitoring dashboard
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsDTO {
    // Overall statistics
    private Long totalActiveRooms;
    private Long totalOnlineUsers;
    private Long totalUsersInRooms;
    private Long totalWaitingRooms;
    private Long totalInGameRooms;
    
    // Room details
    private List<RoomDetailDTO> activeRooms;
    
    // User statistics
    private Long totalRegisteredUsers; // Optional - if you have user count
}

