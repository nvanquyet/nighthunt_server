package com.nighthunt.room.websocket;

import com.nighthunt.game.websocket.GameWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {
    private final GameWebSocketHandler gameWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Unified Game WebSocket - connected after login, handles all events (session + room)
        registry.addHandler(gameWebSocketHandler, "/ws/game")
                .setAllowedOriginPatterns("*");
        
        // Note: Do NOT use .withSockJS() - Unity uses native WebSocket, not SockJS
    }
}
