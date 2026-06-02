package com.nighthunt.realtime.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nighthunt.game.websocket.dto.WebSocketMessageDTO;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.realtime.port.RealtimeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Collection;

@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class RealtimeConnectionManager implements ConnectionManager {
    private final RealtimeEventPublisher eventPublisher;
    private final RealtimeRouteStore routeStore;
    private final ObjectMapper objectMapper;

    @Override
    public void sendToUser(Long userId, String eventType, Object data) {
        String message = encode(eventType, data);
        if (message != null) {
            eventPublisher.publishToUser(userId, message);
        }
    }

    @Override
    public void broadcastToRoom(Long roomId, String eventType, Object data) {
        String message = encode(eventType, data);
        if (message != null) {
            eventPublisher.publishToRoom(roomId, message);
        }
    }

    @Override
    public void broadcastToUsers(Collection<Long> userIds, String eventType, Object data) {
        String message = encode(eventType, data);
        if (message == null) {
            return;
        }
        for (Long userId : userIds) {
            eventPublisher.publishToUser(userId, message);
        }
    }

    @Override
    public void updateUserRoom(Long userId, Long roomId) {
        routeStore.updateUserRoom(userId, roomId);
    }

    @Override
    public int getActiveConnectionCount() {
        return routeStore.countActiveRoutes();
    }

    @Override
    public boolean isUserConnected(Long userId) {
        return routeStore.isUserRouted(userId);
    }

    @Override
    public String getClientIp(Long userId) {
        return routeStore.getClientIp(userId);
    }

    private String encode(String eventType, Object data) {
        try {
            String dataJson = objectMapper.writeValueAsString(data);
            return objectMapper.writeValueAsString(WebSocketMessageDTO.builder()
                    .type(eventType)
                    .data(dataJson)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to encode realtime event {}: {}", eventType, e.getMessage());
            return null;
        }
    }
}
