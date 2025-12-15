package com.nighthunt.game.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nighthunt.game.websocket.dto.WebSocketMessageDTO;
import com.nighthunt.room.dto.RoomResponse;
import com.nighthunt.room.dto.RoomPlayerResponse;
import com.nighthunt.room.entity.Room;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import com.nighthunt.room.service.RoomService;
import com.nighthunt.security.port.TokenProvider;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Unified WebSocket handler for all game events
 * Handles both session-level events (force_logout, session_expired) and room-level events
 * Single WebSocket connection per user, established after login and kept alive throughout session
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameWebSocketHandler extends TextWebSocketHandler {
    
    private final RoomRepository roomRepository;
    private final RoomPlayerRepository roomPlayerRepository;
    private final UserRepository userRepository;
    // Removed RoomService dependency to break circular dependency - using buildRoomResponse() directly
    private final ObjectMapper objectMapper;
    private final TokenProvider tokenProvider;
    
    // Store active user sessions by userId -> session
    private final Map<Long, WebSocketSession> userSessions = new ConcurrentHashMap<>();
    
    // Store user's current room (userId -> roomId)
    private final Map<Long, Long> userRooms = new ConcurrentHashMap<>();
    
    // Store session metadata
    private final Map<WebSocketSession, SessionMetadata> sessionMetadata = new ConcurrentHashMap<>();
    
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("Game WebSocket connection established: {}", session.getId());
        
        // Extract token from URI
        String uri = session.getUri().toString();
        String token = extractToken(uri);
        
        if (token == null) {
            log.warn("Invalid WebSocket connection: missing token");
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        
        // Authenticate user from token
        Long userId = authenticateUser(token);
        if (userId == null) {
            log.warn("WebSocket authentication failed for session: {}", session.getId());
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        
        // Check if there's already a session for this user (close old one)
        WebSocketSession existingSession = userSessions.get(userId);
        if (existingSession != null && existingSession.isOpen()) {
            log.info("Closing existing WebSocket session for user {} (new connection)", userId);
            try {
                existingSession.close(CloseStatus.NORMAL);
            } catch (Exception e) {
                log.error("Error closing existing session: {}", e.getMessage());
            }
        }
        
        // Store new session
        userSessions.put(userId, session);
        sessionMetadata.put(session, new SessionMetadata(userId, token));
        
        // Check if user is in a room
        Long roomId = getCurrentRoomId(userId);
        if (roomId != null) {
            userRooms.put(userId, roomId);
            log.info("User {} connected via Game WebSocket, currently in room {}", userId, roomId);
        } else {
            log.info("User {} connected via Game WebSocket, not in any room", userId);
        }
        
        // Send initial connection confirmation
        sendMessage(session, "connected", Map.of(
            "userId", userId,
            "roomId", roomId != null ? roomId : 0,
            "message", "Game WebSocket connected"
        ));
    }
    
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // Handle client messages (ping/pong, room join/leave, etc.)
        log.debug("Received message from session {}: {}", session.getId(), message.getPayload());
        
        // For now, we only handle server-to-client messages
        // Client can send ping/pong for keepalive if needed
    }
    
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        SessionMetadata metadata = sessionMetadata.remove(session);
        if (metadata != null) {
            Long userId = metadata.getUserId();
            userSessions.remove(userId);
            userRooms.remove(userId);
            log.info("Game WebSocket disconnected for user {} (status: {})", userId, status);
        } else {
            log.info("Game WebSocket disconnected (status: {})", status);
        }
    }
    
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("Game WebSocket transport error for session {}: {}", session.getId(), exception.getMessage());
        SessionMetadata metadata = sessionMetadata.remove(session);
        if (metadata != null) {
            Long userId = metadata.getUserId();
            userSessions.remove(userId);
            userRooms.remove(userId);
        }
    }
    
    // ==================== Session Events ====================
    
    /**
     * Send force logout event to user
     */
    public void sendForceLogout(Long userId, String reason) {
        WebSocketSession session = userSessions.get(userId);
        if (session == null || !session.isOpen()) {
            return;
        }
        
        try {
            String message = createWebSocketMessage("force_logout", Map.of(
                "reason", reason != null ? reason : "Tài khoản đã đăng nhập ở nơi khác",
                "message", "Bạn đã bị đăng xuất. Vui lòng đăng nhập lại."
            ));
            session.sendMessage(new TextMessage(message));
            log.info("Sent force_logout event to user {}", userId);
        } catch (IOException e) {
            log.error("Error sending force_logout event to user {}: {}", userId, e.getMessage());
        }
    }
    
    /**
     * Send session expired event to user
     */
    public void sendSessionExpired(Long userId) {
        WebSocketSession session = userSessions.get(userId);
        if (session == null || !session.isOpen()) {
            return;
        }
        
        try {
            String message = createWebSocketMessage("session_expired", Map.of(
                "message", "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại."
            ));
            session.sendMessage(new TextMessage(message));
            log.info("Sent session_expired event to user {}", userId);
        } catch (IOException e) {
            log.error("Error sending session_expired event to user {}: {}", userId, e.getMessage());
        }
    }
    
    // ==================== Room Events ====================
    
    /**
     * Update user's current room (called when user joins/leaves a room)
     */
    public void updateUserRoom(Long userId, Long roomId) {
        if (roomId != null && roomId > 0) {
            userRooms.put(userId, roomId);
        } else {
            userRooms.remove(userId);
        }
    }
    
    /**
     * Send room update event to user (when user is in a room)
     */
    public void sendRoomUpdate(Long userId, Long roomId) {
        WebSocketSession session = userSessions.get(userId);
        if (session == null || !session.isOpen()) {
            return;
        }
        
        try {
            // Build room response directly to avoid circular dependency with RoomService
            RoomResponse roomResponse = buildRoomResponse(roomId);
            if (roomResponse != null) {
                String message = createWebSocketMessage("room_updated", roomResponse);
                session.sendMessage(new TextMessage(message));
                log.debug("Sent room_updated event to user {} for room {}", userId, roomId);
            }
        } catch (Exception e) {
            log.error("Error sending room_updated event to user {}: {}", userId, e.getMessage());
        }
    }
    
    /**
     * Broadcast room update to all users in room
     */
    public void broadcastRoomUpdate(Long roomId) {
        List<RoomPlayer> players = roomPlayerRepository.findByRoomId(roomId);
        for (RoomPlayer player : players) {
            sendRoomUpdate(player.getUserId(), roomId);
        }
    }
    
    /**
     * Broadcast player joined event to all users in room
     */
    public void broadcastPlayerJoined(Long roomId, Long userId, String username) {
        List<RoomPlayer> players = roomPlayerRepository.findByRoomId(roomId);
        RoomResponse roomResponse = buildRoomResponse(roomId);
        
        PlayerJoinedEvent event = new PlayerJoinedEvent(userId, username, roomResponse);
        String message = createWebSocketMessage("player_joined", event);
        
        for (RoomPlayer player : players) {
            WebSocketSession session = userSessions.get(player.getUserId());
            if (session != null && session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(message));
                } catch (IOException e) {
                    log.error("Error sending player_joined event to user {}: {}", player.getUserId(), e.getMessage());
                }
            }
        }
    }
    
    /**
     * Broadcast player left event to all users in room
     */
    public void broadcastPlayerLeft(Long roomId, Long userId) {
        List<RoomPlayer> players = roomPlayerRepository.findByRoomId(roomId);
        RoomResponse roomResponse = buildRoomResponse(roomId);
        
        PlayerLeftEvent event = new PlayerLeftEvent(userId, roomResponse);
        String message = createWebSocketMessage("player_left", event);
        
        for (RoomPlayer player : players) {
            WebSocketSession session = userSessions.get(player.getUserId());
            if (session != null && session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(message));
                } catch (IOException e) {
                    log.error("Error sending player_left event to user {}: {}", player.getUserId(), e.getMessage());
                }
            }
        }
    }
    
    /**
     * Broadcast player ready event to all users in room
     */
    public void broadcastPlayerReady(Long roomId, Long userId, boolean isReady) {
        List<RoomPlayer> players = roomPlayerRepository.findByRoomId(roomId);
        RoomResponse roomResponse = buildRoomResponse(roomId);
        
        PlayerReadyEvent event = new PlayerReadyEvent(userId, isReady, roomResponse);
        String message = createWebSocketMessage("player_ready", event);
        
        for (RoomPlayer player : players) {
            WebSocketSession session = userSessions.get(player.getUserId());
            if (session != null && session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(message));
                } catch (IOException e) {
                    log.error("Error sending player_ready event to user {}: {}", player.getUserId(), e.getMessage());
                }
            }
        }
    }
    
    /**
     * Broadcast team changed event to all users in room
     */
    public void broadcastTeamChanged(Long roomId, Long userId, Integer newTeam, Integer newSlot) {
        List<RoomPlayer> players = roomPlayerRepository.findByRoomId(roomId);
        RoomResponse roomResponse = buildRoomResponse(roomId);
        
        TeamChangedEvent event = new TeamChangedEvent(userId, newTeam, newSlot, roomResponse);
        String message = createWebSocketMessage("team_changed", event);
        
        for (RoomPlayer player : players) {
            WebSocketSession session = userSessions.get(player.getUserId());
            if (session != null && session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(message));
                } catch (IOException e) {
                    log.error("Error sending team_changed event to user {}: {}", player.getUserId(), e.getMessage());
                }
            }
        }
    }
    
    /**
     * Broadcast room status changed event to all users in room
     */
    public void broadcastRoomStatusChanged(Long roomId, String newStatus) {
        List<RoomPlayer> players = roomPlayerRepository.findByRoomId(roomId);
        RoomResponse roomResponse = buildRoomResponse(roomId);
        
        RoomStatusChangedEvent event = new RoomStatusChangedEvent(newStatus, roomResponse);
        String message = createWebSocketMessage("room_status_changed", event);
        
        for (RoomPlayer player : players) {
            WebSocketSession session = userSessions.get(player.getUserId());
            if (session != null && session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(message));
                } catch (IOException e) {
                    log.error("Error sending room_status_changed event to user {}: {}", player.getUserId(), e.getMessage());
                }
            }
        }
    }
    
    /**
     * Broadcast swap request event to target user
     */
    public void broadcastSwapRequest(Long roomId, Long requesterId, Long targetUserId, Long requestId) {
        WebSocketSession session = userSessions.get(targetUserId);
        if (session == null || !session.isOpen()) {
            return;
        }
        
        try {
            SwapRequestEvent event = new SwapRequestEvent(requesterId, targetUserId, requestId);
            String message = createWebSocketMessage("swap_request", event);
            session.sendMessage(new TextMessage(message));
        } catch (IOException e) {
            log.error("Error sending swap_request event to user {}: {}", targetUserId, e.getMessage());
        }
    }
    
    /**
     * Broadcast swap request status changed event to requester and target
     */
    public void broadcastSwapRequestStatusChanged(Long roomId, Long requestId, String status) {
        // Get swap request details from repository (simplified - you may need to adjust)
        // For now, broadcast to all users in room
        List<RoomPlayer> players = roomPlayerRepository.findByRoomId(roomId);
        RoomResponse roomResponse = buildRoomResponse(roomId);
        
        SwapRequestStatusEvent event = new SwapRequestStatusEvent(requestId, status, roomResponse);
        String message = createWebSocketMessage("swap_request_status", event);
        
        for (RoomPlayer player : players) {
            WebSocketSession session = userSessions.get(player.getUserId());
            if (session != null && session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(message));
                } catch (IOException e) {
                    log.error("Error sending swap_request_status event to user {}: {}", player.getUserId(), e.getMessage());
                }
            }
        }
    }
    
    // ==================== Helper Methods ====================
    
    private Long getCurrentRoomId(Long userId) {
        RoomPlayer player = roomPlayerRepository.findByUserId(userId)
            .stream()
            .findFirst()
            .orElse(null);
        return player != null ? player.getRoomId() : null;
    }
    
    private RoomResponse buildRoomResponse(Long roomId) {
        try {
            Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Room not found: " + roomId));
            
            List<RoomPlayer> players = roomPlayerRepository.findByRoomId(roomId);
            List<RoomPlayerResponse> playerResponses = players.stream()
                .map(p -> {
                    User user = userRepository.findById(p.getUserId()).orElse(null);
                    return RoomPlayerResponse.builder()
                        .userId(p.getUserId())
                        .username(user != null ? user.getUsername() : "Unknown")
                        .team(p.getTeam())
                        .slot(p.getSlot())
                        .isReady(p.getIsReady())
                        .build();
                })
                .collect(Collectors.toList());
            
            return RoomResponse.builder()
                .roomId(room.getId())
                .roomCode(room.getRoomCode())
                .mode(room.getMode())
                .status(room.getStatus())
                .isPublic(room.getIsPublic())
                .isLocked(room.getIsLocked())
                .ownerId(room.getOwnerId())
                .players(playerResponses)
                .build();
        } catch (Exception e) {
            log.error("Error building room response: {}", e.getMessage());
            return null;
        }
    }
    
    private String extractToken(String uri) {
        try {
            String[] parts = uri.split("\\?");
            if (parts.length > 1) {
                String[] params = parts[1].split("&");
                for (String param : params) {
                    String[] keyValue = param.split("=");
                    if (keyValue.length == 2 && keyValue[0].equals("token")) {
                        return keyValue[1];
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
            log.error("Error authenticating user with token: {}", e.getMessage());
            return null;
        }
    }
    
    private void sendMessage(WebSocketSession session, String type, Object data) {
        try {
            String message = createWebSocketMessage(type, data);
            session.sendMessage(new TextMessage(message));
        } catch (IOException e) {
            log.error("Error sending message: {}", e.getMessage());
        }
    }
    
    private String createWebSocketMessage(String type, Object data) {
        try {
            // Format: {"type": "event_type", "data": "{\"key\":\"value\"}"}
            // data is serialized as JSON string for compatibility with Unity JsonUtility
            String dataJson = objectMapper.writeValueAsString(data);
            WebSocketMessageDTO message = WebSocketMessageDTO.builder()
                .type(type)
                .data(dataJson) // data as JSON string for Unity compatibility
                .build();
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("Error creating WebSocket message: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Get active WebSocket connection count
     */
    public int getActiveConnectionCount() {
        return userSessions.size();
    }
    
    // ==================== Inner Classes ====================
    
    private static class SessionMetadata {
        private final Long userId;
        private final String token;
        
        public SessionMetadata(Long userId, String token) {
            this.userId = userId;
            this.token = token;
        }
        
        public Long getUserId() { return userId; }
        public String getToken() { return token; }
    }
    
    // WebSocketMessageDTO is now in separate file for consistency
    
    // Event classes
    private static class PlayerJoinedEvent {
        private Long userId;
        private String username;
        private RoomResponse room;
        
        public PlayerJoinedEvent(Long userId, String username, RoomResponse room) {
            this.userId = userId;
            this.username = username;
            this.room = room;
        }
        
        public Long getUserId() { return userId; }
        public String getUsername() { return username; }
        public RoomResponse getRoom() { return room; }
    }
    
    private static class PlayerLeftEvent {
        private Long userId;
        private RoomResponse room;
        
        public PlayerLeftEvent(Long userId, RoomResponse room) {
            this.userId = userId;
            this.room = room;
        }
        
        public Long getUserId() { return userId; }
        public RoomResponse getRoom() { return room; }
    }
    
    private static class PlayerReadyEvent {
        private Long userId;
        private boolean isReady;
        private RoomResponse room;
        
        public PlayerReadyEvent(Long userId, boolean isReady, RoomResponse room) {
            this.userId = userId;
            this.isReady = isReady;
            this.room = room;
        }
        
        public Long getUserId() { return userId; }
        public boolean getIsReady() { return isReady; }
        public RoomResponse getRoom() { return room; }
    }
    
    private static class TeamChangedEvent {
        private Long userId;
        private Integer newTeam;
        private Integer newSlot;
        private RoomResponse room;
        
        public TeamChangedEvent(Long userId, Integer newTeam, Integer newSlot, RoomResponse room) {
            this.userId = userId;
            this.newTeam = newTeam;
            this.newSlot = newSlot;
            this.room = room;
        }
        
        public Long getUserId() { return userId; }
        public Integer getNewTeam() { return newTeam; }
        public Integer getNewSlot() { return newSlot; }
        public RoomResponse getRoom() { return room; }
    }
    
    private static class RoomStatusChangedEvent {
        private String newStatus;
        private RoomResponse room;
        
        public RoomStatusChangedEvent(String newStatus, RoomResponse room) {
            this.newStatus = newStatus;
            this.room = room;
        }
        
        public String getNewStatus() { return newStatus; }
        public RoomResponse getRoom() { return room; }
    }
    
    private static class SwapRequestEvent {
        private Long requesterId;
        private Long targetUserId;
        private Long requestId;
        
        public SwapRequestEvent(Long requesterId, Long targetUserId, Long requestId) {
            this.requesterId = requesterId;
            this.targetUserId = targetUserId;
            this.requestId = requestId;
        }
        
        public Long getRequesterId() { return requesterId; }
        public Long getTargetUserId() { return targetUserId; }
        public Long getRequestId() { return requestId; }
    }
    
    private static class SwapRequestStatusEvent {
        private Long requestId;
        private String status;
        private RoomResponse room;
        
        public SwapRequestStatusEvent(Long requestId, String status, RoomResponse room) {
            this.requestId = requestId;
            this.status = status;
            this.room = room;
        }
        
        public Long getRequestId() { return requestId; }
        public String getStatus() { return status; }
        public RoomResponse getRoom() { return room; }
    }
}

