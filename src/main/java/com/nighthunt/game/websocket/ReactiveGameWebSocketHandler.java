package com.nighthunt.game.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.friend.service.PlayerStatusService;
import com.nighthunt.game.websocket.dto.WebSocketMessageDTO;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.matchmaking.service.MatchmakingQueueService;
import com.nighthunt.match.service.MatchPresenceService;
import com.nighthunt.room.dto.RoomResponse;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import com.nighthunt.room.service.RoomResponseAssembler;
import com.nighthunt.security.port.TokenProvider;
import com.nighthunt.session.port.SessionStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ReactiveGameWebSocketHandler — Reactor Netty–based WebSocket handler.
 *
 * <p>Implements {@link ConnectionManager} so all existing business services
 * (RoomService, PartyService, FriendService, etc.) continue to call the same
 * port interface without any changes.</p>
 *
 * <h2>Architecture</h2>
 * <pre>
 * Unity Client ─── WSS ──→ Reactor Netty EventLoop
 *                              │
 *                              ▼
 *                  ReactiveGameWebSocketHandler.handle()
 *                              │
 *                    per-user Sinks.Many<String>   ← non-blocking write
 *                              │
 *                         WebSocketSession.send()
 * </pre>
 *
 * <h2>Client contract (unchanged)</h2>
 * <ul>
 *   <li>Endpoint: {@code wss://host/ws/game?token=...}</li>
 *   <li>Auth: JWT in query param {@code token}</li>
 *   <li>Messages: JSON {@code {"type":"...","data":"..."}}</li>
 *   <li>Keepalive: client sends {@code {"type":"ping"}} → server replies {@code {"type":"pong"}}</li>
 * </ul>
 *
 * <h2>Differences from Tomcat handler</h2>
 * <ul>
 *   <li>Uses {@link Sinks.Many} per user — write path is fully non-blocking.</li>
 *   <li>No {@code synchronized(session)} block required (Reactor Netty is single-writer per channel).</li>
 *   <li>Stale eviction still runs via {@code @Scheduled} (Spring scheduler thread, not Netty I/O).</li>
 * </ul>
 */
@Slf4j
@Component
public class ReactiveGameWebSocketHandler implements WebSocketHandler, ConnectionManager {

    private final RoomPlayerRepository roomPlayerRepository;
    private final RoomRepository roomRepository;
    private final RoomResponseAssembler roomResponseAssembler;
    private final ObjectMapper objectMapper;
    private final TokenProvider tokenProvider;
    private final SessionStore sessionStore;
    private final PlayerStatusService playerStatusService;
    private final TransactionTemplate transactionTemplate;

    private MatchmakingQueueService matchmakingQueueService;
    private MatchPresenceService matchPresenceService;

    // ── Session State ─────────────────────────────────────────────────────────

    /** userId → reactive sink (non-blocking write channel) */
    private final Map<Long, Sinks.Many<String>> userSinks     = new ConcurrentHashMap<>();
    /** userId → roomId  (forward index) */
    private final Map<Long, Long>               userRooms     = new ConcurrentHashMap<>();
    /** roomId → Set<userId>  (reverse index — O(1) broadcastToRoom) */
    private final Map<Long, Set<Long>>          roomUsers     = new ConcurrentHashMap<>();
    /** userId → last ping/message timestamp (stale detection) */
    private final Map<Long, Instant>            lastActivityAt = new ConcurrentHashMap<>();

    private static final long STALE_TIMEOUT_SECONDS = 30L;
    private static final String PONG_FRAME = "{\"type\":\"pong\"}";

    // ── Constructor ───────────────────────────────────────────────────────────

    public ReactiveGameWebSocketHandler(
            RoomPlayerRepository roomPlayerRepository,
            RoomRepository roomRepository,
            RoomResponseAssembler roomResponseAssembler,
            ObjectMapper objectMapper,
            TokenProvider tokenProvider,
            SessionStore sessionStore,
            PlayerStatusService playerStatusService,
            TransactionTemplate transactionTemplate) {
        this.roomPlayerRepository = roomPlayerRepository;
        this.roomRepository       = roomRepository;
        this.roomResponseAssembler = roomResponseAssembler;
        this.objectMapper         = objectMapper;
        this.tokenProvider        = tokenProvider;
        this.sessionStore         = sessionStore;
        this.playerStatusService  = playerStatusService;
        this.transactionTemplate  = transactionTemplate;
    }

    @Autowired @Lazy
    public void setMatchmakingQueueService(MatchmakingQueueService s) { this.matchmakingQueueService = s; }

    @Autowired @Lazy
    public void setMatchPresenceService(MatchPresenceService s) { this.matchPresenceService = s; }

    // ── WebSocketHandler ──────────────────────────────────────────────────────

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        // 1. Extract + validate JWT
        String token = extractToken(session.getHandshakeInfo().getUri().toString());
        if (token == null) {
            log.warn("Reactive WS rejected: missing token");
            return session.close();
        }

        Long userId = authenticateUser(token);
        if (userId == null) {
            log.warn("Reactive WS rejected: invalid token");
            return session.close();
        }

        // 2. Validate Redis session
        String redisSession = sessionStore.getSessionId(String.valueOf(userId));
        if (redisSession == null) {
            log.warn("Reactive WS rejected: no Redis session for userId={}", userId);
            return session.close();
        }
        if (sessionStore.isForceLogout(String.valueOf(userId))) {
            log.warn("Reactive WS rejected: force-logout for userId={}", userId);
            return session.close();
        }

        // 3. Single-device policy — close existing sink if present
        Sinks.Many<String> existing = userSinks.remove(userId);
        if (existing != null) {
            log.info("Closing previous reactive WS sink for userId={}", userId);
            existing.tryEmitComplete();
        }

        // 4. Create per-user sink (unbounded, backpressure via LATEST drop on overflow)
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        userSinks.put(userId, sink);
        lastActivityAt.put(userId, Instant.now());

        // 5. Mark online
        safeSetOnline(userId);

        // 6. Restore room state
        Long roomId = findCurrentRoomId(userId);
        if (roomId != null) {
            updateUserRoom(userId, roomId);
            recordTransportConnected(userId);
        }

        // 7. Send connected confirmation
        sendToUser(userId, "connected", Map.of(
                "userId",  userId,
                "roomId",  roomId != null ? roomId : 0,
                "message", "Game WebSocket connected"
        ));

        // 8. Push current room state (skip terminal rooms)
        if (roomId != null) {
            RoomResponse roomResponse = roomResponseAssembler.toResponseById(roomId);
            if (roomResponse != null
                    && !GameConstants.ROOM_STATUS_CLOSED.equals(roomResponse.getStatus())
                    && !GameConstants.ROOM_STATUS_FINISHED.equals(roomResponse.getStatus())) {
                sendToUser(userId, "room_updated", roomResponse);
            }
        }

        log.info("Reactive WS connected: userId={}, roomId={}", userId, roomId);

        // 9. Outbound stream — sink → WebSocket frames
        Flux<WebSocketMessage> outbound = sink.asFlux()
                .map(session::textMessage);

        // 10. Inbound stream — handle ping, update heartbeat
        Mono<Void> inbound = session.receive()
                .doOnNext(msg -> {
                    lastActivityAt.put(userId, Instant.now());
                    String payload = msg.getPayloadAsText();
                    if (payload != null && payload.contains("\"ping\"")) {
                        // reply pong directly via sink
                        Sinks.Many<String> s = userSinks.get(userId);
                        if (s != null) s.tryEmitNext(PONG_FRAME);
                    }
                    log.debug("Reactive WS inbound userId={}: {}", userId, payload);
                })
                .then();

        // 11. Teardown on disconnect
        Mono<Void> teardown = Mono.fromRunnable(() -> onDisconnect(userId, session.getId()));

        return Mono.zip(
                session.send(outbound).then(teardown),
                inbound.then(teardown)
        ).then();
    }

    // ── Disconnect handling ───────────────────────────────────────────────────

    private void onDisconnect(Long userId, String sessionId) {
        Sinks.Many<String> removed = userSinks.remove(userId);
        if (removed == null) {
            // Already replaced by a new connection — do not mark offline
            log.debug("Reactive WS onDisconnect: sink already replaced for userId={}", userId);
            return;
        }
        removed.tryEmitComplete();

        // Clean up room index
        Long roomId = userRooms.remove(userId);
        if (roomId != null) {
            roomUsers.computeIfPresent(roomId, (k, set) -> {
                set.remove(userId);
                return set.isEmpty() ? null : set;
            });
        }
        lastActivityAt.remove(userId);

        safeSetOffline(userId);
        safeDequeue(userId);
        recordTransportDisconnected(userId, "TRANSPORT_DROP");
        cleanupWaitingRoomPlayer(userId);

        log.info("Reactive WS disconnected: userId={}, sessionId={}", userId, sessionId);
    }

    // ── Stale eviction ────────────────────────────────────────────────────────

    /**
     * Evicts connections that have not sent any frame in {@value STALE_TIMEOUT_SECONDS} seconds.
     * Unity client must send {@code {"type":"ping"}} every 10–15 s.
     */
    @Scheduled(fixedDelay = 15_000)
    public void evictStaleConnections() {
        Instant cutoff = Instant.now().minusSeconds(STALE_TIMEOUT_SECONDS);
        List<Long> stale = new ArrayList<>();
        lastActivityAt.forEach((uid, ts) -> {
            if (ts.isBefore(cutoff)) stale.add(uid);
        });
        for (Long uid : stale) {
            log.warn("Evicting stale reactive WS for userId={} (idle >{}s)", uid, STALE_TIMEOUT_SECONDS);
            Sinks.Many<String> sink = userSinks.remove(uid);
            if (sink != null) sink.tryEmitComplete();
            lastActivityAt.remove(uid);
            Long roomId = userRooms.remove(uid);
            if (roomId != null) {
                roomUsers.computeIfPresent(roomId, (k, set) -> {
                    set.remove(uid);
                    return set.isEmpty() ? null : set;
                });
            }
            safeSetOffline(uid);
            recordTransportDisconnected(uid, "STALE_CONNECTION");
        }
        if (!stale.isEmpty()) {
            log.info("Stale reactive WS eviction: {} session(s)", stale.size());
        }
    }

    // ── ConnectionManager ─────────────────────────────────────────────────────

    @Override
    public void sendToUser(Long userId, String eventType, Object data) {
        Sinks.Many<String> sink = userSinks.get(userId);
        if (sink == null) return;
        String json = buildMessage(eventType, data);
        if (json == null) return;
        // tryEmitNext is non-blocking and thread-safe — no synchronized needed
        Sinks.EmitResult result = sink.tryEmitNext(json);
        if (result.isFailure()) {
            log.warn("Sink emit failure for userId={} event={}: {}", userId, eventType, result);
        }
    }

    @Override
    public void broadcastToRoom(Long roomId, String eventType, Object data) {
        Set<Long> members = roomUsers.getOrDefault(roomId, Collections.emptySet());
        for (Long uid : members) {
            sendToUser(uid, eventType, data);
        }
    }

    @Override
    public void updateUserRoom(Long userId, Long roomId) {
        Long old = userRooms.get(userId);
        if (old != null) {
            roomUsers.computeIfPresent(old, (k, set) -> {
                set.remove(userId);
                return set.isEmpty() ? null : set;
            });
        }
        if (roomId != null && roomId > 0) {
            userRooms.put(userId, roomId);
            roomUsers.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(userId);
        } else {
            userRooms.remove(userId);
        }
    }

    @Override
    public int getActiveConnectionCount() {
        return userSinks.size();
    }

    @Override
    public boolean isUserConnected(Long userId) {
        Sinks.Many<String> sink = userSinks.get(userId);
        return sink != null;
    }

    @Override
    public String getClientIp(Long userId) {
        // IP is not directly accessible from reactive WebSocketSession after handshake.
        // For relay mode: capture at handshake time if needed (future enhancement).
        return null;
    }

    // ── Session-Level Events ──────────────────────────────────────────────────

    public void sendForceLogout(Long userId, String reason) {
        recordSessionTerminated(userId, "FORCE_LOGOUT");
        sendToUser(userId, "force_logout", Map.of(
                "reason",  reason != null ? reason : "Account logged in from another location",
                "message", "You have been logged out. Please log in again."
        ));
    }

    public void sendSessionExpired(Long userId) {
        recordSessionTerminated(userId, "SESSION_EXPIRED");
        sendToUser(userId, "session_expired", Map.of(
                "message", "Session expired. Please log in again."
        ));
    }

    // ── Room-Level Broadcasts (delegated from RoomService/etc.) ──────────────

    public void broadcastPlayerJoined(Long roomId, Long userId, String username) {
        RoomResponse room = roomResponseAssembler.toResponseById(roomId);
        broadcastToRoom(roomId, "player_joined", Map.of(
                "userId", userId, "username", username, "room", room));
    }

    public void broadcastPlayerLeft(Long roomId, Long userId) {
        RoomResponse room = roomResponseAssembler.toResponseById(roomId);
        broadcastToRoom(roomId, "player_left", Map.of("userId", userId, "room", room));
    }

    public void broadcastRoomUpdate(Long roomId) {
        RoomResponse room = roomResponseAssembler.toResponseById(roomId);
        if (room != null) broadcastToRoom(roomId, "room_updated", room);
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private String buildMessage(String type, Object data) {
        try {
            String dataJson = objectMapper.writeValueAsString(data);
            return objectMapper.writeValueAsString(
                    WebSocketMessageDTO.builder().type(type).data(dataJson).build());
        } catch (Exception e) {
            log.error("Failed to build WS message type={}: {}", type, e.getMessage());
            return null;
        }
    }

    private String extractToken(String uri) {
        try {
            String[] parts = uri.split("\\?");
            if (parts.length > 1) {
                for (String param : parts[1].split("&")) {
                    String[] kv = param.split("=");
                    if (kv.length == 2 && "token".equals(kv[0])) return kv[1];
                }
            }
        } catch (Exception ignored) { /* malformed URI */ }
        return null;
    }

    private Long authenticateUser(String token) {
        try {
            return tokenProvider.getUserIdFromToken(token);
        } catch (Exception e) {
            log.debug("Token auth failed: {}", e.getMessage());
            return null;
        }
    }

    private Long findCurrentRoomId(Long userId) {
        return roomPlayerRepository.findActiveRoomsByUserId(userId).stream()
                .findFirst()
                .map(RoomPlayer::getRoomId)
                .orElse(null);
    }

    private void cleanupWaitingRoomPlayer(Long userId) {
        try {
            roomPlayerRepository.findByUserId(userId).stream()
                    .findFirst()
                    .ifPresent(rp -> roomRepository.findById(rp.getRoomId()).ifPresent(room -> {
                        if ("WAITING".equals(room.getStatus())) {
                            transactionTemplate.executeWithoutResult(s ->
                                    roomPlayerRepository.deleteByRoomIdAndUserId(room.getId(), userId));
                            log.info("Removed disconnected userId={} from WAITING room {}", userId, room.getId());
                            RoomResponse response = roomResponseAssembler.toResponseById(room.getId());
                            if (response != null) {
                                broadcastToRoom(room.getId(), "player_left",
                                        Map.of("userId", userId, "room", response));
                            }
                        }
                    }));
        } catch (Exception e) {
            log.warn("Error cleaning up room for userId={}: {}", userId, e.getMessage());
        }
    }

    private void safeSetOnline(Long userId) {
        try { playerStatusService.setOnline(userId); }
        catch (Exception e) { log.error("setOnline failed for userId={}: {}", userId, e.getMessage()); }
    }

    private void safeSetOffline(Long userId) {
        try { playerStatusService.setOffline(userId); }
        catch (Exception e) { log.error("setOffline failed for userId={}: {}", userId, e.getMessage()); }
    }

    private void safeDequeue(Long userId) {
        if (matchmakingQueueService == null) return;
        try { matchmakingQueueService.dequeue(userId); }
        catch (Exception e) { log.debug("Dequeue on disconnect for userId={}: {}", userId, e.getMessage()); }
    }

    private void recordTransportConnected(Long userId) {
        if (matchPresenceService == null) return;
        try { matchPresenceService.recordTransportConnected(userId); }
        catch (Exception e) { log.warn("recordTransportConnected failed userId={}: {}", userId, e.getMessage()); }
    }

    private void recordTransportDisconnected(Long userId, String reason) {
        if (matchPresenceService == null) return;
        try { matchPresenceService.recordTransportDisconnected(userId, reason); }
        catch (Exception e) { log.warn("recordTransportDisconnected failed userId={} reason={}: {}", userId, reason, e.getMessage()); }
    }

    private void recordSessionTerminated(Long userId, String reason) {
        if (matchPresenceService == null) return;
        try { matchPresenceService.recordSessionTerminated(userId, reason); }
        catch (Exception e) { log.warn("recordSessionTerminated failed userId={}: {}", userId, e.getMessage()); }
    }
}
