package com.nighthunt.room.service;

import com.nighthunt.game.websocket.GameWebSocketHandler;
import com.nighthunt.messaging.service.MessageBrokerService;
import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.match.entity.Match;
import com.nighthunt.match.repository.MatchRepository;
import com.nighthunt.room.dto.*;
import com.nighthunt.room.entity.Room;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.entity.SwapRequest;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import com.nighthunt.room.repository.SwapRequestRepository;
import com.nighthunt.room.util.RoomCodeGenerator;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {
    // Note: headlessServerId should be null when creating a room
    // It will be assigned later when a headless server is allocated

    private final RoomRepository roomRepository;
    private final RoomPlayerRepository roomPlayerRepository;
    private final UserRepository userRepository;
    private final MatchRepository matchRepository;
    private final SwapRequestRepository swapRequestRepository;
    private final GameWebSocketHandler gameWebSocketHandler; // Unified WebSocket handler for all events
    private final MessageBrokerService messageBroker; // Message broker for event publishing
    private final Random random = new Random();

    @Transactional
    public RoomResponse createRoom(Long userId, CreateRoomRequest request) {
        // Generate match ID and join token
        String matchId = UUID.randomUUID().toString();
        String joinToken = UUID.randomUUID().toString();

        // Generate unique room code
        String roomCode;
        do {
            roomCode = RoomCodeGenerator.generate();
        } while (roomRepository.findByRoomCode(roomCode).isPresent());

        // Create room
        Room room = Room.builder()
                .roomCode(roomCode)
                .mode(request.getMode())
                .status(GameConstants.ROOM_STATUS_WAITING)
                .isPublic(request.getIsPublic() != null ? request.getIsPublic() : true)
                .isLocked(request.getIsLocked() != null ? request.getIsLocked() : false)
                .password(request.getPassword()) // Optional password
                .ownerId(userId)
                .headlessServerId(null) // Will be assigned when headless server is allocated
                .matchId(matchId)
                .build();
        room = roomRepository.save(room);

        // Create match entity
        // Note: Match also needs headlessServerId, but we'll set it to null for now
        // It will be updated when a headless server is allocated
        Match match = Match.builder()
                .matchId(matchId)
                .roomId(room.getId())
                .headlessServerId(null) // Will be assigned when headless server is allocated
                .status(GameConstants.MATCH_STATUS_LOBBY)
                .build();
        matchRepository.save(match);

        // Add owner as first player
        RoomPlayer ownerPlayer = RoomPlayer.builder()
                .roomId(room.getId())
                .userId(userId)
                .team(GameConstants.TEAM_1)
                .slot(0)
                .isReady(true) // Host auto-ready
                .build();
        roomPlayerRepository.save(ownerPlayer);

        RoomResponse response = buildRoomResponse(room, joinToken);
        
        // Broadcast room update via WebSocket
        User owner = userRepository.findById(userId).orElse(null);
        String username = owner != null ? owner.getUsername() : "Unknown";
        gameWebSocketHandler.broadcastPlayerJoined(room.getId(), userId, username);
        gameWebSocketHandler.updateUserRoom(userId, room.getId());
        
        // Publish event via Message Broker
        messageBroker.publishPlayerJoined(room.getId(), userId, username);
        
        return response;
    }

    @Transactional
    public RoomResponse joinRoomByCode(Long userId, String roomCode, String password) {
        Room room = roomRepository.findByRoomCode(roomCode)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "Room not found"));

        // Check password if room has one
        if (room.getPassword() != null && !room.getPassword().isEmpty()) {
            if (password == null || password.isEmpty() || !room.getPassword().equals(password)) {
                throw new BusinessException(ErrorCodes.ROOM_PASSWORD_INVALID,
                        "Room password is required or incorrect");
            }
        }

        // Check room status
        if (!GameConstants.ROOM_STATUS_WAITING.equals(room.getStatus())) {
            throw new BusinessException(ErrorCodes.ROOM_ALREADY_STARTED,
                    "Room already started");
        }

        // Check if already in room
        if (roomPlayerRepository.findByRoomIdAndUserId(room.getId(), userId).isPresent()) {
            throw new BusinessException(ErrorCodes.ROOM_SLOT_OCCUPIED,
                    "Already in room");
        }

        // Check room capacity based on mode
        int maxPlayers = getMaxPlayersForMode(room.getMode());
        int currentPlayers = roomPlayerRepository.countByRoomId(room.getId());
        if (currentPlayers >= maxPlayers) {
            throw new BusinessException(ErrorCodes.ROOM_FULL,
                    "Room is full");
        }

        // Find available slot
        int team = GameConstants.TEAM_1;
        int slot = 0;
        int team1Count = roomPlayerRepository.countByRoomIdAndTeam(room.getId(), GameConstants.TEAM_1);
        int team2Count = roomPlayerRepository.countByRoomIdAndTeam(room.getId(), GameConstants.TEAM_2);
        
        if (team1Count <= team2Count) {
            team = GameConstants.TEAM_1;
            slot = team1Count;
        } else {
            team = GameConstants.TEAM_2;
            slot = team2Count;
        }

        // Add player
        RoomPlayer player = RoomPlayer.builder()
                .roomId(room.getId())
                .userId(userId)
                .team(team)
                .slot(slot)
                .isReady(room.getOwnerId().equals(userId)) // Auto-ready if owner (e.g., quickPlay create branch)
                .build();
        roomPlayerRepository.save(player);

        // Generate join token
        String joinToken = UUID.randomUUID().toString();

        RoomResponse response = buildRoomResponse(room, joinToken);
        
        // Broadcast player joined event via WebSocket
        User user = userRepository.findById(userId).orElse(null);
        String username = user != null ? user.getUsername() : "Unknown";
        gameWebSocketHandler.broadcastPlayerJoined(room.getId(), userId, username);
        gameWebSocketHandler.updateUserRoom(userId, room.getId());
        
        // Publish event via Message Broker
        messageBroker.publishPlayerJoined(room.getId(), userId, username);
        
        return response;
    }

    @Transactional
    public RoomResponse quickPlay(Long userId, QuickPlayRequest request) {
        // Find available public rooms
        List<Room> availableRooms = roomRepository.findAvailablePublicRooms(GameConstants.ROOM_STATUS_WAITING);
        
        // Filter by mode and not full
        availableRooms = availableRooms.stream()
                .filter(room -> room.getMode().equals(request.getMode()))
                .filter(room -> {
                    int maxPlayers = getMaxPlayersForMode(room.getMode());
                    int currentPlayers = roomPlayerRepository.countByRoomId(room.getId());
                    return currentPlayers < maxPlayers;
                })
                .collect(Collectors.toList());

        if (availableRooms.isEmpty()) {
            // Create new room if none available
            CreateRoomRequest createRequest = new CreateRoomRequest();
            createRequest.setMode(request.getMode());
            createRequest.setIsPublic(true);
            createRequest.setIsLocked(false);
            return createRoom(userId, createRequest);
        }

        // Random select (skip rooms with password for quick play)
        List<Room> roomsWithoutPassword = availableRooms.stream()
                .filter(room -> room.getPassword() == null || room.getPassword().isEmpty())
                .collect(Collectors.toList());
        
        if (roomsWithoutPassword.isEmpty()) {
            // Create new room if all available rooms have password
            CreateRoomRequest createRequest = new CreateRoomRequest();
            createRequest.setMode(request.getMode());
            createRequest.setIsPublic(true);
            createRequest.setIsLocked(false);
            return createRoom(userId, createRequest);
        }
        
        Room selectedRoom = roomsWithoutPassword.get(random.nextInt(roomsWithoutPassword.size()));
        return joinRoomByCode(userId, selectedRoom.getRoomCode(), null);
    }

    @Transactional
    public RoomResponse setReady(Long userId, Long roomId, ReadyRequest request) {
        RoomPlayer player = roomPlayerRepository.findByRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_PLAYER_NOT_FOUND,
                        "Player not in room"));

        player.setIsReady(request.getIsReady() != null ? request.getIsReady() : false);
        roomPlayerRepository.save(player);

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "Room not found"));

        RoomResponse response = buildRoomResponse(room, null);
        
        // Broadcast player ready event via WebSocket
        gameWebSocketHandler.broadcastPlayerReady(roomId, userId, player.getIsReady());
        
        // Publish event via Message Broker
        messageBroker.publishPlayerReady(roomId, userId, player.getIsReady());
        
        return response;
    }

    @Transactional
    public RoomResponse changeTeam(Long userId, Long roomId, ChangeTeamRequest request) {
        RoomPlayer player = roomPlayerRepository.findByRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_PLAYER_NOT_FOUND,
                        "Player not in room"));

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "Room not found"));

        // Check if slot is available
        int team = request.getTeam();
        int slot = request.getSlot();
        
        // Check if slot is occupied by another player
        boolean slotOccupied = roomPlayerRepository.findByRoomIdAndTeam(roomId, team).stream()
                .anyMatch(p -> p.getSlot().equals(slot) && !p.getUserId().equals(userId));

        if (slotOccupied) {
            throw new BusinessException(ErrorCodes.ROOM_SLOT_OCCUPIED,
                    "Slot already occupied");
        }

        // Update player position
        player.setTeam(team);
        player.setSlot(slot);
        player.setIsReady(false); // Reset ready when changing team
        
        try {
            roomPlayerRepository.save(player);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Handle race condition: another player moved to this slot between check and save
            log.warn("Race condition detected: slot ({}, {}) in room {} was occupied by another player", 
                    team, slot, roomId);
            throw new BusinessException(ErrorCodes.ROOM_SLOT_OCCUPIED,
                    "Slot was just occupied by another player. Please try again.");
        }

        RoomResponse response = buildRoomResponse(room, null);
        
        // Broadcast team changed event via WebSocket
        gameWebSocketHandler.broadcastTeamChanged(roomId, userId, team, slot);
        
        return response;
    }

    @Transactional
    public void leaveRoom(Long userId, Long roomId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "Room not found"));

        RoomPlayer player = roomPlayerRepository.findByRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_PLAYER_NOT_FOUND,
                        "Player not in room"));

        // Remove player
        roomPlayerRepository.deleteByRoomIdAndUserId(roomId, userId);
        
        // Broadcast player left event via WebSocket
        gameWebSocketHandler.broadcastPlayerLeft(roomId, userId);
        gameWebSocketHandler.updateUserRoom(userId, null);

        // Handle owner transfer or room disband
        if (room.getOwnerId().equals(userId)) {
            List<RoomPlayer> remainingPlayers = roomPlayerRepository.findByRoomId(roomId);
            if (remainingPlayers.isEmpty()) {
                // Disband room
                disbandRoom(roomId, userId);
            } else {
                // Transfer ownership
                RoomPlayer newOwner = remainingPlayers.get(0);
                room.setOwnerId(newOwner.getUserId());
                roomRepository.save(room);
            }
        }
    }

    @Transactional
    public void kickPlayer(Long roomId, Long ownerId, Long targetUserId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "Room not found"));

        if (!room.getOwnerId().equals(ownerId)) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_OWNER,
                    "Only room owner can kick players");
        }

        if (room.getOwnerId().equals(targetUserId)) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_OWNER,
                    "Cannot kick room owner");
        }

        roomPlayerRepository.deleteByRoomIdAndUserId(roomId, targetUserId);
        
        // Broadcast player left event via WebSocket
        gameWebSocketHandler.broadcastPlayerLeft(roomId, targetUserId);
        gameWebSocketHandler.updateUserRoom(targetUserId, null);
    }

    @Transactional
    public void disbandRoom(Long roomId, Long ownerId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "Room not found"));

        if (!room.getOwnerId().equals(ownerId)) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_OWNER,
                    "Only room owner can disband room");
        }

        // Delete all players
        roomPlayerRepository.deleteByRoomId(roomId);

        // Update room status
        room.setStatus(GameConstants.ROOM_STATUS_CLOSED);
        roomRepository.save(room);
    }

    @Transactional
    public RoomResponse startGame(Long roomId, Long ownerId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "Room not found"));

        if (!room.getOwnerId().equals(ownerId)) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_OWNER,
                    "Only room owner can start game");
        }

        if (!GameConstants.ROOM_STATUS_WAITING.equals(room.getStatus())) {
            throw new BusinessException(ErrorCodes.ROOM_ALREADY_STARTED,
                    "Room already started");
        }

        // Check all players ready
        List<RoomPlayer> players = roomPlayerRepository.findByRoomId(roomId);
        boolean allReady = players.stream().allMatch(RoomPlayer::getIsReady);
        if (!allReady) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_READY,
                    "Not all players are ready");
        }

        // Check enough players for mode
        int currentPlayerCount = players.size();
        int requiredPlayerCount = getMaxPlayersForMode(room.getMode());
        if (currentPlayerCount != requiredPlayerCount) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_ENOUGH_PLAYERS,
                    String.format("Room requires %d players for %s mode, but has %d players",
                            requiredPlayerCount, room.getMode(), currentPlayerCount));
        }

        log.info("Game starting for room {} (code: {}, matchId: {}, mode: {}, players: {})",
                roomId, room.getRoomCode(), room.getMatchId(), room.getMode(), currentPlayerCount);

        // Update room status
        room.setStatus(GameConstants.ROOM_STATUS_IN_GAME);
        roomRepository.save(room);

        RoomResponse response = buildRoomResponse(room, null);
        
        // Broadcast room status changed event via WebSocket
        gameWebSocketHandler.broadcastRoomStatusChanged(roomId, GameConstants.ROOM_STATUS_IN_GAME);
        
        // Publish event via Message Broker
        messageBroker.publishRoomStatusChanged(roomId, room.getStatus(), GameConstants.ROOM_STATUS_IN_GAME);
        
        return response;
    }

    public RoomResponse getRoom(Long roomId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "Room not found"));
        return buildRoomResponse(room, null);
    }

    public RoomResponse buildRoomResponse(Room room, String joinToken) {
        List<RoomPlayer> players = roomPlayerRepository.findByRoomId(room.getId());
        List<RoomPlayerResponse> playerResponses = players.stream()
                .map(p -> {
                    User user = userRepository.findById(p.getUserId()).orElse(null);
                    return RoomPlayerResponse.builder()
                            .userId(p.getUserId())
                            .username(user != null ? user.getUsername() : "Unknown")
                            .team(p.getTeam())
                            .slot(p.getSlot())
                            .isReady(p.getIsReady())
                            .build();
                })
                .collect(Collectors.toList());

        // Placeholder server info (headless server functionality disabled)
        String serverIp = "127.0.0.1";
        int serverPort = 0;

        return RoomResponse.builder()
                .roomId(room.getId())
                .roomCode(room.getRoomCode())
                .mode(room.getMode())
                .status(room.getStatus())
                .isPublic(room.getIsPublic())
                .isLocked(room.getIsLocked())
                .ownerId(room.getOwnerId())
                .serverIp(serverIp)
                .serverPort(serverPort)
                .matchId(room.getMatchId())
                .joinToken(joinToken)
                .players(playerResponses)
                .build();
    }

    private int getMaxPlayersForMode(String mode) {
        return switch (mode) {
            case GameConstants.MODE_2V2 -> 4;
            case GameConstants.MODE_3V3 -> 6;
            case GameConstants.MODE_5V5 -> 10;
            default -> 4;
        };
    }

    /**
     * Get count of rooms waiting for server assignment
     * Used for auto-scaling
     */
    public int getWaitingRoomsCount() {
        return roomRepository.findByStatus(GameConstants.ROOM_STATUS_WAITING).size();
    }

    /**
     * Get count of active rooms (in game)
     */
    public int getActiveRoomsCount() {
        return roomRepository.findByStatus(GameConstants.ROOM_STATUS_IN_GAME).size();
    }

    @Transactional
    public SwapRequestDTO requestSwap(Long userId, Long roomId, Long targetUserId, Integer targetTeam, Integer targetSlot) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "Room not found"));

        RoomPlayer requester = roomPlayerRepository.findByRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_PLAYER_NOT_FOUND,
                        "Requester not in room"));

        // If targetUserId is null, it means swapping with empty slot - just move requester to that slot
        if (targetUserId == null) {
            // Check if slot is available (empty)
            boolean slotOccupied = roomPlayerRepository.findByRoomIdAndTeam(roomId, targetTeam).stream()
                    .anyMatch(p -> p.getSlot().equals(targetSlot) && !p.getUserId().equals(userId));

            if (slotOccupied) {
                throw new BusinessException(ErrorCodes.ROOM_SLOT_OCCUPIED,
                        "Slot is not empty");
            }

            // Move requester to empty slot (no swap request needed)
            requester.setTeam(targetTeam);
            requester.setSlot(targetSlot);
            requester.setIsReady(false); // Reset ready when changing position
            
            try {
                roomPlayerRepository.save(requester);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // Handle race condition: another player moved to this slot between check and save
                log.warn("Race condition detected: slot ({}, {}) in room {} was occupied by another player during swap", 
                        targetTeam, targetSlot, roomId);
                throw new BusinessException(ErrorCodes.ROOM_SLOT_OCCUPIED,
                        "Slot was just occupied by another player. Please try again.");
            }

            // Broadcast team changed event via WebSocket
            gameWebSocketHandler.broadcastTeamChanged(roomId, userId, targetTeam, targetSlot);

            // Return a dummy DTO to indicate success (no actual swap request created)
            SwapRequestDTO dto = new SwapRequestDTO();
            dto.setRequesterId(userId);
            dto.setRequesterTeam(requester.getTeam());
            dto.setRequesterSlot(requester.getSlot());
            dto.setTargetUserId(null);
            dto.setTargetTeam(targetTeam);
            dto.setTargetSlot(targetSlot);
            dto.setStatus("COMPLETED"); // Direct move, no pending request
            return dto;
        }

        // Normal swap with another player
        RoomPlayer target = roomPlayerRepository.findByRoomIdAndUserId(roomId, targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_PLAYER_NOT_FOUND,
                        "Target player not in room"));

        // Check if slot matches target player's current position
        if (!target.getTeam().equals(targetTeam) || !target.getSlot().equals(targetSlot)) {
            throw new BusinessException(ErrorCodes.ROOM_SLOT_OCCUPIED,
                    "Target player position has changed");
        }

        // Check if there's already a pending request
        swapRequestRepository.findPendingRequest(roomId, userId, targetUserId)
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCodes.ROOM_SLOT_OCCUPIED,
                            "Swap request already pending");
                });

        // Create swap request
        SwapRequest swapRequest = SwapRequest.builder()
                .roomId(roomId)
                .requesterId(userId)
                .targetId(targetUserId)
                .requesterTeam(requester.getTeam())
                .requesterSlot(requester.getSlot())
                .targetTeam(targetTeam)
                .targetSlot(targetSlot)
                .status("PENDING")
                .build();

        swapRequest = swapRequestRepository.save(swapRequest);

        // Build DTO
        User requesterUser = userRepository.findById(userId).orElse(null);
        SwapRequestDTO dto = new SwapRequestDTO();
        dto.setRequestId(swapRequest.getId());
        dto.setRequesterId(userId);
        dto.setRequesterUsername(requesterUser != null ? requesterUser.getUsername() : "Unknown");
        dto.setRequesterTeam(requester.getTeam());
        dto.setRequesterSlot(requester.getSlot());
        dto.setTargetUserId(targetUserId);
        dto.setTargetTeam(targetTeam);
        dto.setTargetSlot(targetSlot);
        dto.setStatus("PENDING");

        // Broadcast swap request event via WebSocket
        gameWebSocketHandler.broadcastSwapRequest(roomId, dto.getRequesterId(), dto.getTargetUserId(), dto.getRequestId());

        return dto;
    }

    @Transactional
    public RoomResponse acceptSwapRequest(Long userId, Long roomId, Long requestId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "Room not found"));

        SwapRequest swapRequest = swapRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "Swap request not found"));

        // Verify user is the target
        if (!swapRequest.getTargetId().equals(userId)) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_OWNER,
                    "You are not the target of this swap request");
        }

        if (!"PENDING".equals(swapRequest.getStatus())) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                    "Swap request is no longer pending");
        }
        if (swapRequest.getExpiresAt() != null && swapRequest.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
            swapRequest.setStatus("REJECTED");
            swapRequestRepository.save(swapRequest);
            gameWebSocketHandler.broadcastSwapRequestStatusChanged(roomId, requestId, "REJECTED");
            throw new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                    "Swap request is no longer pending");
        }

        // Get both players
        RoomPlayer requester = roomPlayerRepository.findByRoomIdAndUserId(roomId, swapRequest.getRequesterId())
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_PLAYER_NOT_FOUND,
                        "Requester not in room"));

        RoomPlayer target = roomPlayerRepository.findByRoomIdAndUserId(roomId, swapRequest.getTargetId())
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_PLAYER_NOT_FOUND,
                        "Target not in room"));

        // Verify positions haven't changed
        if (!requester.getTeam().equals(swapRequest.getRequesterTeam()) ||
            !requester.getSlot().equals(swapRequest.getRequesterSlot()) ||
            !target.getTeam().equals(swapRequest.getTargetTeam()) ||
            !target.getSlot().equals(swapRequest.getTargetSlot())) {
            // Positions changed, reject request
            swapRequest.setStatus("REJECTED");
            swapRequestRepository.save(swapRequest);
            throw new BusinessException(ErrorCodes.ROOM_SLOT_OCCUPIED,
                    "Player positions have changed");
        }

        // Perform swap (with race-condition protection and explicit flush)
        int origRequesterTeam = requester.getTeam();
        int origRequesterSlot = requester.getSlot();
        int origTargetTeam = target.getTeam();
        int origTargetSlot = target.getSlot();

        // Use a unique temporary slot value to avoid duplicate constraint
        int tempSlotValue = -requester.getId().intValue(); // negative + unique per requester

        try {
            // Step 1: move requester to a temporary slot to avoid unique constraint clash
            requester.setSlot(tempSlotValue);
            requester.setTeam(origRequesterTeam);
            requester.setIsReady(false);
            roomPlayerRepository.save(requester);
            roomPlayerRepository.flush();

            // Step 2: move target into requester's original slot
            target.setTeam(origRequesterTeam);
            target.setSlot(origRequesterSlot);
            target.setIsReady(false);
            roomPlayerRepository.save(target);
            roomPlayerRepository.flush();

            // Step 3: move requester into target's original slot
            requester.setTeam(origTargetTeam);
            requester.setSlot(origTargetSlot);
            requester.setIsReady(false);
            roomPlayerRepository.save(requester);
            roomPlayerRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            swapRequest.setStatus("REJECTED");
            swapRequestRepository.save(swapRequest);
            throw new BusinessException(ErrorCodes.ROOM_SLOT_OCCUPIED,
                    "Slot was just occupied by another player. Please try again.");
        } catch (org.hibernate.exception.ConstraintViolationException ex) {
            swapRequest.setStatus("REJECTED");
            swapRequestRepository.save(swapRequest);
            throw new BusinessException(ErrorCodes.ROOM_SLOT_OCCUPIED,
                    "Slot was just occupied by another player. Please try again.");
        } catch (jakarta.persistence.PersistenceException ex) {
            Throwable cause = ex.getCause();
            boolean isConstraint = cause instanceof java.sql.SQLIntegrityConstraintViolationException
                    || cause instanceof org.hibernate.exception.ConstraintViolationException;
            if (isConstraint) {
                swapRequest.setStatus("REJECTED");
                swapRequestRepository.save(swapRequest);
                throw new BusinessException(ErrorCodes.ROOM_SLOT_OCCUPIED,
                        "Slot was just occupied by another player. Please try again.");
            }
            throw ex;
        } catch (Exception ex) {
            Throwable cause = ex.getCause();
            boolean isConstraint = cause instanceof java.sql.SQLIntegrityConstraintViolationException
                    || cause instanceof org.hibernate.exception.ConstraintViolationException;
            if (isConstraint) {
                swapRequest.setStatus("REJECTED");
                swapRequestRepository.save(swapRequest);
                throw new BusinessException(ErrorCodes.ROOM_SLOT_OCCUPIED,
                        "Slot was just occupied by another player. Please try again.");
            }
            throw ex;
        }

        // Update swap request status
        swapRequest.setStatus("ACCEPTED");
        swapRequestRepository.save(swapRequest);

        RoomResponse response = buildRoomResponse(room, null);
        
        // Broadcast swap accepted event via WebSocket
        // This will trigger team_changed events for both players
        // Final positions after swap:
        // requester -> origTargetTeam/slot
        // target    -> origRequesterTeam/slot
        gameWebSocketHandler.broadcastTeamChanged(roomId, swapRequest.getRequesterId(),
                requester.getTeam(), requester.getSlot());
        gameWebSocketHandler.broadcastTeamChanged(roomId, swapRequest.getTargetId(),
                target.getTeam(), target.getSlot());
        // Broadcast swap request status
        gameWebSocketHandler.broadcastSwapRequestStatusChanged(roomId, requestId, "ACCEPTED");
        
        return response;
    }

    @Transactional
    public void rejectSwapRequest(Long userId, Long roomId, Long requestId) {
        SwapRequest swapRequest = swapRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "Swap request not found"));

        // Verify user is the target
        if (!swapRequest.getTargetId().equals(userId)) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_OWNER,
                    "You are not the target of this swap request");
        }

        if (!"PENDING".equals(swapRequest.getStatus())) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                    "Swap request is no longer pending");
        }
        if (swapRequest.getExpiresAt() != null && swapRequest.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
            swapRequest.setStatus("REJECTED");
            swapRequestRepository.save(swapRequest);
            gameWebSocketHandler.broadcastSwapRequestStatusChanged(roomId, requestId, "REJECTED");
            throw new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                    "Swap request is no longer pending");
        }

        swapRequest.setStatus("REJECTED");
        swapRequestRepository.save(swapRequest);
        
        // Broadcast swap rejected event via WebSocket
        gameWebSocketHandler.broadcastSwapRequestStatusChanged(roomId, requestId, "REJECTED");
    }

    /**
     * Cancel swap request (requester only)
     */
    @Transactional
    public void cancelSwapRequest(Long userId, Long roomId, Long requestId) {
        SwapRequest swapRequest = swapRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "Swap request not found"));

        // Verify user is the requester
        if (!swapRequest.getRequesterId().equals(userId)) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_OWNER,
                    "You are not the requester of this swap request");
        }

        if (!"PENDING".equals(swapRequest.getStatus())) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                    "Swap request is no longer pending");
        }

        swapRequest.setStatus("REJECTED"); // Use REJECTED status for cancelled requests
        swapRequestRepository.save(swapRequest);
        
        // Broadcast swap cancelled event via WebSocket
        gameWebSocketHandler.broadcastSwapRequestStatusChanged(roomId, requestId, "REJECTED");
    }

    public List<SwapRequestDTO> getPendingSwapRequests(Long userId, Long roomId) {
        List<SwapRequest> requests = swapRequestRepository.findPendingRequestsForUser(userId);
        
        return requests.stream()
                .filter(r -> r.getRoomId().equals(roomId))
                .map(r -> {
                    User requester = userRepository.findById(r.getRequesterId()).orElse(null);
                    SwapRequestDTO dto = new SwapRequestDTO();
                    dto.setRequestId(r.getId());
                    dto.setRequesterId(r.getRequesterId());
                    dto.setRequesterUsername(requester != null ? requester.getUsername() : "Unknown");
                    dto.setRequesterTeam(r.getRequesterTeam());
                    dto.setRequesterSlot(r.getRequesterSlot());
                    dto.setTargetUserId(r.getTargetId());
                    dto.setTargetTeam(r.getTargetTeam());
                    dto.setTargetSlot(r.getTargetSlot());
                    dto.setStatus(r.getStatus());
                    return dto;
                })
                .collect(Collectors.toList());
    }

    /**
     * Transfer room ownership to another player (owner only)
     */
    @Transactional
    public RoomResponse transferOwner(Long ownerId, Long roomId, Long targetUserId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND, "Room not found"));

        // Verify current user is owner
        if (!room.getOwnerId().equals(ownerId)) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_OWNER, "Only room owner can transfer ownership");
        }

        // Verify target user is in the room
        RoomPlayer targetPlayer = roomPlayerRepository.findByRoomIdAndUserId(roomId, targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_PLAYER_NOT_FOUND,
                        "Target user is not in the room"));

        // Cannot transfer to self
        if (ownerId.equals(targetUserId)) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_OWNER, "Cannot transfer ownership to yourself");
        }

        // Transfer ownership
        room.setOwnerId(targetUserId);
        room = roomRepository.save(room);

        // Auto-ready new owner (host), keep others unchanged
        roomPlayerRepository.findByRoomIdAndUserId(roomId, targetUserId).ifPresent(p -> {
            if (!p.getIsReady()) {
                p.setIsReady(true);
                roomPlayerRepository.save(p);
            }
        });

        log.info("Room {} ownership transferred from user {} to user {}", roomId, ownerId, targetUserId);

        RoomResponse response = buildRoomResponse(room, null);
        
        // Broadcast room update via WebSocket (owner changed)
        gameWebSocketHandler.broadcastRoomUpdate(roomId);
        
        return response;
    }

    /**
     * Update room settings (owner only)
     */
    @Transactional
    public RoomResponse updateRoomSettings(Long ownerId, Long roomId, UpdateRoomSettingsRequest request) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND, "Room not found"));

        // Verify owner
        if (!room.getOwnerId().equals(ownerId)) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_OWNER, "Only room owner can update settings");
        }

        // Verify room is in WAITING status
        if (!GameConstants.ROOM_STATUS_WAITING.equals(room.getStatus())) {
            throw new BusinessException(ErrorCodes.ROOM_ALREADY_STARTED, "Cannot update settings when game has started");
        }

        // Update mode if provided
        if (request.getMode() != null && !request.getMode().isEmpty()) {
            // Validate mode
            if (!request.getMode().equals(GameConstants.MODE_2V2) &&
                !request.getMode().equals(GameConstants.MODE_3V3) &&
                !request.getMode().equals(GameConstants.MODE_5V5)) {
                throw new BusinessException(ErrorCodes.ROOM_NOT_FOUND, "Invalid mode. Must be 2v2, 3v3, or 5v5");
            }

            // Check if mode change requires player count adjustment
            int currentMaxPlayers = getMaxPlayersForMode(room.getMode());
            int newMaxPlayers = getMaxPlayersForMode(request.getMode());
            int currentPlayers = roomPlayerRepository.findByRoomId(roomId).size();

            if (newMaxPlayers < currentPlayers) {
                throw new BusinessException(ErrorCodes.ROOM_FULL, 
                    String.format("Không thể đổi sang chế độ %s. Hiện tại có %d người trong phòng, nhưng chế độ %s chỉ cho phép tối đa %d người. Vui lòng yêu cầu một số người chơi rời phòng trước.", 
                        request.getMode(), currentPlayers, request.getMode(), newMaxPlayers));
            }

            room.setMode(request.getMode());
        }

        // Update public/private if provided
        if (request.getIsPublic() != null) {
            room.setIsPublic(request.getIsPublic());
        }

        // Update lock status and password if provided
        if (request.getIsLocked() != null) {
            room.setIsLocked(request.getIsLocked());
            
            // Update password
            if (request.getIsLocked()) {
                // Lock room - set password if provided, otherwise keep current
                if (request.getPassword() != null) {
                    if (request.getPassword().isEmpty()) {
                        // Empty string means remove password (unlock)
                        room.setIsLocked(false);
                        room.setPassword(null);
                    } else {
                        // Set new password
                        room.setPassword(request.getPassword());
                    }
                }
                // If password is null and isLocked is true, keep current password
            } else {
                // Unlock room - remove password
                room.setPassword(null);
            }
        } else if (request.getPassword() != null) {
            // Password provided but isLocked not specified
            if (request.getPassword().isEmpty()) {
                // Remove password
                room.setPassword(null);
                room.setIsLocked(false);
            } else {
                // Set password and lock
                room.setPassword(request.getPassword());
                room.setIsLocked(true);
            }
        }

        room = roomRepository.save(room);
        RoomResponse response = buildRoomResponse(room, null);
        
        // Broadcast room update via WebSocket (settings changed)
        gameWebSocketHandler.broadcastRoomUpdate(roomId);
        
        return response;
    }
}

