package com.nighthunt.matchmaking.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.matchmaking.service.MatchmakingQueueService;
import com.nighthunt.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Ranked matchmaking queue endpoints.
 *
 * <ul>
 *   <li>{@code POST   /api/matchmaking/queue} — enter the ranked queue</li>
 *   <li>{@code DELETE /api/matchmaking/queue} — leave the ranked queue</li>
 * </ul>
 *
 * No accept/decline endpoints — when a full group is formed the DS boots
 * immediately and {@code match_ready} is sent directly to all players.
 */
@RestController
@RequestMapping("/matchmaking/queue")
@RequiredArgsConstructor
public class MatchmakingQueueController {

    private final MatchmakingQueueService queueService;

    /**
     * Enter the ranked matchmaking queue.
     *
     * Body: {@code { "gameMode": "2v2", "mapId": "map_01", "platform": "MOBILE" }}
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> enqueue(@RequestBody Map<String, String> body) {
        Long userId   = SecurityUtils.getCurrentUserId();
        String mode   = body.getOrDefault("gameMode", "2v2");
        String mapId  = body.get("mapId");      // nullable — null = any map
        String platform = body.get("platform"); // nullable — MOBILE | PC
        queueService.enqueue(userId, mode, mapId, platform);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    /**
     * Leave the ranked matchmaking queue.
     */
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> dequeue() {
        Long userId = SecurityUtils.getCurrentUserId();
        queueService.dequeue(userId);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
