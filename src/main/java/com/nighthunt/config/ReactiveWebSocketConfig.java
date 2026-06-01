package com.nighthunt.config;

import com.nighthunt.game.websocket.ReactiveGameWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

/**
 * ReactiveWebSocketConfig — registers the reactive WebSocket handler.
 *
 * <p>The existing Tomcat-based REST API ({@code spring-boot-starter-web}) is NOT
 * replaced. This configuration adds a reactive WebSocket route on top so that
 * {@link ReactiveGameWebSocketHandler} handles {@code /ws/game} connections via
 * Reactor Netty's non-blocking I/O, while all HTTP REST endpoints continue to
 * be served by Tomcat's thread-per-request model.</p>
 *
 * <p>Client-facing contract is unchanged: the Unity client still connects to
 * {@code wss://host/ws/game?token=...} — same path, same auth, same JSON format.</p>
 */
@Configuration
public class ReactiveWebSocketConfig {

    /**
     * Maps the {@code /ws/game} path to the reactive WebSocket handler.
     * Order -1 ensures it is evaluated before any MVC handler mappings.
     */
    @Bean
    public HandlerMapping reactiveWebSocketHandlerMapping(ReactiveGameWebSocketHandler handler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of("/ws/game", (WebSocketHandler) handler));
        mapping.setOrder(-1);
        return mapping;
    }

    /**
     * Required adapter that bridges Spring's reactive WebSocket support.
     */
    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
