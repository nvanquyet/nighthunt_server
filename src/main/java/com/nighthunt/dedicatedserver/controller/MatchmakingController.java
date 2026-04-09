package com.nighthunt.dedicatedserver.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.dedicatedserver.dto.ServerAllocateResponse;
import com.nighthunt.dedicatedserver.service.DedicatedServerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Client-facing matchmaking endpoint.
 * Client gọi đây để lấy IP:Port của DS + session token để connect.
 * Yêu cầu user JWT auth (Spring Security filter xử lý).
 */
@RestController
@RequestMapping("/matchmaking")
@RequiredArgsConstructor
public class MatchmakingController {

    private final DedicatedServerService dsService;

    /**
     * Client gọi khi muốn vào game.
     * Response: { ip, port, sessionToken, status }
     * Nếu status = "starting" → client poll /status/{serverId} cho đến khi "ready"
     */
    @PostMapping("/request")
    public ApiResponse<ServerAllocateResponse> requestServer(
            @RequestParam(defaultValue = "vn") String region,
            @RequestParam(required = false) String mapId) {

        ServerAllocateResponse allocation = dsService.allocateServer(region, mapId);
        return ApiResponse.ok(allocation);
    }

    /**
     * Client poll status khi server đang "starting"
     */
    @GetMapping("/status/{serverId}")
    public ApiResponse<String> getServerStatus(@PathVariable String serverId) {
        String status = dsService.getServerStatus(serverId);
        if (status == null) return ApiResponse.error("Server not found", "NOT_FOUND");
        return ApiResponse.ok(status);
    }
}
