package com.nighthunt.game.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nighthunt.friend.service.PlayerStatusService;
import com.nighthunt.matchmaking.service.MatchmakingQueueService;
import com.nighthunt.game.websocket.dto.*;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.room.dto.RoomResponse;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import com.nighthunt.room.service.RoomResponseAssembler;
import com.nighthunt.security.port.TokenProvider;
import com.nighthunt.session.port.SessionStore;
import com.nighthunt.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified WebSocket handler for all game events.
 * Implements {@link ConnectionManager} so business services only depend on the port.
 *
 * Responsibilities (simplified from original 621-line God class):
 * - Connection lifecycle (open, close, error)
 * - Authentication via JWT token in query param + Redis session validation
 * - Single-device policy (close old session when new one arrives)
 * - Routing messages to users / rooms
 *
 * All room-level broadcasts are called from the service layer
 * through the {@link ConnectionManager} interface.
 */
@Slf4j
@Lazy
@Component
public class GameWebSocketHandler extends TextWebSocketHandler implements ConnectionManager {

    private final RoomPlayerRepository roomPlayerRepository;
    private final RoomRepository roomRepository;
    private final RoomResponseAssembler roomResponseAssembler;
    private final ObjectMapper objectMapper;
    private final TokenProvider tokenProvider;
    private final SessionStore sessionStore;
    private final UserRepository userRepository;
    private final PlayerStatusService playerStatusService;
    private final MatchmakingQueueService matchmakingQueueService;
    private final TransactionTemplate transactionTemplate;

    // userId -> session
    private final Map<Long, WebSocketSession> userSessions = new ConcurrentHashMap<>();
    // userId -> roomId
    private final Map<Long, Long> userRooms = new ConcurrentHashMap<>();
    // session -> metadata
    private final Map<WebSocketSession, SessionMetadata> sessionMetadata = new ConcurrentHashMap<>();
    // userId -> last activity timestamp (for heartbeat / stale-connection eviction)
    private final Map<Long, Instant> lastActivityAt = new ConcurrentHashMap<>();

    /** Sessions idle longer than this (seconds) are considered crashed/dead. */
    private static final long STALE_TIMEOUT_SECONDS = 30;

    public GameWebSocketHandler(
            RoomPlayerRepository roomPlayerRepository,
            RoomRepository roomRepository,
            RoomResponseAssembler roomResponseAssembler,
            ObjectMapper objectMapper,
            TokenProvider tokenProvider,
            SessionStore sessionStore,
            UserRepository userRepository,
            PlayerStatusService playerStatusService,
            MatchmakingQueueService matchmakingQueueService,
            TransactionTemplate transactionTemplate) {
        this.roomPlayerRepository = roomPlayerRepository;
        this.roomRepository = roomRepository;
        this.roomResponseAssembler = roomResponseAssembler;
        this.objectMapper = objectMapper;
        this.tokenProvider = tokenProvider;
        this.sessionStore = sessionStore;
        this.userRepository = userRepository;
        this.playerStatusService = playerStatusService;
        this.matchmakingQueueService = matchmakingQueueService;
        this.transactionTemplate = transactionTemplate;
    }

    // ==================== WebSocket Lifecycle ====================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = extractToken(session.getUri().toString());
        if (token == null) {
            log.warn("WebSocket connection rejected: missing token");
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        Long userId = authenticateUser(token);
        if (userId == null) {
            log.warn("WebSocket authentication failed for session: {}", session.getId());
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        // SEC-1: Validate Redis session is still alive and not force-logged-out
        String redisSession = sessionStore.getSessionId(String.valueOf(userId));
        if (redisSession == null) {
            log.warn("WebSocket rejected: no active session in Redis for userId={}", userId);
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        if (sessionStore.isForceLogout(String.valueOf(userId))) {
            log.warn("WebSocket rejected: force logout flag set for userId={}", userId);
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        // Single-device policy: close existing session
        WebSocketSession existing = userSessions.get(userId);
        if (existing != null && existing.isOpen()) {
            log.info("Closing existing WebSocket for user {} (new connection)", userId);
            closeQuietly(existing);
        }

        userSessions.put(userId, session);
        sessionMetadata.put(session, new SessionMetadata(userId, token));
        lastActivityAt.put(userId, Instant.now());

        // A reconnect after focus-loss/app-resume must mark the user online again.
        // Otherwise status can remain OFFLINE even though WS is already active.
        try {
            playerStatusService.setOnline(userId);
        } catch (Exception e) {
            log.error("Error setting player online status for userId={}: {}", userId, e.getMessage());
        }

        // Restore room mapping if user is in a room
        Long roomId = findCurrentRoomId(userId);
        if (roomId != null) {
            userRooms.put(userId, roomId);
        }

        // Send connection confirmation
        sendToUser(userId, "connected", Map.of(
                "userId", userId,
                "roomId", roomId != null ? roomId : 0,
                "message", "Game WebSocket connected"
        ));

        // Push latest room state for reconnection recovery
        if (roomId != null) {
            RoomResponse roomResponse = roomResponseAssembler.toResponseById(roomId);
            if (roomResponse != null) {
                sendToUser(userId, "room_updated", roomResponse);
            }
        }

        log.info("WebSocket connected: userId={}, roomId={}", userId, roomId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Client can send a {"type":"ping"} message to keep the connection alive.
        SessionMetadata meta = sessionMetadata.get(session);
        if (meta != null) {
            lastActivityAt.put(meta.getUserId(), Instant.now());
        }
        String payload = message.getPayload();
        if (payload != null && payload.contains("\"ping\"")) {
            // Reply with a pong so the client knows the server is alive
            try {
                session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
            } catch (Exception ignored) {}
        }
        log.debug("Received message from {}: {}", session.getId(), message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SessionMetadata metadata = sessionMetadata.remove(session);
        if (metadata != null) {
            Long userId = metadata.getUserId();
            boolean wasActive = userSessions.remove(userId, session);
            if (wasActive) {
                userRooms.remove(userId);
                lastActivityAt.remove(userId);
                try {
                    playerStatusService.setOffline(userId);
                } catch (Exception e) {
                    log.error("Error setting player offline status for userId={}: {}", userId, e.getMessage());
                }
                // Remove from matchmaking queue on disconnect
                try {
                    matchmakingQueueService.dequeue(userId);
                } catch (Exception e) {
                    log.debug("Matchmaking dequeue on disconnect for userId={}: {}", userId, e.getMessage());
                }
                // Remove player from WAITING rooms on disconnect to free the slot.
                // IN_GAME rooms are left intact so the player can reconnect mid-match.
                cleanupWaitingRoomPlayer(userId);
            }
            log.info("WebSocket disconnected: userId={}, status={}, wasActive={}", userId, status, wasActive);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket transport error for {}: {}", session.getId(), exception.getMessage());
        SessionMetadata metadata = sessionMetadata.remove(session);
        if (metadata != null) {
            Long userId = metadata.getUserId();
            boolean wasActive = userSessions.remove(userId, session);
            if (wasActive) {
                userRooms.remove(userId);
                lastActivityAt.remove(userId);
                try {
                    playerStatusService.setOffline(userId);
                } catch (Exception e) {
                    log.error("Error setting player offline status for userId={}: {}", userId, e.getMessage());
                }
                try {
                    matchmakingQueueService.dequeue(userId);
                } catch (Exception e) {
                    log.debug("Matchmaking dequeue on transport error for userId={}: {}", userId, e.getMessage());
                }
            }
        }
    }

    /**
     * Stale connection eviction.
     * Runs every 15 seconds. Any WebSocket session that has not sent a ping (or any message)
     * in the last {@value #STALE_TIMEOUT_SECONDS} seconds is treated as a crashed/dead client:
     * the connection is closed, the user is marked OFFLINE, and friends are notified.
     *
     * Unity client should send a {"type":"ping"} frame every 10–15 seconds.
     */
    @Scheduled(fixedDelay = 15_000)
    public void evictStaleConnections() {
        Instant cutoff = Instant.now().minusSeconds(STALE_TIMEOUT_SECONDS);
        List<Long> stale = new ArrayList<>();
        lastActivityAt.forEach((userId, lastSeen) -> {
            if (lastSeen.isBefore(cutoff)) {
                stale.add(userId);
            }
        });
        for (Long userId : stale) {
            WebSocketSession session = userSessions.get(userId);
            log.warn("Evicting stale WS connection for userId={} (no activity for {}s)",
                    userId, STALE_TIMEOUT_SECONDS);
            if (session != null && session.isOpen()) {
                closeQuietly(session);   // triggers afterConnectionClosed → setOffline
            } else {
                // Session already gone from map but lastActivityAt wasn't cleaned up
                lastActivityAt.remove(userId);
                userSessions.remove(userId);
                userRooms.remove(userId);
                try {
                    playerStatusService.setOffline(userId);
                } catch (Exception e) {
                    log.error("Error marking stale userId={} offline: {}", userId, e.getMessage());
                }
            }
        }
        if (!stale.isEmpty()) {
            log.info("Stale WS eviction: {} session(s) cleaned up", stale.size());
        }
    }

    // ==================== ConnectionManager Implementation ====================

    @Override
    public void sendToUser(Long userId, String eventType, Object data) {
        WebSocketSession session = userSessions.get(userId);
        if (session == null || !session.isOpen()) return;

        // Synchronize per session to prevent TEXT_PARTIAL_WRITING when two threads
        // (e.g. HTTP handler + scheduler) try to write to the same WS connection.
        synchronized (session) {
            if (!session.isOpen()) return;
            try {
                String json = createWebSocketMessage(eventType, data);
                if (json != null) {
                    session.sendMessage(new TextMessage(json));
                }
            } catch (IOException e) {
                log.error("Failed to send {} to user {}: {}", eventType, userId, e.getMessage());
            }
        }
    }

    @Override
    public void broadcastToRoom(Long roomId, String eventType, Object data) {
        userRooms.forEach((userId, mappedRoomId) -> {
            if (roomId.equals(mappedRoomId)) {
                sendToUser(userId, eventType, data);
            }
        });
    }

    @Override
    public void updateUserRoom(Long userId, Long roomId) {
        if (roomId != null && roomId > 0) {
            userRooms.put(userId, roomId);
        } else {
            userRooms.remove(userId);
        }
    }

    @Override
    public int getActiveConnectionCount() {
        return userSessions.size();
    }

    @Override
    public boolean isUserConnected(Long userId) {
        WebSocketSession s = userSessions.get(userId);
        return s != null && s.isOpen();
    }

    @Override
    public String getClientIp(Long userId) {
        WebSocketSession s = userSessions.get(userId);
        if (s == null || !s.isOpen()) return null;
        java.net.InetSocketAddress addr = s.getRemoteAddress();
        return addr != null ? addr.getAddress().getHostAddress() : null;
    }

    // ==================== Session-Level Events ====================

    public void sendForceLogout(Long userId, String reason) {
        sendToUser(userId, "force_logout", Map.of(
                "reason", reason != null ? reason : "Account logged in from another location",
                "message", "You have been logged out. Please log in again."
        ));
    }

    public void sendSessionExpired(Long userId) {
        sendToUser(userId, "session_expired", Map.of(
                "message", "Session expired. Please log in again."
        ));
    }

    // ==================== Room-Level Broadcasts ====================

    public void broadcastPlayerJoined(Long roomId, Long userId, String username) {
        RoomResponse roomResponse = roomResponseAssembler.toResponseById(roomId);
        broadcastToRoom(roomId, "player_joined",
                PlayerJoinedEventDTO.builder().userId(userId).username(username).room(roomResponse).build());
    }

    public void broadcastPlayerLeft(Long roomId, Long userId) {
        RoomResponse roomResponse = roomResponseAssembler.toResponseById(roomId);
        broadcastToRoom(roomId, "player_left",
                PlayerLeftEventDTO.builder().userId(userId).room(roomResponse).build());
    }

    public void broadcastPlayerReady(Long roomId, Long userId, boolean isReady) {
        RoomResponse roomResponse = roomResponseAssembler.toResponseById(roomId);
        broadcastToRoom(roomId, "player_ready",
                PlayerReadyEventDTO.builder().userId(userId).isReady(isReady).room(roomResponse).build());
    }

    public void broadcastTeamChanged(Long roomId, Long userId, Integer newTeam, Integer newSlot) {
        RoomResponse roomResponse = roomResponseAssembler.toResponseById(roomId);
        broadcastToRoom(roomId, "team_changed",
                TeamChangedEventDTO.builder().userId(userId).newTeam(newTeam).newSlot(newSlot).room(roomResponse).build());
    }

    public void broadcastRoomStatusChanged(Long roomId, String newStatus) {
        RoomResponse roomResponse = roomResponseAssembler.toResponseById(roomId);
        broadcastToRoom(roomId, "room_status_changed",
                RoomStatusChangedEventDTO.builder().newStatus(newStatus).room(roomResponse).build());
    }

    public void broadcastRoomUpdate(Long roomId) {
        RoomResponse roomResponse = roomResponseAssembler.toResponseById(roomId);
        if (roomResponse != null) {
            broadcastToRoom(roomId, "room_updated", roomResponse);
        }
    }

    public void broadcastSwapRequest(Long roomId, Long requesterId, Long targetUserId, Long requestId) {
        // BUG-3 fix: look up requester's username so the UI can display "Swap request from <name>"
        String requesterUsername = userRepository.findById(requesterId)
                .map(u -> u.getUsername())
                .orElse("Unknown");
        sendToUser(targetUserId, "swap_request",
                SwapRequestEventDTO.builder()
                        .requesterId(requesterId)
                        .requesterUsername(requesterUsername)
                        .targetUserId(targetUserId)
                        .requestId(requestId)
                        .build());
    }

    public void broadcastSwapRequestStatusChanged(Long roomId, Long requestId, String status) {
        RoomResponse roomResponse = roomResponseAssembler.toResponseById(roomId);
        broadcastToRoom(roomId, "swap_request_status",
                SwapRequestStatusEventDTO.builder().requestId(requestId).status(status).room(roomResponse).build());
    }

    // ==================== Private Helpers ====================

    /**
     * Remove a disconnected player from any WAITING room so the slot is freed.
     * IN_GAME rooms are untouched (player may reconnect mid-match).
     */
    private void cleanupWaitingRoomPlayer(Long userId) {
        try {
            roomPlayerRepository.findByUserId(userId).stream()
                    .findFirst()
                    .ifPresent(rp -> {
                        roomRepository.findById(rp.getRoomId()).ifPresent(room -> {
                            if ("WAITING".equals(room.getStatus())) {
                                transactionTemplate.executeWithoutResult(status -> {
                                    roomPlayerRepository.deleteByRoomIdAndUserId(room.getId(), userId);
                                });
                                log.info("Removed disconnected player userId={} from WAITING room {}", userId, room.getId());
                                // Broadcast updated room state to remaining players
                                try {
                                    RoomResponse response = roomResponseAssembler.toResponseById(room.getId());
                                    if (response != null) {
                                        broadcastToRoom(room.getId(), "player_left", response);
                                    }
                                } catch (Exception e) {
                                    log.warn("Failed to broadcast player_left for userId={} room={}: {}",
                                            userId, room.getId(), e.getMessage());
                                }
                            }
                        });
                    });
        } catch (Exception e) {
            log.warn("Error cleaning up room for disconnected userId={}: {}", userId, e.getMessage());
        }
    }

    private Long findCurrentRoomId(Long userId) {
        return roomPlayerRepository.findByUserId(userId).stream()
                .findFirst()
                .map(RoomPlayer::getRoomId)
                .orElse(null);
    }

    private String extractToken(String uri) {
        try {
            String[] parts = uri.split("\\?");
            if (parts.length > 1) {
                for (String param : parts[1].split("&")) {
                    String[] kv = param.split("=");
                    if (kv.length == 2 && "token".equals(kv[0])) {
                        return kv[1];
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error extracting token from URI: {}", uri);
        }
        return null;
    }

    private Long authenticateUser(String token) {
        try {
            return tokenProvider.getUserIdFromToken(token);
        } catch (Exception e) {
            log.debug("Token authentication failed: {}", e.getMessage());
            return null;
        }
    }

    private String createWebSocketMessage(String type, Object data) {
        try {
            String dataJson = objectMapper.writeValueAsString(data);
            WebSocketMessageDTO message = WebSocketMessageDTO.builder()
                    .type(type)
                    .data(dataJson)
                    .build();
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("Error creating WebSocket message: {}", e.getMessage());
            return null;
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.NORMAL);
        } catch (Exception ignored) {
        }
    }

    // ==================== Session Metadata ====================

    private record SessionMetadata(Long userId, String token) {
        Long getUserId() { return userId; }
        String getToken() { return token; }
    }
}
