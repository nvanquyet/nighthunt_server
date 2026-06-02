package com.nighthunt.realtime.service;

import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.friend.service.PlayerStatusService;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.match.service.MatchPresenceService;
import com.nighthunt.matchmaking.service.MatchmakingQueueService;
import com.nighthunt.realtime.dto.GatewayPresenceEvent;
import com.nighthunt.room.dto.RoomResponse;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import com.nighthunt.room.service.RoomResponseAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayPresenceEventHandler {
    private final RealtimeRouteStore routeStore;
    private final PlayerStatusService playerStatusService;
    private final MatchPresenceService matchPresenceService;
    private final MatchmakingQueueService matchmakingQueueService;
    private final RoomPlayerRepository roomPlayerRepository;
    private final RoomRepository roomRepository;
    private final RoomResponseAssembler roomResponseAssembler;
    private final ConnectionManager connectionManager;
    private final TransactionTemplate transactionTemplate;

    @Async("wsEventExecutor")
    public void handleConnected(GatewayPresenceEvent event) {
        if (!isCurrent(event)) {
            return;
        }

        safeSetOnline(event.userId());
        Long roomId = findCurrentRoomId(event.userId());
        connectionManager.updateUserRoom(event.userId(), roomId);

        if (roomId != null) {
            safeRecordTransportConnected(event.userId());
        }

        connectionManager.sendToUser(event.userId(), "connected", Map.of(
                "userId", event.userId(),
                "roomId", roomId == null ? 0L : roomId,
                "message", "Realtime gateway connected"
        ));

        if (roomId != null) {
            RoomResponse roomResponse = roomResponseAssembler.toResponseById(roomId);
            if (roomResponse != null
                    && !GameConstants.ROOM_STATUS_CLOSED.equals(roomResponse.getStatus())
                    && !GameConstants.ROOM_STATUS_FINISHED.equals(roomResponse.getStatus())) {
                connectionManager.sendToUser(event.userId(), "room_updated", roomResponse);
            }
        }
    }

    @Async("wsEventExecutor")
    public void handleDisconnected(GatewayPresenceEvent event) {
        if (!isCurrentOrAlreadyReleased(event)) {
            return;
        }

        safeSetOffline(event.userId());
        connectionManager.updateUserRoom(event.userId(), null);
        safeDequeue(event.userId());
        safeRecordTransportDisconnected(event.userId(), normalizeReason(event.reason()));
        cleanupWaitingRoomPlayer(event.userId());
    }

    private boolean isCurrent(GatewayPresenceEvent event) {
        if (event == null || !event.hasIdentity()) {
            return false;
        }
        if (!routeStore.isCurrentConnection(event.userId(), event.connectionId())) {
            log.debug("Ignoring stale gateway connect event userId={} connectionId={}",
                    event.userId(), event.connectionId());
            return false;
        }
        return true;
    }

    private boolean isCurrentOrAlreadyReleased(GatewayPresenceEvent event) {
        if (event == null || !event.hasIdentity()) {
            return false;
        }
        if (!routeStore.isRouteMissingOrCurrentConnection(event.userId(), event.connectionId())) {
            log.debug("Ignoring stale gateway disconnect event userId={} connectionId={}",
                    event.userId(), event.connectionId());
            return false;
        }
        return true;
    }

    private Long findCurrentRoomId(Long userId) {
        return roomPlayerRepository.findActiveRoomsByUserId(userId).stream()
                .findFirst()
                .map(RoomPlayer::getRoomId)
                .orElse(null);
    }

    private void cleanupWaitingRoomPlayer(Long userId) {
        try {
            roomPlayerRepository.findByUserId(userId).stream()
                    .findFirst()
                    .ifPresent(roomPlayer -> roomRepository.findById(roomPlayer.getRoomId()).ifPresent(room -> {
                        if (!GameConstants.ROOM_STATUS_WAITING.equals(room.getStatus())) {
                            return;
                        }
                        transactionTemplate.executeWithoutResult(status ->
                                roomPlayerRepository.deleteByRoomIdAndUserId(room.getId(), userId));

                        RoomResponse response = roomResponseAssembler.toResponseById(room.getId());
                        if (response != null) {
                            connectionManager.broadcastToRoom(room.getId(), "player_left",
                                    Map.of("userId", userId, "room", response));
                        }
                        log.info("Removed disconnected userId={} from WAITING room {}", userId, room.getId());
                    }));
        } catch (Exception error) {
            log.warn("Error cleaning up room for userId={}: {}", userId, error.getMessage());
        }
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "TRANSPORT_DROP" : reason;
    }

    private void safeSetOnline(Long userId) {
        try {
            playerStatusService.setOnline(userId);
        } catch (Exception error) {
            log.error("setOnline failed for userId={}: {}", userId, error.getMessage());
        }
    }

    private void safeSetOffline(Long userId) {
        try {
            playerStatusService.setOffline(userId);
        } catch (Exception error) {
            log.error("setOffline failed for userId={}: {}", userId, error.getMessage());
        }
    }

    private void safeDequeue(Long userId) {
        try {
            matchmakingQueueService.dequeue(userId);
        } catch (Exception error) {
            log.debug("Dequeue on disconnect for userId={}: {}", userId, error.getMessage());
        }
    }

    private void safeRecordTransportConnected(Long userId) {
        try {
            matchPresenceService.recordTransportConnected(userId);
        } catch (Exception error) {
            log.warn("recordTransportConnected failed userId={}: {}", userId, error.getMessage());
        }
    }

    private void safeRecordTransportDisconnected(Long userId, String reason) {
        try {
            matchPresenceService.recordTransportDisconnected(userId, reason);
        } catch (Exception error) {
            log.warn("recordTransportDisconnected failed userId={} reason={}: {}",
                    userId, reason, error.getMessage());
        }
    }
}
