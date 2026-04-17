package com.nighthunt.relay.service;

import com.nighthunt.relay.model.RelaySession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * BE-24 — Mini-Relay Session Manager.
 *
 * <p>Manages in-memory {@link RelaySession} objects and coordinates with the
 * external Python relay server (relay/relay_server.py) via its HTTP management API.</p>
 *
 * <p>Two modes:</p>
 * <ol>
 *   <li><b>relay.server.url is set</b>: calls the relay server's {@code POST /session/create}
 *       to allocate a unique UDP port per session. The relay server does the actual
 *       UDP packet forwarding so players on different networks (mobile, different WiFi)
 *       can connect without port forwarding.</li>
 *   <li><b>relay.server.url is blank</b>: falls back to a single fixed port + host IP
 *       (only works on LAN or with port forwarding — legacy mode).</li>
 * </ol>
 */
@Slf4j
@Service
public class RelaySessionManager {

    /** How many hours before an inactive relay session auto-expires. */
    @Value("${relay.session.ttl-hours:2}")
    private int ttlHours;

    /**
     * URL of the relay server's management HTTP API (internal VPS address).
     * Example: {@code http://nighthunt-relay:7776}
     * If blank, falls back to single-port direct-IP mode (LAN / port-forward only).
     */
    @Value("${relay.server.url:}")
    private String relayServerUrl;

    /** Public IP / hostname of the relay server (sent to clients as relayHost). */
    @Value("${relay.host:}")
    private String configuredRelayHost;

    /** Fallback port used in single-port direct-IP mode (no relay server). */
    @Value("${relay.port:7777}")
    private int defaultRelayPort;

    /** sessionToken → RelaySession */
    private final ConcurrentMap<String, RelaySession> sessions = new ConcurrentHashMap<>();

    /** roomId → sessionToken */
    private final ConcurrentMap<Long, String> roomIndex = new ConcurrentHashMap<>();

    private final RestTemplate rest = new RestTemplate();

    // ── API ──────────────────────────────────────────────────────────────────

    /**
     * Create a new relay session for a room that is starting a Custom match.
     *
     * @param roomId    the room identifier
     * @param matchId   the match UUID (pre-allocated by the caller)
     * @param hostIp    detected IP of the room owner's WebSocket connection.
     *                  Used as relay host when relay server and relay.host are not configured.
     * @return the newly created {@link RelaySession}
     */
    public RelaySession createSession(Long roomId, String matchId, String hostIp) {
        // Clean up any previous session for this room
        String oldToken = roomIndex.remove(roomId);
        if (oldToken != null) {
            sessions.remove(oldToken);
            log.info("[Relay] Replaced old session for room={}", roomId);
        }

        String token  = UUID.randomUUID().toString();
        Instant now   = Instant.now();

        // ── Resolve relay host and port ──────────────────────────────────────
        String resolvedHost;
        int    resolvedPort;

        if (!relayServerUrl.isBlank()) {
            // ── Mode A: Full relay server (works across any network) ─────────
            // Call the relay server's HTTP API to allocate a unique UDP port.
            int allocatedPort = allocatePortFromRelayServer(token);
            if (allocatedPort <= 0) {
                log.error("[Relay] Failed to allocate port from relay server — session not created");
                // Return a dummy session so callers don't NPE; game_starting won't fire
                return buildSession(token, roomId, matchId, now, "0.0.0.0", defaultRelayPort);
            }
            resolvedHost = configuredRelayHost.isBlank() ? extractHost(relayServerUrl) : configuredRelayHost;
            resolvedPort = allocatedPort;
            log.info("[Relay] Mode A (relay server): host={}:{} session={}", resolvedHost, resolvedPort, token);
        } else if (!configuredRelayHost.isBlank()) {
            // ── Mode B: Static relay host IP (VPS, but no relay server process) ──
            // All sessions share the same port — only works for 1 concurrent game.
            resolvedHost = configuredRelayHost;
            resolvedPort = defaultRelayPort;
            log.warn("[Relay] Mode B (static host, single port={}): only 1 concurrent game supported", resolvedPort);
        } else if (hostIp != null && !hostIp.isBlank()) {
            // ── Mode C: Direct P2P — host's detected public IP ────────────────
            // Works on LAN automatically; internet requires host to port-forward relayPort.
            resolvedHost = hostIp;
            resolvedPort = defaultRelayPort;
            log.info("[Relay] Mode C (direct P2P): hostIp={} port={} — LAN OK, internet needs port-forward", resolvedHost, resolvedPort);
        } else {
            resolvedHost = "127.0.0.1";
            resolvedPort = defaultRelayPort;
            log.warn("[Relay] Mode D (loopback fallback) — only same-machine play will work");
        }

        RelaySession session = buildSession(token, roomId, matchId, now, resolvedHost, resolvedPort);
        sessions.put(token, session);
        roomIndex.put(roomId, token);
        log.info("[Relay] Session created: token={} room={} match={} relayHost={}:{}",
                token, roomId, matchId, resolvedHost, resolvedPort);
        return session;
    }

    /**
     * Call the relay server HTTP API to allocate a unique UDP port for this session.
     *
     * @return allocated port (> 0), or 0 on failure
     */
    private int allocatePortFromRelayServer(String token) {
        try {
            String url  = relayServerUrl.stripTrailing("/") + "/session/create";
            Map<String, Object> body = Map.of("token", token);
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = rest.postForObject(url, body, Map.class);
            if (resp != null && resp.containsKey("port")) {
                return ((Number) resp.get("port")).intValue();
            }
        } catch (Exception e) {
            log.error("[Relay] HTTP call to relay server failed: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * Notify the relay server to close and release the port for this session.
     * Called on match end.
     */
    public void closeRelayServerSession(String token) {
        if (relayServerUrl.isBlank()) return;
        try {
            String url = relayServerUrl.stripTrailing("/") + "/session/close";
            rest.postForObject(url, Map.of("token", token), Map.class);
            log.info("[Relay] Relay server session closed: token={}", token);
        } catch (Exception e) {
            log.warn("[Relay] Failed to close relay server session {}: {}", token, e.getMessage());
        }
    }

    private RelaySession buildSession(String token, Long roomId, String matchId,
                                      Instant now, String host, int port) {
        return RelaySession.builder()
                .sessionToken(token)
                .roomId(roomId)
                .matchId(matchId)
                .relayHost(host)
                .relayPort(port)
                .createdAt(now)
                .expiresAt(now.plus(ttlHours, ChronoUnit.HOURS))
                .started(false)
                .finished(false)
                .build();
    }

    /** Extract host from a URL like http://nighthunt-relay:7776 → "nighthunt-relay" */
    private static String extractHost(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getHost();
        } catch (Exception e) {
            return url;
        }
    }

    // ── Lookup ────────────────────────────────────────────────────────────────

    public Optional<RelaySession> getByToken(String token) {
        return Optional.ofNullable(sessions.get(token));
    }

    public Optional<RelaySession> getByRoomId(Long roomId) {
        String token = roomIndex.get(roomId);
        if (token == null) return Optional.empty();
        return Optional.ofNullable(sessions.get(token));
    }

    public boolean addPlayer(String token, Long userId) {
        RelaySession s = sessions.get(token);
        if (s == null || s.isExpired()) return false;
        s.addPlayer(userId);
        return true;
    }

    public void removePlayer(String token, Long userId) {
        RelaySession s = sessions.get(token);
        if (s != null) s.removePlayer(userId);
    }

    public void markStarted(String token) {
        RelaySession s = sessions.get(token);
        if (s != null) {
            s.setStarted(true);
            log.info("[Relay] Session {} marked STARTED", token);
        }
    }

    public void finishSession(String token) {
        RelaySession s = sessions.remove(token);
        if (s != null) {
            s.setFinished(true);
            roomIndex.remove(s.getRoomId());
            closeRelayServerSession(token);
            log.info("[Relay] Session {} finished (room={})", token, s.getRoomId());
        }
    }

    public Collection<RelaySession> getAllActiveSessions() {
        return sessions.values().stream()
                .filter(s -> !s.isExpired() && !s.isFinished())
                .toList();
    }

    @Scheduled(fixedDelayString = "${relay.cleanup.interval-ms:600000}")
    public void evictExpiredSessions() {
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> {
            if (e.getValue().isExpired() || e.getValue().isFinished()) {
                roomIndex.remove(e.getValue().getRoomId());
                closeRelayServerSession(e.getKey());
                return true;
            }
            return false;
        });
        int removed = before - sessions.size();
        if (removed > 0)
            log.info("[Relay] Evicted {} expired session(s)", removed);
    }
}

/**
 * BE-24 — Mini-Relay Session Manager.
 *
 * <p>Manages in-memory {@link RelaySession} objects that track Custom-mode
 * relay sessions. The relay itself is handled by the game's Tugboat/FishNet
 * transport; this service issues tokens, tracks membership, and enforces TTL
 * cleanup so stale sessions do not accumulate.</p>
 *
 * <p>Sessions are keyed by {@code sessionToken} (UUID) with a secondary index
 * on {@code roomId → token} for fast lookup from room events.</p>
 */
@Slf4j
@Service
public class RelaySessionManager {

    /** How many hours before an inactive relay session auto-expires. */
    @Value("${relay.session.ttl-hours:2}")
    private int ttlHours;

    /** Optional fixed relay hostname / IP. If blank, caller must supply one. */
    @Value("${relay.host:}")
    private String configuredRelayHost;

    /** Default relay UDP port base (each room gets this exact port for now). */
    @Value("${relay.port:7777}")
    private int defaultRelayPort;

    /** sessionToken → RelaySession */
    private final ConcurrentMap<String, RelaySession> sessions = new ConcurrentHashMap<>();

    /** roomId → sessionToken */
    private final ConcurrentMap<Long, String> roomIndex = new ConcurrentHashMap<>();

    // ── API ──────────────────────────────────────────────────────────────────

    /**
     * Create a new relay session for a room that is starting a Custom match.
     *
     * @param roomId    the room identifier
     * @param matchId   the match UUID (pre-allocated by the caller)
     * @param hostIp    detected IP of the room owner's WebSocket connection.
     *                  Used as relay host when {@code relay.host} is not configured.
     *                  Null-safe: falls back to configured/default host.
     * @return the newly created {@link RelaySession}
     */
    public RelaySession createSession(Long roomId, String matchId, String hostIp) {
        // Clean up any previous session for this room
        String oldToken = roomIndex.remove(roomId);
        if (oldToken != null) {
            sessions.remove(oldToken);
            log.info("[Relay] Replaced old session for room={}", roomId);
        }

        // Resolve relay host:
        //   1. If relay.host is explicitly configured (e.g. VPS IP) → use it (VPS relay mode).
        //   2. Else if the host player's IP is available → use it (direct P2P mode).
        //   3. Fallback: "127.0.0.1" (LAN / same-machine test).
        String resolvedHost;
        if (!configuredRelayHost.isBlank()) {
            resolvedHost = configuredRelayHost;
            log.info("[Relay] Using configured relay.host={}", resolvedHost);
        } else if (hostIp != null && !hostIp.isBlank()) {
            resolvedHost = hostIp;
            log.info("[Relay] No relay.host configured — using host player's detected IP={} (direct P2P mode)", resolvedHost);
        } else {
            resolvedHost = "127.0.0.1";
            log.warn("[Relay] No relay.host config and no host IP detected — defaulting to 127.0.0.1 (LAN only)");
        }

        String token = UUID.randomUUID().toString();
        Instant now   = Instant.now();

        RelaySession session = RelaySession.builder()
                .sessionToken(token)
                .roomId(roomId)
                .matchId(matchId)
                .relayHost(resolvedHost)
                .relayPort(defaultRelayPort)
                .createdAt(now)
                .expiresAt(now.plus(ttlHours, ChronoUnit.HOURS))
                .started(false)
                .finished(false)
                .build();

        sessions.put(token, session);
        roomIndex.put(roomId, token);
        log.info("[Relay] Created session token={} room={} match={} relayHost={}:{}", token, roomId, matchId, resolvedHost, defaultRelayPort);
        return session;
    }

    /**
     * Look up a relay session by its token.
     */
    public Optional<RelaySession> getByToken(String token) {
        return Optional.ofNullable(sessions.get(token));
    }

    /**
     * Look up a relay session by room ID.
     */
    public Optional<RelaySession> getByRoomId(Long roomId) {
        String token = roomIndex.get(roomId);
        if (token == null) return Optional.empty();
        return Optional.ofNullable(sessions.get(token));
    }

    /**
     * Register a player as connected to a relay session.
     *
     * @return false if the session doesn't exist or is expired
     */
    public boolean addPlayer(String token, Long userId) {
        RelaySession s = sessions.get(token);
        if (s == null || s.isExpired()) return false;
        s.addPlayer(userId);
        log.debug("[Relay] Player {} joined session {}", userId, token);
        return true;
    }

    /**
     * Remove a player from a relay session (on disconnect or leave).
     */
    public void removePlayer(String token, Long userId) {
        RelaySession s = sessions.get(token);
        if (s != null) s.removePlayer(userId);
    }

    /**
     * Mark the relay session as started (game is live).
     */
    public void markStarted(String token) {
        RelaySession s = sessions.get(token);
        if (s != null) {
            s.setStarted(true);
            log.info("[Relay] Session {} marked STARTED", token);
        }
    }

    /**
     * Mark the relay session as finished and clean it up.
     */
    public void finishSession(String token) {
        RelaySession s = sessions.remove(token);
        if (s != null) {
            s.setFinished(true);
            roomIndex.remove(s.getRoomId());
            log.info("[Relay] Session {} finished and removed (room={})", token, s.getRoomId());
        }
    }

    /** All active (non-expired) sessions. Intended for monitoring/debugging. */
    public Collection<RelaySession> getAllActiveSessions() {
        return sessions.values().stream()
                .filter(s -> !s.isExpired() && !s.isFinished())
                .toList();
    }

    // ── Scheduled cleanup ────────────────────────────────────────────────────

    /**
     * Every 10 minutes evict expired sessions to prevent memory leaks.
     */
    @Scheduled(fixedDelayString = "${relay.cleanup.interval-ms:600000}")
    public void evictExpiredSessions() {
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> {
            if (e.getValue().isExpired() || e.getValue().isFinished()) {
                roomIndex.remove(e.getValue().getRoomId());
                return true;
            }
            return false;
        });
        int removed = before - sessions.size();
        if (removed > 0)
            log.info("[Relay] Evicted {} expired session(s)", removed);
    }
}
