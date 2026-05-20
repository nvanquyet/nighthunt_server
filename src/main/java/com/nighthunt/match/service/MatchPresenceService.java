package com.nighthunt.match.service;

import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.game.websocket.dto.MatchPresenceNoticeDTO;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.friend.service.PlayerStatusService;
import com.nighthunt.match.adapter.RedisMatchPresenceCache;
import com.nighthunt.match.dto.MatchPresenceRequest;
import com.nighthunt.match.dto.MatchPresenceState;
import com.nighthunt.match.model.MatchPresenceSnapshot;
import com.nighthunt.room.dto.RoomResponse;
import com.nighthunt.room.entity.Room;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import com.nighthunt.room.service.RoomResponseAssembler;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchPresenceService {
    private static final String REASON_TRANSPORT_DROP = "TRANSPORT_DROP";
    private static final String REASON_INTENTIONAL_LEAVE = "INTENTIONAL_LEAVE";
    private static final String REASON_LOGOUT = "LOGOUT";
    private static final String REASON_SESSION_EXPIRED = "SESSION_EXPIRED";
    private static final String REASON_FORCE_LOGOUT = "FORCE_LOGOUT";

    private final RoomRepository roomRepository;
    private final RoomPlayerRepository roomPlayerRepository;
    private final UserRepository userRepository;
    private final ConnectionManager connectionManager;
    private final RoomResponseAssembler roomResponseAssembler;
    private final RedisMatchPresenceCache presenceCache;
    private final PlayerStatusService playerStatusService;
    private final AbandonPenaltyService abandonPenaltyService;

    @Transactional
    public void recordUserPresence(Long actorUserId, MatchPresenceRequest request) {
        if (actorUserId == null) {
            throw new BusinessException(ErrorCodes.AUTH_REQUIRED, "User not authenticated");
        }
        updatePresence(actorUserId, request, actorUserId);
    }

    @Transactional
    public void recordServerPresence(MatchPresenceRequest request) {
        updatePresence(request.getUserId(), request, null);
    }

    @Transactional
    public void recordTransportConnected(Long userId) {
        recordCurrentMatchPresence(userId, MatchPresenceState.CONNECTED, "RECONNECTED");
    }

    @Transactional
    public void recordTransportDisconnected(Long userId, String reason) {
        recordCurrentMatchPresence(userId, MatchPresenceState.DISCONNECTED, reason);
    }

    @Transactional
    public void recordSessionTerminated(Long userId, String reason) {
        recordCurrentMatchPresence(userId, MatchPresenceState.ABANDONED, reason);
    }

    @Transactional
    public void clearMatchPresence(String matchId) {
        if (matchId == null || matchId.isBlank()) {
            return;
        }

        roomRepository.findByMatchId(matchId).ifPresent(room -> {
            List<RoomPlayer> players = roomPlayerRepository.findByRoomId(room.getId());
            for (RoomPlayer player : players) {
                presenceCache.delete(matchId, player.getUserId());
            }
        });
    }

    @Scheduled(fixedDelay = 10_000)
    @Transactional
    public void sweepDisconnectedPlayers() {
        List<Room> activeRooms = roomRepository.findByStatus(GameConstants.ROOM_STATUS_IN_GAME);
        if (activeRooms.isEmpty()) {
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(GameConstants.MATCH_ABANDON_GRACE_SECONDS);

        for (Room room : activeRooms) {
            if (room.getMatchId() == null || room.getMatchId().isBlank()) {
                continue;
            }

            List<RoomPlayer> players = roomPlayerRepository.findByRoomId(room.getId());
            for (RoomPlayer player : players) {
                presenceCache.get(room.getMatchId(), player.getUserId()).ifPresent(snapshot -> {
                    if (snapshot.isAbandoned()) {
                        return;
                    }
                    if (snapshot.getState() != MatchPresenceState.DISCONNECTED) {
                        return;
                    }
                    LocalDateTime reportedAt = snapshot.getDisconnectedAt() != null
                            ? snapshot.getDisconnectedAt()
                            : snapshot.getReportedAt();
                    if (reportedAt == null || reportedAt.isAfter(cutoff)) {
                        return;
                    }
                    markAbandoned(room, player, snapshot, "AFK_TIMEOUT");
                });
            }
        }
    }

    private void recordCurrentMatchPresence(Long userId, MatchPresenceState state, String reason) {
        if (userId == null) {
            return;
        }

        findCurrentInGameContext(userId).ifPresent(context -> {
            try {
                updatePresence(userId, MatchPresenceRequest.builder()
                        .matchId(context.room().getMatchId())
                        .userId(userId)
                        .state(state)
                        .reason(reason)
                        .build(), null);
            } catch (Exception e) {
                log.warn("[MatchPresence] Failed to record {} for userId={} roomId={} matchId={}: {}",
                        state, userId, context.room().getId(), context.room().getMatchId(), e.getMessage());
            }
        });
    }

    private Optional<PresenceContext> findCurrentInGameContext(Long userId) {
        return roomPlayerRepository.findActiveRoomsByUserId(userId).stream()
                .flatMap(player -> roomRepository.findById(player.getRoomId())
                        .filter(room -> GameConstants.ROOM_STATUS_IN_GAME.equals(room.getStatus()))
                        .filter(room -> room.getMatchId() != null && !room.getMatchId().isBlank())
                        .map(room -> new PresenceContext(room, player))
                        .stream())
                .findFirst();
    }

    private void updatePresence(Long userId, MatchPresenceRequest request, Long actorUserId) {
        if (request == null || request.getMatchId() == null || request.getMatchId().isBlank()) {
            throw new BusinessException(ErrorCodes.MATCH_NOT_FOUND, "matchId is required");
        }
        if (userId == null) {
            throw new BusinessException(ErrorCodes.USER_NOT_FOUND, "userId is required");
        }

        Room room = roomRepository.findByMatchId(request.getMatchId())
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "Room not found for matchId: " + request.getMatchId()));

        RoomPlayer player = roomPlayerRepository.findByRoomIdAndUserId(room.getId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_PLAYER_NOT_FOUND,
                        "Player not in room: " + userId));

        if (actorUserId != null && !actorUserId.equals(userId) && !room.getOwnerId().equals(actorUserId)) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_OWNER,
                    "Only room owner can report presence for another player");
        }

        LocalDateTime now = LocalDateTime.now();
        MatchPresenceState state = request.getState() != null ? request.getState() : MatchPresenceState.CONNECTED;
        String reason = normalizeReason(request.getReason());

        MatchPresenceSnapshot snapshot = presenceCache.get(room.getMatchId(), userId)
                .orElseGet(() -> MatchPresenceSnapshot.builder()
                        .matchId(room.getMatchId())
                        .roomId(room.getId())
                        .userId(userId)
                        .displayName(resolveDisplayName(userId))
                        .build());

        snapshot.setMatchId(room.getMatchId());
        snapshot.setRoomId(room.getId());
        snapshot.setUserId(userId);
        snapshot.setDisplayName(resolveDisplayName(userId));
        snapshot.setReason(reason);
        snapshot.setReportedAt(now);

        if (state == MatchPresenceState.CONNECTED) {
            snapshot.setState(MatchPresenceState.CONNECTED);
            snapshot.setDisconnectedAt(null);
            snapshot.setAbandonedAt(null);
            snapshot.setAbandoned(false);
            presenceCache.save(snapshot);
            player.setLastSeenAt(now);
            roomPlayerRepository.save(player);
            connectionManager.updateUserRoom(userId, room.getId());
            broadcastPresence(room, snapshot, "Player reconnected to the match.");
            return;
        }

        if (state == MatchPresenceState.ABANDONED || shouldForceAbandon(reason)) {
            snapshot.setState(MatchPresenceState.ABANDONED);
            snapshot.setDisconnectedAt(now);
            snapshot.setAbandonedAt(now);
            snapshot.setAbandoned(true);
            presenceCache.save(snapshot);
            player.setLastSeenAt(now);
            roomPlayerRepository.save(player);
            markAbandoned(room, player, snapshot, reason);
            return;
        }

        snapshot.setState(MatchPresenceState.DISCONNECTED);
        snapshot.setDisconnectedAt(now);
        snapshot.setAbandoned(false);
        presenceCache.save(snapshot);
        player.setLastSeenAt(now);
        roomPlayerRepository.save(player);
        broadcastPresence(room, snapshot, "Player disconnected. Slot held for "
                + GameConstants.MATCH_ABANDON_GRACE_SECONDS + "s.");
    }

    public boolean hasActiveInGameSlot(Long userId) {
        return userId != null && findCurrentInGameContext(userId).isPresent();
    }

    private void markAbandoned(Room room, RoomPlayer player, MatchPresenceSnapshot snapshot, String reason) {
        if (snapshot == null) {
            return;
        }

        RoomPlayer activePlayer = roomPlayerRepository.findByRoomIdAndUserId(room.getId(), player.getUserId())
                .orElse(null);
        if (activePlayer == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        snapshot.setState(MatchPresenceState.ABANDONED);
        snapshot.setAbandoned(true);
        snapshot.setAbandonedAt(now);
        snapshot.setReportedAt(now);
        snapshot.setReason(reason);
        presenceCache.save(snapshot);

        roomPlayerRepository.deleteByRoomIdAndUserId(room.getId(), player.getUserId());
        connectionManager.sendToUser(player.getUserId(), "you_were_kicked",
                java.util.Map.of(
                        "roomId", room.getId(),
                        "matchId", room.getMatchId(),
                        "reason", "afk_abandoned",
                        "message", "Your slot was released after the reconnect grace window.",
                        "graceSeconds", GameConstants.MATCH_ABANDON_GRACE_SECONDS));

        try {
            playerStatusService.setBackToOnline(player.getUserId());
            playerStatusService.updateCurrentRoom(player.getUserId(), null);
        } catch (Exception e) {
            log.warn("[MatchPresence] Failed to update player status for abandoned user {}: {}",
                    player.getUserId(), e.getMessage());
        }

        // ── ELO penalty: deduct points for AFK/abandon ────────────────────────
        try {
            abandonPenaltyService.applyPenalty(player.getUserId(), room.getMatchId(), room.getId(), reason);
        } catch (Exception e) {
            log.error("[MatchPresence] Failed to apply abandon penalty for userId={} matchId={}: {}",
                    player.getUserId(), room.getMatchId(), e.getMessage());
        }

        RoomResponse roomResponse = roomResponseAssembler.toResponseById(room.getId());
        MatchPresenceNoticeDTO notice = MatchPresenceNoticeDTO.builder()
                .matchId(room.getMatchId())
                .userId(player.getUserId())
                .displayName(snapshot.getDisplayName())
                .state(MatchPresenceState.ABANDONED.name())
                .reason(reason)
                .graceSeconds(GameConstants.MATCH_ABANDON_GRACE_SECONDS)
                .message("Player abandoned the match after the reconnect grace window.")
                .room(roomResponse)
                .build();
        connectionManager.broadcastToRoom(room.getId(), "match_presence_notice", notice);
        connectionManager.updateUserRoom(player.getUserId(), null);

        // ── DS notification: broadcast player_abandoned so dedicated server
        //    can remove the player from its authoritative game state ───────────
        java.util.Map<String, Object> dsPayload = new java.util.HashMap<>();
        dsPayload.put("matchId",     room.getMatchId());
        dsPayload.put("userId",      player.getUserId());
        dsPayload.put("displayName", snapshot.getDisplayName());
        dsPayload.put("reason",      reason);
        connectionManager.broadcastToRoom(room.getId(), "player_abandoned", dsPayload);

        if (roomPlayerRepository.countByRoomId(room.getId()) == 0) {
            room.setStatus(GameConstants.ROOM_STATUS_CLOSED);
            roomRepository.save(room);
            connectionManager.broadcastToRoom(room.getId(), "room_disbanded",
                    java.util.Map.of("roomId", room.getId(), "reason", "match_abandoned"));
        }

        log.info("[MatchPresence] Player {} abandoned match {} (roomId={})",
                player.getUserId(), room.getMatchId(), room.getId());
    }

    private void broadcastPresence(Room room, MatchPresenceSnapshot snapshot, String message) {
        RoomResponse roomResponse = roomResponseAssembler.toResponseById(room.getId());
        MatchPresenceNoticeDTO notice = MatchPresenceNoticeDTO.builder()
                .matchId(room.getMatchId())
                .userId(snapshot.getUserId())
                .displayName(snapshot.getDisplayName())
                .state(snapshot.getState().name())
                .reason(snapshot.getReason())
                .graceSeconds(GameConstants.MATCH_ABANDON_GRACE_SECONDS)
                .message(message)
                .room(roomResponse)
                .build();
        connectionManager.broadcastToRoom(room.getId(), "match_presence_notice", notice);
    }

    private String resolveDisplayName(Long userId) {
        return userRepository.findById(userId)
                .map(User::getUsername)
                .orElse("Player_" + userId);
    }

    private boolean shouldForceAbandon(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        String normalized = reason.trim().toUpperCase(Locale.ROOT);
        return REASON_INTENTIONAL_LEAVE.equals(normalized)
                || REASON_LOGOUT.equals(normalized)
                || REASON_SESSION_EXPIRED.equals(normalized)
                || REASON_FORCE_LOGOUT.equals(normalized);
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return REASON_TRANSPORT_DROP;
        }
        return reason.trim().toUpperCase(Locale.ROOT);
    }

    private record PresenceContext(Room room, RoomPlayer player) {
    }
}
