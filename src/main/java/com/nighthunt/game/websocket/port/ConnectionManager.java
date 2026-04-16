package com.nighthunt.game.websocket.port;

/**
 * Port (interface) for managing real-time client connections.
 * Decouples business logic from the WebSocket transport layer.
 * Implementation can be WebSocket, SSE, or any other protocol.
 */
public interface ConnectionManager {

    /**
     * Send a typed message to a specific user.
     */
    void sendToUser(Long userId, String eventType, Object data);

    /**
     * Broadcast a typed message to all users in a room.
     */
    void broadcastToRoom(Long roomId, String eventType, Object data);

    /**
     * Track which room a user is currently in (for broadcast routing).
     * Pass null roomId to clear the mapping (user left room).
     */
    void updateUserRoom(Long userId, Long roomId);

    /**
     * Get count of active WebSocket connections.
     */
    int getActiveConnectionCount();

    /**
     * Check if a user currently has an active (open) WebSocket connection.
     * Used by AuthService to distinguish live sessions from orphaned ones
     * (client crashed / closed without logout).
     */
    boolean isUserConnected(Long userId);

    /**
     * Returns the remote IP address (host string only, no port) of the user's
     * current WebSocket connection, or {@code null} if not connected.
     *
     * <p>Used by Custom-game relay to determine the host player's reachable IP
     * so non-host players can connect directly (direct P2P mode).</p>
     *
     * <p>Behind a reverse proxy: Spring resolves the real IP from
     * {@code X-Forwarded-For} when {@code forward-headers-strategy=native} is set.</p>
     */
    String getClientIp(Long userId);
}
