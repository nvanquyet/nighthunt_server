package com.nighthunt.room.service;

import com.nighthunt.gamemode.service.GameModeService;
import com.nighthunt.room.dto.RoomPlayerResponse;
import com.nighthunt.room.dto.RoomResponse;
import com.nighthunt.room.entity.Room;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Assembles RoomResponse DTOs from Room entities.
 * Extracted from RoomService to eliminate duplication.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomResponseAssembler {

    private final RoomRepository roomRepository;
    private final RoomPlayerRepository roomPlayerRepository;
    private final UserRepository userRepository;
    private final GameModeService gameModeService;

    /**
     * Build a RoomResponse from a Room entity with optional joinToken.
     */
    public RoomResponse toResponse(Room room, String joinToken) {
        List<RoomPlayer> players = roomPlayerRepository.findByRoomId(room.getId());
        List<RoomPlayerResponse> playerResponses = players.stream()
                .map(this::toPlayerResponse)
                .collect(Collectors.toList());

        return RoomResponse.builder()
                .roomId(room.getId())
                .roomCode(room.getRoomCode())
                .mode(room.getMode())
            .mapId(room.getMapId())
                .status(room.getStatus())
                .isPublic(room.getIsPublic())
                .isLocked(room.getIsLocked())
                .ownerId(room.getOwnerId())
                .matchId(room.getMatchId())
                .joinToken(joinToken)
                .players(playerResponses)
                .build();
    }

    /**
     * Build a RoomResponse by roomId. Returns null if room not found.
     */
    public RoomResponse toResponseById(Long roomId) {
        try {
            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null) return null;
            return toResponse(room, null);
        } catch (Exception e) {
            log.error("Error building room response for roomId={}: {}", roomId, e.getMessage());
            return null;
        }
    }

    /**
     * Get max player count for a mode string.
     */
    public int getMaxPlayersForMode(String mode) {
        return gameModeService.getTotalPlayers(mode);
    }

    private RoomPlayerResponse toPlayerResponse(RoomPlayer player) {
        User user = userRepository.findById(player.getUserId()).orElse(null);
        return RoomPlayerResponse.builder()
                .userId(player.getUserId())
                .username(user != null ? user.getUsername() : "Unknown")
                .team(player.getTeam())
                .slot(player.getSlot())
                .isReady(player.getIsReady())
                .build();
    }
}
