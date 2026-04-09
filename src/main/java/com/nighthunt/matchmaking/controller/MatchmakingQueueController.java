package com.nighthunt.matchmaking.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.matchmaking.service.MatchmakingQueueService;
import com.nighthunt.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * BE-28 — Ranked matchmaking queue endpoints.
 *
 * <ul>
 *   <li>{@code POST /api/matchmaking/queue}     — enter the ranked queue</li>
 *   <li>{@code DELETE /api/matchmaking/queue}   — leave the ranked queue</li>
 * </ul>
 *
 * The existing {@code POST /api/matchmaking/request} endpoint in
 * {@code MatchmakingController} handles DS allocation and remains intact.
 */
@RestController
@RequestMapping("/matchmaking/queue")
@RequiredArgsConstructor
public class MatchmakingQueueController {

    private final MatchmakingQueueService queueService;

    /**
     * Enter the ranked matchmaking queue.
     *
     * Body: {@code { "gameMode": "2v2" }}
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> enqueue(@RequestBody Map<String, String> body) {
        Long userId   = SecurityUtils.getCurrentUserId();
        String mode   = body.getOrDefault("gameMode", "2v2");
        String mapId  = body.get("mapId"); // nullable — null = any map
        queueService.enqueue(userId, mode, mapId);
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

    /**
     * Accept the pending match offer.
     * Body: {@code { "lobbyToken": "..." }}
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/accept")
    public ResponseEntity<ApiResponse<Void>> accept(@RequestBody Map<String, String> body) {
        Long   userId     = SecurityUtils.getCurrentUserId();
        String lobbyToken = body.get("lobbyToken");
        queueService.accept(userId, lobbyToken);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    /**
     * Decline the pending match offer. Re-queues the other players who already accepted.
     * Body: {@code { "lobbyToken": "..." }}
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/decline")
    public ResponseEntity<ApiResponse<Void>> decline(@RequestBody Map<String, String> body) {
        Long   userId     = SecurityUtils.getCurrentUserId();
        String lobbyToken = body.get("lobbyToken");
        queueService.decline(userId, lobbyToken);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
