package com.nighthunt.match.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.room.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Headless server controller - DISABLED
 * Headless server functionality has been disabled.
 * This controller remains for backward compatibility but returns empty/stub responses.
 */
@RestController
@RequestMapping("/api/servers")
@RequiredArgsConstructor
public class HeadlessServerController {
    private final RoomService roomService;

    @GetMapping("/{serverId}/rooms/count")
    public ApiResponse<Integer> getServerRoomsCount(@PathVariable String serverId) {
        return ApiResponse.ok(0);
    }

    @GetMapping("/active-rooms/count")
    public ApiResponse<Integer> getTotalActiveRoomsCount() {
        return ApiResponse.ok(0);
    }

    @GetMapping("/waiting-rooms/count")
    public ApiResponse<Map<String, Object>> getWaitingRoomsCount() {
        int waitingCount = roomService.getWaitingRoomsCount();
        Map<String, Object> response = new HashMap<>();
        response.put("count", waitingCount);
        return ApiResponse.ok(response);
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getServerStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalServers", 0);
        stats.put("activeServers", 0);
        stats.put("totalMatches", 0);
        stats.put("availableCapacity", 0);
        stats.put("waitingRooms", roomService.getWaitingRoomsCount());
        return ApiResponse.ok(stats);
    }
}
