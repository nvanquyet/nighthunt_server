package com.nighthunt.relay.service;

import com.nighthunt.relay.model.RelaySession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Collection;
import java.util.List;
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
    @Value("${RELAY_SESSION_TTL_HOURS:2}")
    private int ttlHours;

    /**
     * URL of the relay server's management HTTP API (internal VPS address).
     * Example: {@code http://nighthunt-relay:7776}
     * If blank, falls back to single-port direct-IP mode (LAN / port-forward only).
     */
    @Value("${RELAY_SERVER_URL:}")
    private String relayServerUrl;

    /** Public IP / hostname of the relay server (sent to clients as relayHost). */
    @Value("${RELAY_HOST:}")
    private String configuredRelayHost;

    /** Public VPS IP used when RELAY_HOST is missing on the deployed host. */
    @Value("${VPS_PUBLIC_IP:}")
    private String vpsPublicIp;

    /** Public API URL; its host is a final fallback for client-facing relay host. */
    @Value("${API_BASE_URL:}")
    private String apiBaseUrl;

    /** Fallback port used in single-port direct-IP mode (no relay server). */
    @Value("${RELAY_PORT:7777}")
    private int defaultRelayPort;

    /** Number of host-facing upstream ports reserved for each player-hosted relay session. */
    @Value("${RELAY_HOST_UPSTREAMS:4}")
    private int relayHostUpstreams;

    /** sessionToken → RelaySession */
    private final ConcurrentMap<String, RelaySession> sessions = new ConcurrentHashMap<>();

    /** roomId → sessionToken */
    private final ConcurrentMap<Long, String> roomIndex = new ConcurrentHashMap<>();

    private final RestTemplate rest;

    {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        rest = new RestTemplate(factory);
    }

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
        List<Integer> resolvedHostPorts = Collections.emptyList();
        String relayServer = normalizeConfigValue(relayServerUrl);
        String relayHost = normalizeHostValue(configuredRelayHost);

        if (!relayServer.isBlank()) {
            // ── Mode A: Full relay server (works across any network) ─────────
            // Call the relay server's HTTP API to allocate a unique UDP port.
            RelayAllocation allocation = allocatePortFromRelayServer(relayServer, token);
            resolvedHost = resolveClientRelayHost(relayServer);
            if (allocation.port() <= 0) {
                log.error("[Relay] Failed to allocate port from relay server — falling back to default port");
                resolvedPort = defaultRelayPort;
                log.warn("[Relay] Relay fallback: host={}:{} — single concurrent game only", resolvedHost, resolvedPort);
            } else {
                resolvedPort = allocation.port();
                resolvedHostPorts = allocation.hostPorts();
                log.info("[Relay] Mode A (relay server): host={}:{} hostPorts={} session={}", resolvedHost, resolvedPort, resolvedHostPorts, token);
            }
        } else if (!relayHost.isBlank()) {
            // ── Mode B: Static relay host IP (VPS, but no relay server process) ──
            // All sessions share the same port — only works for 1 concurrent game.
            resolvedHost = relayHost;
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

        RelaySession session = buildSession(token, roomId, matchId, now, resolvedHost, resolvedPort, resolvedHostPorts);
        sessions.put(token, session);
        roomIndex.put(roomId, token);
        log.info("[Relay] Session created: token={} room={} match={} relayHost={}:{} hostPorts={}",
                token, roomId, matchId, resolvedHost, resolvedPort, resolvedHostPorts);
        return session;
    }

    /**
     * Call the relay server HTTP API to allocate a unique UDP port for this session.
     *
     * @return allocated relay ports, or port 0 on failure
     */
    private RelayAllocation allocatePortFromRelayServer(String relayServer, String token) {
        try {
            String url  = relayServer.replaceAll("/+$", "") + "/session/create";
            Map<String, Object> body = Map.of(
                    "token", token,
                    "hostUpstreamCount", Math.max(1, relayHostUpstreams));
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = rest.postForObject(url, body, Map.class);
            if (resp != null && resp.containsKey("port")) {
                int port = ((Number) resp.get("port")).intValue();
                return new RelayAllocation(port, parseIntegerList(resp.get("hostPorts")));
            }
        } catch (Exception e) {
            log.error("[Relay] HTTP call to relay server failed: {}", e.getMessage());
        }
        return new RelayAllocation(0, Collections.emptyList());
    }

    private static List<Integer> parseIntegerList(Object value) {
        if (!(value instanceof List<?>)) {
            return Collections.emptyList();
        }
        List<?> raw = (List<?>) value;
        return raw.stream()
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::intValue)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Proxy the relay server's /health endpoint for smoke-test visibility.
     * Returns null when relay.server.url is not configured (LAN/direct mode).
     */
    public Map<String, Object> checkRelayServerHealth() {
        String relayServer = normalizeConfigValue(relayServerUrl);
        if (relayServer.isBlank()) return null;
        try {
            String url = relayServer.replaceAll("/+$", "") + "/health";
            @SuppressWarnings("unchecked")
            Map<String, Object> result = rest.getForObject(url, Map.class);
            return result;
        } catch (Exception e) {
            log.warn("[Relay] Health check failed: {}", e.getMessage());
            return Map.of("status", "unreachable", "error", e.getMessage());
        }
    }

    /**
     * Notify the relay server to close and release the port for this session.
     * Called on match end.
     */
    public void closeRelayServerSession(String token) {
        String relayServer = normalizeConfigValue(relayServerUrl);
        if (relayServer.isBlank()) return;
        try {
            String url = relayServer.replaceAll("/+$", "") + "/session/close";
            rest.postForObject(url, Map.of("token", token), Map.class);
            log.info("[Relay] Relay server session closed: token={}", token);
        } catch (Exception e) {
            log.warn("[Relay] Failed to close relay server session {}: {}", token, e.getMessage());
        }
    }

    private RelaySession buildSession(String token, Long roomId, String matchId,
                                      Instant now, String host, int port,
                                      List<Integer> hostPorts) {
        return RelaySession.builder()
                .sessionToken(token)
                .roomId(roomId)
                .matchId(matchId)
                .relayHost(host)
                .relayPort(port)
                .relayHostPorts(hostPorts == null ? Collections.emptyList() : hostPorts)
                .createdAt(now)
                .expiresAt(now.plus(ttlHours, ChronoUnit.HOURS))
                .started(false)
                .finished(false)
                .build();
    }

    /** Extract host from a URL like http://nighthunt-relay:7776 → "nighthunt-relay" */
    private static String extractHost(String url) {
        String value = normalizeConfigValue(url);
        if (value.isBlank()) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(value);
            String host = uri.getHost();
            if (host != null && !host.isBlank()) {
                return host;
            }
        } catch (Exception ignored) {
            // Fall back to plain host[:port] parsing below.
        }

        int slash = value.indexOf('/');
        String hostPort = slash >= 0 ? value.substring(0, slash) : value;
        int colon = hostPort.indexOf(':');
        return colon >= 0 ? hostPort.substring(0, colon) : hostPort;
    }

    private String resolveClientRelayHost(String relayServer) {
        String explicitHost = firstPublicHost(
                configuredRelayHost,
                vpsPublicIp,
                extractHost(apiBaseUrl));
        if (!explicitHost.isBlank()) {
            return explicitHost;
        }

        String relayServerHost = normalizeHostValue(extractHost(relayServer));
        if (!isInternalHost(relayServerHost)) {
            return relayServerHost;
        }

        throw new IllegalStateException("[Relay] relay.server.url points to internal host '"
                + relayServerHost
                + "' but no public RELAY_HOST/VPS_PUBLIC_IP/API_BASE_URL is configured. "
                + "Clients cannot resolve Docker-only relay hostnames.");
    }

    private static String firstPublicHost(String... candidates) {
        for (String candidate : candidates) {
            String host = normalizeHostValue(candidate);
            if (!host.isBlank() && !isInternalHost(host)) {
                return host;
            }
        }
        return "";
    }

    private static String normalizeHostValue(String value) {
        String normalized = normalizeConfigValue(value);
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            normalized = extractHost(normalized);
        }
        return normalized;
    }

    private static String normalizeConfigValue(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        int inlineComment = trimmed.indexOf(" #");
        if (inlineComment >= 0) {
            trimmed = trimmed.substring(0, inlineComment).trim();
        }
        return trimmed;
    }

    private static boolean isInternalHost(String host) {
        String value = normalizeConfigValue(host).toLowerCase();
        if (value.isBlank()) {
            return true;
        }
        if ("localhost".equals(value) || "0.0.0.0".equals(value) || "::1".equals(value)) {
            return true;
        }
        if (value.startsWith("127.") || value.startsWith("10.") || value.startsWith("192.168.")) {
            return true;
        }
        if (value.matches("^172\\.(1[6-9]|2\\d|3[0-1])\\..*")) {
            return true;
        }
        if (value.endsWith(".local")) {
            return true;
        }
        return !value.contains(".") && !value.contains(":");
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

    private static final class RelayAllocation {
        private final int port;
        private final List<Integer> hostPorts;

        private RelayAllocation(int port, List<Integer> hostPorts) {
            this.port = port;
            this.hostPorts = hostPorts == null ? Collections.emptyList() : hostPorts;
        }

        private int port() {
            return port;
        }

        private List<Integer> hostPorts() {
            return hostPorts;
        }
    }
}
