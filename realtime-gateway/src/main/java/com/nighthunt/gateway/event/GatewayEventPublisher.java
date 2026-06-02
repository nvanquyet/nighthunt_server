package com.nighthunt.gateway.event;

public interface GatewayEventPublisher extends AutoCloseable {
    void connected(long userId, String connectionId, String clientIp);

    void disconnected(long userId, String connectionId, String clientIp, String reason);

    @Override
    default void close() throws Exception {
    }
}
