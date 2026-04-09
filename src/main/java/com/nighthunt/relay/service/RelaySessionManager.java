package com.nighthunt.relay.service;

import com.nighthunt.relay.model.RelaySession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
     * @param roomId  the room identifier
     * @param matchId the match UUID (pre-allocated by the caller)
     * @return the newly created {@link RelaySession}
     */
    public RelaySession createSession(Long roomId, String matchId) {
        // Clean up any previous session for this room
        String oldToken = roomIndex.remove(roomId);
        if (oldToken != null) {
            sessions.remove(oldToken);
            log.info("[Relay] Replaced old session for room={}", roomId);
        }

        String token = UUID.randomUUID().toString();
        Instant now   = Instant.now();

        RelaySession session = RelaySession.builder()
                .sessionToken(token)
                .roomId(roomId)
                .matchId(matchId)
                .relayHost(configuredRelayHost.isBlank() ? "0.0.0.0" : configuredRelayHost)
                .relayPort(defaultRelayPort)
                .createdAt(now)
                .expiresAt(now.plus(ttlHours, ChronoUnit.HOURS))
                .started(false)
                .finished(false)
                .build();

        sessions.put(token, session);
        roomIndex.put(roomId, token);
        log.info("[Relay] Created session token={} room={} match={}", token, roomId, matchId);
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
