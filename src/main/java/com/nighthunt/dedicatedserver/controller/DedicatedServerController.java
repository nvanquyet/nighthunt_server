package com.nighthunt.dedicatedserver.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.dedicatedserver.dto.DsHeartbeatRequest;
import com.nighthunt.dedicatedserver.dto.DsRegisterRequest;
import com.nighthunt.dedicatedserver.dto.ServerAllocateResponse;
import com.nighthunt.dedicatedserver.service.DedicatedServerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * DS-facing endpoints - không cần user JWT auth.
 * Bảo mật bằng: X-DS-Secret header (verified bằng BCrypt trong service).
 */
@RestController
@RequestMapping("/api/ds")
@RequiredArgsConstructor
@Slf4j
public class DedicatedServerController {

    private final DedicatedServerService dsService;

    // ── DS Registration ───────────────────────────────────────────────────────

    /** DS gọi sau khi FishNet server boot xong */
    @PostMapping("/register")
    public ApiResponse<String> register(
            @RequestBody DsRegisterRequest req,
            @RequestHeader(value = "X-DS-Secret", required = false) String headerSecret) {

        // Header secret phải match body secret (double check)
        if (headerSecret != null && !headerSecret.equals(req.getServerSecret())) {
            return ApiResponse.error("Secret mismatch", "INVALID_SECRET");
        }

        boolean ok = dsService.registerServer(req);
        if (!ok) return ApiResponse.error("Invalid server credentials", "AUTH_FAILED");

        return ApiResponse.ok("registered");
    }

    /** DS gửi heartbeat mỗi 30s */
    @PostMapping("/heartbeat")
    public ApiResponse<String> heartbeat(
            @RequestBody DsHeartbeatRequest req,
            @RequestHeader(value = "X-DS-Secret", required = false) String headerSecret) {

        if (headerSecret != null && !headerSecret.equals(req.getServerSecret())) {
            return ApiResponse.error("Secret mismatch", "INVALID_SECRET");
        }

        boolean ok = dsService.heartbeat(req);
        if (!ok) return ApiResponse.error("Invalid credentials", "AUTH_FAILED");

        return ApiResponse.ok("ok");
    }

    /** DS validate session token khi client connect - optional */
    @PostMapping("/validate-token")
    public ApiResponse<Boolean> validateToken(@RequestBody Map<String, String> body) {
        String token    = body.get("token");
        String serverId = body.get("serverId");
        boolean valid   = dsService.validateSessionToken(token, serverId);
        return ApiResponse.ok(valid);
    }
}
