package com.nighthunt.relay.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory representation of one Mini-Relay session that proxies UDP traffic
 * between clients for a Custom-mode lobby.
 *
 * <p>Each session is bound to exactly one room. The backend does <em>not</em>
 * own the actual UDP sockets — a sidecar relay process (or the game server's
 * built-in Tugboat relay) handles them. This object tracks who is in the
 * session and when it expires so the backend can issue tokens and perform
 * cleanup.</p>
 */
@Data
@Builder
public class RelaySession {

    /** Globally unique token clients embed in every UDP packet header. */
    private String sessionToken;

    /** The room this relay session belongs to. */
    private Long roomId;

    /** Match identifier (UUID) assigned when the room starts. */
    private String matchId;

    /** IP address of the relay host (may be a DS allocation or the host's public IP). */
    private String relayHost;

    /** UDP join port that clients connect to. */
    private int relayPort;

    /** When this session was created. */
    private Instant createdAt;

    /**
     * TTL deadline — session auto-expires if not refreshed.
     * Default: createdAt + 2 hours.
     */
    private Instant expiresAt;

    /** Player user-IDs currently connected to this relay. */
    @Builder.Default
    private Set<Long> connectedPlayers = ConcurrentHashMap.newKeySet();

    /** True once the host signals that the match has started. */
    private volatile boolean started;

    /** True once the match has ended and the session is sealed. */
    private volatile boolean finished;

    // ── helpers ──────────────────────────────────────────────────────────────

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public void addPlayer(Long userId) {
        connectedPlayers.add(userId);
    }

    public void removePlayer(Long userId) {
        connectedPlayers.remove(userId);
    }

    public int getPlayerCount() {
        return connectedPlayers.size();
    }
}
