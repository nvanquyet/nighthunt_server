package com.nighthunt.gateway.connection;

import com.nighthunt.gateway.event.GatewayEventPublisher;
import com.nighthunt.gateway.metrics.GatewayMetrics;
import com.nighthunt.gateway.redis.PresenceStore;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ConnectionRegistry {
    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    private final Map<Long, ClientConnection> byUserId = new ConcurrentHashMap<>();
    private final PresenceStore presenceStore;
    private final GatewayEventPublisher eventPublisher;
    private final GatewayMetrics metrics;
    private final long maxPendingOutboundBytes;

    public ConnectionRegistry(
            PresenceStore presenceStore,
            GatewayEventPublisher eventPublisher,
            GatewayMetrics metrics,
            long maxPendingOutboundBytes
    ) {
        this.presenceStore = presenceStore;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.maxPendingOutboundBytes = maxPendingOutboundBytes;
    }

    public void register(long userId, String connectionId, String clientIp, Channel channel) {
        ClientConnection next = new ClientConnection(userId, connectionId, clientIp, channel);
        ClientConnection previous = byUserId.put(userId, next);
        if (previous != null && previous.channel() != channel) {
            previous.channel().close();
        }
        refresh(next).thenRun(() -> eventPublisher.connected(userId, connectionId, clientIp))
                .exceptionally(error -> {
                    log.warn("Failed to refresh presence or publish connected event for userId={}: {}",
                            userId, error.getMessage());
                    return null;
                });
        metrics.accepted();
    }

    public void refresh(long userId, Channel channel) {
        ClientConnection current = byUserId.get(userId);
        if (current != null && current.channel() == channel) {
            refresh(current).exceptionally(error -> {
                log.warn("Failed to refresh presence for userId={}: {}",
                        current.userId(), error.getMessage());
                return null;
            });
        }
    }

    public void unregister(long userId, Channel channel, String reason) {
        ClientConnection current = byUserId.get(userId);
        if (current == null || current.channel() != channel || !byUserId.remove(userId, current)) {
            return;
        }
        presenceStore.release(userId, current.connectionId())
                .thenRun(() -> eventPublisher.disconnected(
                        userId,
                        current.connectionId(),
                        current.clientIp(),
                        reason
                ))
                .exceptionally(error -> {
                    log.warn("Failed to release presence or publish disconnect for userId={}: {}",
                            userId, error.getMessage());
                    return null;
                });
    }

    public boolean send(long userId, String payload) {
        ClientConnection connection = byUserId.get(userId);
        if (connection == null) {
            return false;
        }
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        long pending = connection.pendingOutboundBytes().addAndGet(bytes.length);
        if (pending > maxPendingOutboundBytes || !connection.channel().isWritable()) {
            metrics.slowConsumer();
            connection.channel().close();
            return false;
        }

        connection.channel().writeAndFlush(new TextWebSocketFrame(payload)).addListener(future -> {
            connection.pendingOutboundBytes().addAndGet(-bytes.length);
            if (!future.isSuccess()) {
                connection.channel().close();
            }
        });
        metrics.outboundFrame();
        return true;
    }

    public int size() {
        return byUserId.size();
    }

    private java.util.concurrent.CompletionStage<Void> refresh(ClientConnection connection) {
        return presenceStore.refresh(connection.userId(), connection.connectionId(), connection.clientIp());
    }

    private record ClientConnection(
            long userId,
            String connectionId,
            String clientIp,
            Channel channel,
            AtomicLong pendingOutboundBytes
    ) {
        private ClientConnection(long userId, String connectionId, String clientIp, Channel channel) {
            this(userId, connectionId, clientIp, channel, new AtomicLong());
        }
    }
}
