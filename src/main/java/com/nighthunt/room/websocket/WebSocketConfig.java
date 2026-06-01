package com.nighthunt.room.websocket;

import com.nighthunt.game.websocket.GameWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * @deprecated Superseded by {@link com.nighthunt.config.ReactiveWebSocketConfig}.
 *             {@code @Configuration} removed — {@link GameWebSocketHandler} is no longer a
 *             Spring bean and this class must not be processed. Will be deleted in cleanup PR.
 */
// @Configuration — intentionally removed; see ReactiveWebSocketConfig
// @EnableWebSocket — intentionally removed
@Deprecated(since = "phase-1", forRemoval = true)
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {
    private final GameWebSocketHandler gameWebSocketHandler;

    @Value("${app.websocket.allowed-origins:*}")
    private String[] allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gameWebSocketHandler, "/ws/game")
                .setAllowedOriginPatterns(allowedOrigins);
    }
}

