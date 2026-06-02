package com.nighthunt.realtime;

import com.nighthunt.friend.service.PlayerStatusService;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.match.service.MatchPresenceService;
import com.nighthunt.matchmaking.service.MatchmakingQueueService;
import com.nighthunt.realtime.dto.GatewayPresenceEvent;
import com.nighthunt.realtime.service.GatewayPresenceEventHandler;
import com.nighthunt.realtime.service.RealtimeRouteStore;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import com.nighthunt.room.service.RoomResponseAssembler;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GatewayPresenceEventHandlerTest {
    @Test
    void connectedEventUpdatesOnlineStateAndSendsSnapshotWhenRouteIsCurrent() {
        RealtimeRouteStore routeStore = mock(RealtimeRouteStore.class);
        PlayerStatusService playerStatusService = mock(PlayerStatusService.class);
        MatchPresenceService matchPresenceService = mock(MatchPresenceService.class);
        MatchmakingQueueService matchmakingQueueService = mock(MatchmakingQueueService.class);
        RoomPlayerRepository roomPlayerRepository = mock(RoomPlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        RoomResponseAssembler roomResponseAssembler = mock(RoomResponseAssembler.class);
        ConnectionManager connectionManager = mock(ConnectionManager.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        GatewayPresenceEventHandler handler = handler(
                routeStore,
                playerStatusService,
                matchPresenceService,
                matchmakingQueueService,
                roomPlayerRepository,
                roomRepository,
                roomResponseAssembler,
                connectionManager,
                transactionTemplate
        );
        GatewayPresenceEvent event = connectedEvent("connection-new");
        when(routeStore.isCurrentConnection(7L, "connection-new")).thenReturn(true);
        when(roomPlayerRepository.findActiveRoomsByUserId(7L)).thenReturn(List.of());

        handler.handleConnected(event);

        verify(playerStatusService).setOnline(7L);
        verify(connectionManager).updateUserRoom(7L, null);
        verify(connectionManager).sendToUser(eq(7L), eq("connected"), any());
        verifyNoInteractions(matchPresenceService, roomRepository, roomResponseAssembler, transactionTemplate);
    }

    @Test
    void staleDisconnectEventDoesNotClearNewerConnectionState() {
        RealtimeRouteStore routeStore = mock(RealtimeRouteStore.class);
        PlayerStatusService playerStatusService = mock(PlayerStatusService.class);
        MatchPresenceService matchPresenceService = mock(MatchPresenceService.class);
        MatchmakingQueueService matchmakingQueueService = mock(MatchmakingQueueService.class);
        RoomPlayerRepository roomPlayerRepository = mock(RoomPlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        RoomResponseAssembler roomResponseAssembler = mock(RoomResponseAssembler.class);
        ConnectionManager connectionManager = mock(ConnectionManager.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        GatewayPresenceEventHandler handler = handler(
                routeStore,
                playerStatusService,
                matchPresenceService,
                matchmakingQueueService,
                roomPlayerRepository,
                roomRepository,
                roomResponseAssembler,
                connectionManager,
                transactionTemplate
        );
        when(routeStore.isRouteMissingOrCurrentConnection(7L, "connection-old")).thenReturn(false);

        handler.handleDisconnected(disconnectedEvent("connection-old"));

        verify(routeStore).isRouteMissingOrCurrentConnection(7L, "connection-old");
        verifyNoInteractions(
                playerStatusService,
                matchPresenceService,
                matchmakingQueueService,
                roomPlayerRepository,
                roomRepository,
                roomResponseAssembler,
                connectionManager,
                transactionTemplate
        );
    }

    @Test
    void currentDisconnectClearsPresenceAndMatchmakingState() {
        RealtimeRouteStore routeStore = mock(RealtimeRouteStore.class);
        PlayerStatusService playerStatusService = mock(PlayerStatusService.class);
        MatchPresenceService matchPresenceService = mock(MatchPresenceService.class);
        MatchmakingQueueService matchmakingQueueService = mock(MatchmakingQueueService.class);
        RoomPlayerRepository roomPlayerRepository = mock(RoomPlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        RoomResponseAssembler roomResponseAssembler = mock(RoomResponseAssembler.class);
        ConnectionManager connectionManager = mock(ConnectionManager.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        GatewayPresenceEventHandler handler = handler(
                routeStore,
                playerStatusService,
                matchPresenceService,
                matchmakingQueueService,
                roomPlayerRepository,
                roomRepository,
                roomResponseAssembler,
                connectionManager,
                transactionTemplate
        );
        when(routeStore.isRouteMissingOrCurrentConnection(7L, "connection-new")).thenReturn(true);
        when(roomPlayerRepository.findByUserId(7L)).thenReturn(List.of());

        handler.handleDisconnected(disconnectedEvent("connection-new"));

        verify(playerStatusService).setOffline(7L);
        verify(connectionManager).updateUserRoom(7L, null);
        verify(matchmakingQueueService).dequeue(7L);
        verify(matchPresenceService).recordTransportDisconnected(7L, "CLIENT_CLOSE");
    }

    private static GatewayPresenceEventHandler handler(
            RealtimeRouteStore routeStore,
            PlayerStatusService playerStatusService,
            MatchPresenceService matchPresenceService,
            MatchmakingQueueService matchmakingQueueService,
            RoomPlayerRepository roomPlayerRepository,
            RoomRepository roomRepository,
            RoomResponseAssembler roomResponseAssembler,
            ConnectionManager connectionManager,
            TransactionTemplate transactionTemplate
    ) {
        return new GatewayPresenceEventHandler(
                routeStore,
                playerStatusService,
                matchPresenceService,
                matchmakingQueueService,
                roomPlayerRepository,
                roomRepository,
                roomResponseAssembler,
                connectionManager,
                transactionTemplate
        );
    }

    private static GatewayPresenceEvent connectedEvent(String connectionId) {
        return new GatewayPresenceEvent(
                "connected",
                7L,
                "gateway-1",
                connectionId,
                "203.0.113.9",
                null,
                "2026-06-02T00:00:00Z"
        );
    }

    private static GatewayPresenceEvent disconnectedEvent(String connectionId) {
        return new GatewayPresenceEvent(
                "disconnected",
                7L,
                "gateway-1",
                connectionId,
                "203.0.113.9",
                "CLIENT_CLOSE",
                "2026-06-02T00:00:01Z"
        );
    }
}
