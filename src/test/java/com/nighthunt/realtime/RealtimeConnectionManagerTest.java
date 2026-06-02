package com.nighthunt.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nighthunt.realtime.port.RealtimeEventPublisher;
import com.nighthunt.realtime.service.RealtimeConnectionManager;
import com.nighthunt.realtime.service.RealtimeRouteStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeConnectionManagerTest {
    @Test
    void sendToUserPublishesThroughNatsGatewayOnly() {
        RealtimeEventPublisher publisher = mock(RealtimeEventPublisher.class);
        RealtimeRouteStore routes = mock(RealtimeRouteStore.class);
        RealtimeConnectionManager manager = new RealtimeConnectionManager(
                publisher,
                routes,
                new ObjectMapper()
        );

        manager.sendToUser(7L, "match_ready", Map.of("matchId", 99));

        verify(publisher).publishToUser(eq(7L), contains("\"type\":\"match_ready\""));
        verify(publisher).publishToUser(eq(7L), contains("\\\"matchId\\\":99"));
    }

    @Test
    void broadcastToRoomPublishesOneEncodedMessageToRoomRoute() {
        RealtimeEventPublisher publisher = mock(RealtimeEventPublisher.class);
        RealtimeRouteStore routes = mock(RealtimeRouteStore.class);
        RealtimeConnectionManager manager = new RealtimeConnectionManager(
                publisher,
                routes,
                new ObjectMapper()
        );

        manager.broadcastToRoom(55L, "room_updated", Map.of("roomId", 55));

        verify(publisher).publishToRoom(eq(55L), contains("\"type\":\"room_updated\""));
    }

    @Test
    void updateAndConnectionStateUseRedisRouteStore() {
        RealtimeEventPublisher publisher = mock(RealtimeEventPublisher.class);
        RealtimeRouteStore routes = mock(RealtimeRouteStore.class);
        when(routes.countActiveRoutes()).thenReturn(1234);
        when(routes.isUserRouted(7L)).thenReturn(true);
        when(routes.getClientIp(7L)).thenReturn("203.0.113.9");
        RealtimeConnectionManager manager = new RealtimeConnectionManager(
                publisher,
                routes,
                new ObjectMapper()
        );

        manager.updateUserRoom(7L, 99L);

        verify(routes).updateUserRoom(7L, 99L);
        assertThat(manager.getActiveConnectionCount()).isEqualTo(1234);
        assertThat(manager.isUserConnected(7L)).isTrue();
        assertThat(manager.getClientIp(7L)).isEqualTo("203.0.113.9");
    }
}
