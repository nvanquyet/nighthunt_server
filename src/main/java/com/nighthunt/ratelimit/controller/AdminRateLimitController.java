package com.nighthunt.ratelimit.controller;

import com.nighthunt.ratelimit.config.RateLimitToggle;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin endpoint for runtime rate-limit toggle.
 *
 * GET  /api/admin/rate-limit/status  — current enabled state
 * POST /api/admin/rate-limit/toggle  — flip or set state
 *   body (optional): {"enabled": true|false}
 *
 * All endpoints require X-Admin-Secret header (enforced by SecurityConfig).
 */
@RestController
@RequestMapping("/admin/rate-limit")
@RequiredArgsConstructor
public class AdminRateLimitController {

    private final RateLimitToggle rateLimitToggle;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(
            @RequestHeader("X-Admin-Secret") String secret) {
        return ResponseEntity.ok(statusPayload());
    }

    @PostMapping("/toggle")
    public ResponseEntity<Map<String, Object>> toggle(
            @RequestHeader("X-Admin-Secret") String secret,
            @RequestBody(required = false) Map<String, Object> body) {
        boolean newState;
        if (body != null && body.containsKey("enabled")) {
            newState = Boolean.TRUE.equals(body.get("enabled"));
        } else {
            newState = !rateLimitToggle.isEnabled();
        }
        rateLimitToggle.setEnabled(newState);
        return ResponseEntity.ok(statusPayload());
    }

    private Map<String, Object> statusPayload() {
        boolean enabled = rateLimitToggle.isEnabled();
        return Map.of(
            "success", true,
            "data", Map.of(
                "enabled", enabled,
                "message", enabled
                    ? "Rate limiting ENABLED — production mode"
                    : "Rate limiting DISABLED — load-test / bypass mode"
            )
        );
    }
}
