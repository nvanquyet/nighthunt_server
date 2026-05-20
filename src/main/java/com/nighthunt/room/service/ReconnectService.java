package com.nighthunt.room.service;

import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.match.adapter.RedisMatchSessionCache;
import com.nighthunt.match.dto.MatchPresenceRequest;
import com.nighthunt.match.dto.MatchPresenceState;
import com.nighthunt.match.service.MatchPresenceService;
import com.nighthunt.room.dto.RoomResponse;
import com.nighthunt.room.entity.Room;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import com.nighthunt.security.port.TokenProvider;
import com.nighthunt.session.port.SessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconnectService {
    private final RoomRepository roomRepository;
    private final RoomPlayerRepository roomPlayerRepository;
    private final TokenProvider tokenProvider;
    private final SessionStore sessionStore;
    private final RedisMatchSessionCache matchSessionCache;
    private final RoomService roomService;
    private final MatchPresenceService matchPresenceService;

    @Transactional
    public RoomResponse reconnect(String accessToken, String sessionId, Long roomId) {
        // Validate token
        if (!tokenProvider.validateToken(accessToken)) {
            throw new BusinessException(ErrorCodes.AUTH_TOKEN_INVALID,
                    "Invalid or expired token");
        }

        Long userId = tokenProvider.getUserIdFromToken(accessToken);
        String currentSessionId = sessionStore.getSessionId(String.valueOf(userId));

        // Check session matches
        if (currentSessionId == null || !currentSessionId.equals(sessionId)) {
            throw new BusinessException(ErrorCodes.AUTH_SESSION_EXPIRED,
                    "Session expired or invalid");
        }

        // Check force logout
        if (sessionStore.isForceLogout(String.valueOf(userId))) {
            throw new BusinessException(ErrorCodes.AUTH_FORCE_LOGOUT,
                    "Tài khoản đã đăng nhập ở nơi khác. Vui lòng đăng nhập lại.");
        }

        // Unity JsonUtility cannot omit optional numeric fields, so roomId=0 means "infer it".
        Long requestedRoomId = roomId != null && roomId > 0 ? roomId : null;

        // Find room - try from request, match session cache, then the user's active room.
        // If a requested/cached room is stale or terminal, fall through to the active-room lookup.
        Room room = null;
        if (requestedRoomId != null) {
            room = roomRepository.findById(requestedRoomId)
                    .filter(this::isReconnectable)
                    .orElse(null);
        }

        if (room == null) {
            Object cachedSession = matchSessionCache.getMatchSession(String.valueOf(userId));
            if (cachedSession instanceof MatchSessionData) {
                MatchSessionData sessionData = (MatchSessionData) cachedSession;
                room = roomRepository.findById(sessionData.getRoomId())
                        .filter(this::isReconnectable)
                        .orElse(null);
            }
        }

        if (room == null) {
            room = findActiveRoomForUser(userId);
        }

        if (room == null) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                    "Room not found. Please provide roomId");
        }

        // Check if player slot still exists
        RoomPlayer player = roomPlayerRepository.findByRoomIdAndUserId(room.getId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_PLAYER_NOT_FOUND,
                        "Player slot not found. Room may have been reset."));

        // Update last seen
        player.setLastSeenAt(LocalDateTime.now());
        roomPlayerRepository.save(player);

        if (room.getMatchId() != null && !room.getMatchId().isBlank()) {
            matchPresenceService.recordUserPresence(userId, MatchPresenceRequest.builder()
                    .matchId(room.getMatchId())
                    .userId(userId)
                    .state(MatchPresenceState.CONNECTED)
                    .reason("RECONNECTED")
                    .build());
        }

        // Generate new join token
        String joinToken = UUID.randomUUID().toString();

        // Save match session for future reconnects
        MatchSessionData sessionData = MatchSessionData.builder()
                .roomId(room.getId())
                .matchId(room.getMatchId())
                .playerId(userId)
                .team(player.getTeam())
                .slot(player.getSlot())
                .build();
        matchSessionCache.saveMatchSession(String.valueOf(userId), sessionData,
                GameConstants.RECONNECT_TIMEOUT_SECONDS);

        return roomService.buildRoomResponse(room, joinToken);
    }

    private boolean isReconnectable(Room room) {
        return room != null
                && !GameConstants.ROOM_STATUS_CLOSED.equals(room.getStatus())
                && !GameConstants.ROOM_STATUS_FINISHED.equals(room.getStatus());
    }

    private Room findActiveRoomForUser(Long userId) {
        List<RoomPlayer> activeRoomPlayers = roomPlayerRepository.findActiveRoomsByUserId(userId);
        for (RoomPlayer activeRoomPlayer : activeRoomPlayers) {
            Room room = roomRepository.findById(activeRoomPlayer.getRoomId()).orElse(null);
            if (isReconnectable(room)) {
                return room;
            }
        }

        return null;
    }

    // Inner class for match session data
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class MatchSessionData {
        private Long roomId;
        private String matchId;
        private Long playerId;
        private Integer team;
        private Integer slot;
    }
}

