package com.nighthunt.relay.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.relay.dto.RelayJoinRequest;
import com.nighthunt.relay.dto.RelaySessionResponse;
import com.nighthunt.relay.model.RelaySession;
import com.nighthunt.relay.service.RelaySessionManager;
import com.nighthunt.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * BE-25 — REST endpoints for Mini-Relay session management.
 *
 * <p>Clients use these to:
 * <ol>
 *   <li>Confirm they have joined the relay ({@code POST /api/relay/join})</li>
 *   <li>Query relay connection info for a room ({@code GET /api/relay/room/{roomId}})</li>
 *   <li>Query relay connection info by token ({@code GET /api/relay/session/{token}})</li>
 * </ol>
 *
 * <p>Session <em>creation</em> is triggered internally by
 * {@link com.nighthunt.room.service.RoomService#startGame} when the room's
 * game mode is {@code Custom_Relay}.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/relay")
@RequiredArgsConstructor
public class RelayController {

    private final RelaySessionManager relaySessionManager;

    // ── GET /api/relay/room/{roomId} ─────────────────────────────────────────

    /**
     * Returns the relay session for a given room. Clients call this after
     * receiving the {@code game_starting} WS event to get host/port.
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/room/{roomId}")
    public ResponseEntity<ApiResponse<RelaySessionResponse>> getByRoom(@PathVariable Long roomId) {
        RelaySession s = relaySessionManager.getByRoomId(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "No active relay session for room " + roomId));

        return ResponseEntity.ok(ApiResponse.ok(toResponse(s)));
    }

    // ── GET /api/relay/session/{token} ───────────────────────────────────────

    /**
     * Returns relay session info by the opaque session token.
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/session/{token}")
    public ResponseEntity<ApiResponse<RelaySessionResponse>> getByToken(@PathVariable String token) {
        RelaySession s = relaySessionManager.getByToken(token)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "Relay session not found: " + token));

        return ResponseEntity.ok(ApiResponse.ok(toResponse(s)));
    }

    // ── POST /api/relay/join ─────────────────────────────────────────────────

    /**
     * Client calls this to register itself as connected to the relay.
     * Useful for the host to track when all players have joined before
     * triggering {@code AllPlayersReady}.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/join")
    public ResponseEntity<ApiResponse<RelaySessionResponse>> joinSession(
            @RequestBody RelayJoinRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();

        boolean ok = relaySessionManager.addPlayer(req.getSessionToken(), userId);
        if (!ok) throw new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                "Relay session expired or not found: " + req.getSessionToken());

        RelaySession s = relaySessionManager.getByToken(req.getSessionToken()).orElseThrow();
        log.info("[Relay] User {} joined relay session {} (room={})", userId, req.getSessionToken(), s.getRoomId());
        return ResponseEntity.ok(ApiResponse.ok(toResponse(s)));
    }

    // ── POST /api/relay/leave ────────────────────────────────────────────────

    /**
     * Client calls this when it disconnects from the relay (e.g. on game end or crash).
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/leave")
    public ResponseEntity<ApiResponse<Void>> leaveSession(
            @RequestBody RelayJoinRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        relaySessionManager.removePlayer(req.getSessionToken(), userId);
        log.info("[Relay] User {} left relay session {}", userId, req.getSessionToken());
        return ResponseEntity.ok(ApiResponse.ok());
    }

    // ── GET /api/relay/active (admin/debug) ──────────────────────────────────

    /**
     * Returns all currently active relay sessions. Intended for dashboard/admin use.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<RelaySessionResponse>>> listActive() {
        List<RelaySessionResponse> list = relaySessionManager.getAllActiveSessions()
                .stream().map(this::toResponse).toList();
        return ResponseEntity.ok(ApiResponse.ok(list));
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private RelaySessionResponse toResponse(RelaySession s) {
        return RelaySessionResponse.builder()
                .sessionToken(s.getSessionToken())
                .matchId(s.getMatchId())
                .relayHost(s.getRelayHost())
                .relayPort(s.getRelayPort())
                .connectedPlayers(s.getPlayerCount())
                .started(s.isStarted())
                .finished(s.isFinished())
                .build();
    }
}
