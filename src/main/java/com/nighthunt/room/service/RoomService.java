package com.nighthunt.room.service;

import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.messaging.service.MessageBrokerService;
import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.gamemode.service.GameModeService;
import com.nighthunt.match.entity.Match;
import com.nighthunt.match.repository.MatchRepository;
import com.nighthunt.matchmaking.repository.MatchmakingEntryRepository;
import com.nighthunt.match.dto.MatchPresenceRequest;
import com.nighthunt.match.dto.MatchPresenceState;
import com.nighthunt.match.service.MatchPresenceService;
import com.nighthunt.friend.service.PlayerStatusService;
import com.nighthunt.relay.model.RelaySession;
import com.nighthunt.relay.service.RelaySessionManager;
import com.nighthunt.party.service.PartyCustomModeService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    private static final String DEFAULT_MAP_ID = "map_01";

    private final RoomRepository roomRepository;
    private final RoomPlayerRepository roomPlayerRepository;
    private final UserRepository userRepository;
    private final MatchRepository matchRepository;
    private final MatchmakingEntryRepository matchmakingEntryRepository;
    private final SwapRequestRepository swapRequestRepository;
    private final ConnectionManager connectionManager;
    private final MessageBrokerService messageBroker;
    private final RoomResponseAssembler roomResponseAssembler;
    private final TeamSlotAllocator teamSlotAllocator;
    private final RelaySessionManager relaySessionManager;
    private final PartyCustomModeService partyCustomModeService;
    private final GameModeService gameModeService;
    private final PlayerStatusService playerStatusService;
    private final MatchPresenceService matchPresenceService;
    private final SecureRandom random = new SecureRandom();
    // BCrypt(10) — same work-factor as DedicatedServerService
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(10);

    // Self-injection for quickPlay: allows joinRoomByCode/createRoom to run in separate transactions
    @Autowired @Lazy
    private RoomService self;

    @Transactional
    public RoomResponse createRoom(Long userId, CreateRoomRequest request) {
        // Validate game mode upfront so invalid modes are rejected at creation time
        gameModeService.validateModeOrThrow(request.getMode());

        if (roomPlayerRepository.existsUserInActiveRoom(userId)) {
            throw new BusinessException(ErrorCodes.ROOM_ALREADY_IN_ROOM,
                    "You are already in an active room. Leave it first.");
        }
        ensureUserNotInMatchmakingQueue(userId);

        // Generate match ID and join token
        String matchId = UUID.randomUUID().toString();
        String joinToken = UUID.randomUUID().toString();

        // Generate unique room code
        String roomCode;
        do {
            roomCode = RoomCodeGenerator.generate();
        } while (roomRepository.findByRoomCode(roomCode).isPresent());

        // Create room — hash password before persisting (never store plain text)
        boolean locked = Boolean.TRUE.equals(request.getIsLocked())
                || (request.getPassword() != null && !request.getPassword().isBlank());
        String hashedPassword = resolveCreateRoomPassword(locked, request.getPassword());
        Room room = Room.builder()
                .roomCode(roomCode)
                .mode(request.getMode())
            .mapId(normalizeMapId(request.getMapId()))
                .status(GameConstants.ROOM_STATUS_WAITING)
                .isPublic(request.getIsPublic() != null ? request.getIsPublic() : true)
                .isLocked(locked)
                .password(hashedPassword)
                .ownerId(userId)
                .matchId(matchId)
                .build();
        room = roomRepository.save(room);

        // Create match entity
        Match match = Match.builder()
                .matchId(matchId)
                .roomId(room.getId())
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

        RoomResponse response = roomResponseAssembler.toResponse(room, joinToken);
        
        // Broadcast room update via WebSocket
        User owner = userRepository.findById(userId).orElse(null);
        String username = owner != null ? owner.getUsername() : "Unknown";
        connectionManager.updateUserRoom(userId, room.getId());
        updateCurrentRoomStatus(userId, room.getId());
        connectionManager.sendToUser(userId, "player_joined",
                java.util.Map.of("userId", userId, "username", username, "room", response));
        
        // Publish event via Message Broker
        messageBroker.publishPlayerJoined(room.getId(), userId, username);
        
        return response;
    }

    @Transactional
    public RoomResponse joinRoomByCode(Long userId, String roomCode, String password) {
        if (roomPlayerRepository.existsUserInActiveRoom(userId)) {
            throw new BusinessException(ErrorCodes.ROOM_ALREADY_IN_ROOM,
                    "You are already in an active room. Leave it first.");
        }
        ensureUserNotInMatchmakingQueue(userId);

        Room room = roomRepository.findByRoomCode(roomCode)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "Room not found"));

        verifyRoomPassword(room, password);

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
        int maxPlayers = gameModeService.getTotalPlayers(room.getMode());
        int currentPlayers = roomPlayerRepository.countByRoomId(room.getId());
        if (currentPlayers >= maxPlayers) {
            throw new BusinessException(ErrorCodes.ROOM_FULL,
                    "Room is full");
        }

        // Find available slot
        int[] allocation = teamSlotAllocator.allocate(room.getId(), gameModeService.getPlayersPerTeam(room.getMode()));
        int team = allocation[0];
        int slot = allocation[1];

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

        RoomResponse response = roomResponseAssembler.toResponse(room, joinToken);
        
        // Broadcast player joined event via WebSocket
        User user = userRepository.findById(userId).orElse(null);
        String username = user != null ? user.getUsername() : "Unknown";
        connectionManager.updateUserRoom(userId, room.getId());
        updateCurrentRoomStatus(userId, room.getId());
        connectionManager.broadcastToRoom(room.getId(), "player_joined",
                java.util.Map.of("userId", userId, "username", username, "room", response));
        
        // Publish event via Message Broker
        messageBroker.publishPlayerJoined(room.getId(), userId, username);
        
        return response;
    }

    public RoomResponse quickPlay(Long userId, QuickPlayRequest request) {
        if (roomPlayerRepository.existsUserInActiveRoom(userId)) {
            throw new BusinessException(ErrorCodes.ROOM_ALREADY_IN_ROOM,
                    "You are already in an active room. Leave it first.");
        }
        ensureUserNotInMatchmakingQueue(userId);

        // Find available public rooms that are not locked and do not have a password.
        List<Room> availableRooms = roomRepository.findQuickJoinRooms(GameConstants.ROOM_STATUS_WAITING);
        
        // Filter by mode and not full
        availableRooms = availableRooms.stream()
                // Defense in depth: never quick-join a locked/private room even if a stale
                // repository implementation or inconsistent row returns it.
                .filter(room -> Boolean.TRUE.equals(room.getIsPublic()))
                .filter(room -> !isRoomPasswordProtected(room))
                .filter(room -> room.getMode().equals(request.getMode()))
                .filter(room -> request.getMapId() == null || request.getMapId().isBlank()
                        || request.getMapId().equals(room.getMapId()))
                .filter(room -> {
                    int maxPlayers = gameModeService.getTotalPlayers(room.getMode());
                    int currentPlayers = roomPlayerRepository.countByRoomId(room.getId());
                    return currentPlayers < maxPlayers;
                })
                .collect(Collectors.toList());

        if (availableRooms.isEmpty()) {
            // Create new room if none available
            CreateRoomRequest createRequest = new CreateRoomRequest();
            createRequest.setMode(request.getMode());
            createRequest.setMapId(request.getMapId());
            createRequest.setIsPublic(true);
            createRequest.setIsLocked(false);
            return self.createRoom(userId, createRequest);
        }

        Room selectedRoom = availableRooms.get(random.nextInt(availableRooms.size()));
        try {
            return self.joinRoomByCode(userId, selectedRoom.getRoomCode(), null);
        } catch (DataIntegrityViolationException ex) {
            // Slot was taken by a concurrent request — create a fresh room instead
            log.warn("[QuickPlay] Slot conflict joining room {} for user {} — creating new room", selectedRoom.getId(), userId);
            CreateRoomRequest createRequest = new CreateRoomRequest();
            createRequest.setMode(request.getMode());
            createRequest.setMapId(request.getMapId());
            createRequest.setIsPublic(true);
            createRequest.setIsLocked(false);
            return self.createRoom(userId, createRequest);
        }
    }

    public RoomListResponse listPublicCustomRooms(String mode, String mapId, Integer requestedLimit) {
        int limit = requestedLimit == null ? 20 : Math.max(1, Math.min(20, requestedLimit));
        String modeFilter = normalizeOptionalFilter(mode);
        String mapFilter = normalizeOptionalFilter(mapId);

        List<RoomListItemResponse> rooms = roomRepository
                .findPublicWaitingRooms(GameConstants.ROOM_STATUS_WAITING, modeFilter, mapFilter)
                .stream()
                .filter(room -> !isRoomFull(room))
                .map(this::toRoomListItem)
                .collect(Collectors.toList());

        if (rooms.size() > limit) {
            Collections.shuffle(rooms, random);
            rooms = rooms.subList(0, limit);
        }

        return RoomListResponse.builder()
                .rooms(rooms)
                .build();
    }

    /**
     * Create a private, locked room for a ranked match group.
     * All players are auto-added and auto-readied. No relay session is created
     * (DS handles the connection). Called by MatchmakingQueueService when all players accept.
     */
    @Transactional
    public RoomResponse createRankedRoom(List<Long> userIds, String gameMode, String mapId) {
        return createRankedRoom(userIds, gameMode, mapId, Collections.emptyMap());
    }

    @Transactional
    public RoomResponse createRankedRoom(List<Long> userIds, String gameMode, String mapId, Map<Long, Integer> teamByUserId) {
        gameModeService.validateModeOrThrow(gameMode);

        String matchId = UUID.randomUUID().toString();
        String roomCode;
        do {
            roomCode = RoomCodeGenerator.generate();
        } while (roomRepository.findByRoomCode(roomCode).isPresent());

        String resolvedMapId = (mapId != null && !mapId.isBlank()) ? mapId : DEFAULT_MAP_ID;
        Room room = Room.builder()
                .roomCode(roomCode)
                .mode(gameMode)
            .mapId(resolvedMapId)
                .status(GameConstants.ROOM_STATUS_WAITING)
                .isPublic(false)
                .isLocked(true)
                .ownerId(userIds.get(0))
                .matchId(matchId)
                .build();
        room = roomRepository.save(room);

        Match match = Match.builder()
                .matchId(matchId)
                .roomId(room.getId())
                .status(GameConstants.MATCH_STATUS_LOBBY)
                .build();
        matchRepository.save(match);

        int playersPerTeam = gameModeService.getPlayersPerTeam(gameMode);
        Map<Integer, Integer> nextSlotByTeam = new HashMap<>();
        for (Long uid : userIds) {
            Integer assignedTeam = teamByUserId != null ? teamByUserId.get(uid) : null;
            int team;
            int slot;
            if (assignedTeam != null) {
                team = assignedTeam;
                slot = nextSlotByTeam.getOrDefault(team, 0);
                if (slot >= playersPerTeam) {
                    throw new BusinessException(ErrorCodes.ROOM_FULL,
                            "Assigned ranked team is full for mode " + gameMode);
                }
                nextSlotByTeam.put(team, slot + 1);
            } else {
                int[] alloc = teamSlotAllocator.allocate(room.getId(), playersPerTeam);
                team = alloc[0];
                slot = alloc[1];
            }

            RoomPlayer rp = RoomPlayer.builder()
                    .roomId(room.getId())
                    .userId(uid)
                    .team(team)
                    .slot(slot)
                    .isReady(true) // auto-ready in ranked
                    .build();
            roomPlayerRepository.save(rp);
            connectionManager.updateUserRoom(uid, room.getId());
            updateCurrentRoomStatus(uid, room.getId());
        }

        log.info("[RankedMM] Created ranked room {} (mode={}, players={})", roomCode, gameMode, userIds.size());
        return roomResponseAssembler.toResponse(room, null);
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

        RoomResponse response = roomResponseAssembler.toResponse(room, null);
        
        // Broadcast player ready event via WebSocket
        connectionManager.broadcastToRoom(roomId, "player_ready",
                java.util.Map.of("userId", userId, "isReady", player.getIsReady(), "room", response));
        
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

        int slotsPerTeam = gameModeService.getPlayersPerTeam(room.getMode());
        if ((team != GameConstants.TEAM_1 && team != GameConstants.TEAM_2)
                || slot < 0
                || slot >= slotsPerTeam) {
            throw new BusinessException(ErrorCodes.ROOM_SLOT_OCCUPIED,
                    "Invalid slot for current room mode");
        }
        
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
        setReadyAfterPositionChange(room, player);
        
        try {
            roomPlayerRepository.save(player);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Handle race condition: another player moved to this slot between check and save
            log.warn("Race condition detected: slot ({}, {}) in room {} was occupied by another player", 
                    team, slot, roomId);
            throw new BusinessException(ErrorCodes.ROOM_SLOT_OCCUPIED,
                    "Slot was just occupied by another player. Please try again.");
        }

        RoomResponse response = roomResponseAssembler.toResponse(room, null);

        // Build explicit payload so the client knows WHICH player moved WHERE.
        // Bare RoomResponse alone forces the client to diff the entire player list
        // to discover what changed; provide userId/newTeam/newSlot up-front.
        java.util.Map<String, Object> teamChangedPayload = new java.util.HashMap<>();
        teamChangedPayload.put("userId",   userId);
        teamChangedPayload.put("newTeam",  team);
        teamChangedPayload.put("newSlot",  slot);
        teamChangedPayload.put("room",     response);

        try {
            connectionManager.broadcastToRoom(roomId, "team_changed", teamChangedPayload);
            log.info("[Room] Broadcast team_changed: room={} userId={} team={} slot={}",
                    roomId, userId, team, slot);
        } catch (Exception e) {
            log.error("Failed to broadcast team_changed for room {}: {}", roomId, e.getMessage());
        }

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

        if (GameConstants.ROOM_STATUS_IN_GAME.equals(room.getStatus())) {
            // Record abandonment for penalty tracking
            matchPresenceService.recordUserPresence(userId, MatchPresenceRequest.builder()
                    .matchId(room.getMatchId())
                    .userId(userId)
                    .state(MatchPresenceState.ABANDONED)
                    .reason("INTENTIONAL_LEAVE")
                    .build());
            // Remove from room so existsUserInActiveRoom() returns false immediately.
            // This allows the player to join a new room without waiting for match_ended.
            // The DS still tracks the player via FishNet; this only affects lobby DB state.
            roomPlayerRepository.deleteByRoomIdAndUserId(roomId, userId);
            connectionManager.updateUserRoom(userId, null);
            updateCurrentRoomStatus(userId, null);
            log.info("[Room] User {} left IN_GAME room {} — abandonment recorded, room row removed", userId, roomId);
            if (room.getOwnerId().equals(userId)) {
                log.info("[Room] Owner left IN_GAME room {} — disbanding room", roomId);
                disbandRoom(roomId, userId);
            }
            return;
        }

        // Remove player
        roomPlayerRepository.deleteByRoomIdAndUserId(roomId, userId);
        
        // Clear party room status if player is in a party
        try {
            partyCustomModeService.clearPartyRoomStatus(userId);
        } catch (Exception e) {
            log.warn("Failed to clear party room status for user {}: {}", userId, e.getMessage());
        }
        
        connectionManager.updateUserRoom(userId, null);
        updateCurrentRoomStatus(userId, null);

        // Handle owner transfer or room disband before broadcasting the updated room.
        if (room.getOwnerId().equals(userId)) {
            List<RoomPlayer> remainingPlayers = roomPlayerRepository.findByRoomId(roomId);
            if (remainingPlayers.isEmpty()) {
                disbandRoom(roomId, userId);
                return;
            } else {
                RoomPlayer newOwner = remainingPlayers.get(0);
                room.setOwnerId(newOwner.getUserId());
                roomRepository.save(room);
            }
        }

        connectionManager.broadcastToRoom(roomId, "player_left",
                java.util.Map.of("userId", userId, "room", roomResponseAssembler.toResponseById(roomId)));
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

        // Notify kicked player personally so client can navigate back to Home
        connectionManager.sendToUser(targetUserId, "you_were_kicked",
                java.util.Map.of(
                        "roomId", roomId,
                        "kickedByUserId", ownerId,
                        "reason", "kicked_by_host",
                        "message", "The room owner removed you from the room."));
        connectionManager.updateUserRoom(targetUserId, null);
        updateCurrentRoomStatus(targetUserId, null);

        // Broadcast updated room state to remaining players
        connectionManager.broadcastToRoom(roomId, "player_left",
                java.util.Map.of("userId", targetUserId, "room", roomResponseAssembler.toResponseById(roomId)));
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

        // Collect all players BEFORE deleting so we can clear their WS room mapping
        List<RoomPlayer> players = roomPlayerRepository.findByRoomId(roomId);

        // Broadcast room_disbanded BEFORE deleting players so all connected clients are notified
        connectionManager.broadcastToRoom(roomId, "room_disbanded",
                java.util.Map.of("roomId", roomId, "reason", "disbanded"));

        // Clear WS userRooms mapping for every player so they can immediately join a new room
        for (RoomPlayer p : players) {
            connectionManager.updateUserRoom(p.getUserId(), null);
            updateCurrentRoomStatus(p.getUserId(), null);
        }

        // Delete all players
        roomPlayerRepository.deleteByRoomId(roomId);

        relaySessionManager.getByRoomId(roomId).ifPresent(session -> {
            log.info("[Room] Disbanding room {} - closing relay session={}", roomId, session.getSessionToken());
            relaySessionManager.finishSession(session.getSessionToken());
        });

        if (room.getMatchId() != null && !room.getMatchId().isBlank()) {
            matchPresenceService.clearMatchPresence(room.getMatchId());
        }

        // Update room status
        room.setStatus(GameConstants.ROOM_STATUS_CLOSED);
        roomRepository.save(room);
        log.info("Room {} disbanded by owner {}", roomId, ownerId);
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
        ensureOwnerReady(room, players);
        boolean allReady = players.stream().allMatch(player -> isReadyForStart(room, player));
        if (!allReady) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_READY,
                    "Not all players are ready");
        }

        // Custom rooms can start with any current player count as long as everyone present is ready.
        int currentPlayerCount = players.size();
        if (currentPlayerCount < 1) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_ENOUGH_PLAYERS,
                    "Cannot start a room with no players.");
        }

        log.info("Game starting for room {} (code: {}, matchId: {}, mode: {}, players: {})",
                roomId, room.getRoomCode(), room.getMatchId(), room.getMode(), currentPlayerCount);

        String oldStatus = room.getStatus();
        room.setStatus(GameConstants.ROOM_STATUS_IN_GAME);
        roomRepository.save(room);
        markPlayersInGame(players);

        // ── BE-27: Create relay session for Custom mode ──────────────────────
        // If the room is a Custom (non-ranked) lobby, spin up a Mini-Relay session
        // so clients get host/port info via WS game_starting event.
        String hostPlayerIp = connectionManager.getClientIp(ownerId);
        RelaySession relaySession = relaySessionManager.createSession(roomId, room.getMatchId(), hostPlayerIp);

        RoomResponse response = roomResponseAssembler.toResponse(room, null);

        // Include relay info in the game_starting broadcast
        java.util.Map<String, Object> startPayload = new java.util.HashMap<>();
        startPayload.put("room", response);
        if (relaySession != null) {
            startPayload.put("relayToken",  relaySession.getSessionToken());
            startPayload.put("relayHost",   relaySession.getRelayHost());
            startPayload.put("relayPort",   relaySession.getRelayPort());
            startPayload.put("relayHostPorts", relaySession.getRelayHostPorts());
        }

        // Broadcast game_starting event via WebSocket
        connectionManager.broadcastToRoom(roomId, "game_starting", startPayload);

        // Broadcast room status changed event via WebSocket
        connectionManager.broadcastToRoom(roomId, "room_status_changed",
                java.util.Map.of("newStatus", GameConstants.ROOM_STATUS_IN_GAME, "room", response));

        // Publish event via Message Broker
        messageBroker.publishRoomStatusChanged(roomId, oldStatus, GameConstants.ROOM_STATUS_IN_GAME);
        
        return response;
    }

    @Transactional
    public RoomResponse markRelayHostReady(Long roomId, Long ownerId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "Room not found"));

        if (!room.getOwnerId().equals(ownerId)) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_OWNER,
                    "Only the room owner can mark relay host ready");
        }

        if (!GameConstants.ROOM_STATUS_IN_GAME.equals(room.getStatus())) {
            throw new BusinessException(ErrorCodes.ROOM_ALREADY_STARTED,
                    "Relay host can be marked ready only after the room enters IN_GAME");
        }

        RelaySession relaySession = relaySessionManager.getByRoomId(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "No active relay session for room " + roomId));

        relaySessionManager.addPlayer(relaySession.getSessionToken(), ownerId);
        relaySessionManager.markStarted(relaySession.getSessionToken());

        RoomResponse response = roomResponseAssembler.toResponse(room, null);

        Map<String, Object> readyPayload = new HashMap<>();
        readyPayload.put("room", response);
        readyPayload.put("relayToken", relaySession.getSessionToken());
        readyPayload.put("relayHost", relaySession.getRelayHost());
        readyPayload.put("relayPort", relaySession.getRelayPort());
        readyPayload.put("relayHostPorts", relaySession.getRelayHostPorts());

        connectionManager.broadcastToRoom(roomId, "relay_host_ready", readyPayload);
        log.info("[Relay] Host ready roomId={} matchId={} ownerId={} relay={}:{} hostPorts={}",
                roomId, room.getMatchId(), ownerId, relaySession.getRelayHost(),
                relaySession.getRelayPort(), relaySession.getRelayHostPorts());

        return response;
    }

    public RoomResponse getRoom(Long roomId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "Room not found"));
        return roomResponseAssembler.toResponse(room, null);
    }

    /**
     * Delegates to RoomResponseAssembler (kept for backward compatibility if needed externally).
     */
    public RoomResponse buildRoomResponse(Room room, String joinToken) {
        return roomResponseAssembler.toResponse(room, joinToken);
    }

    /**
     * Immediately transitions a ranked room to IN_GAME status after match_ready has been broadcast.
     */
    @Transactional
    public void markRankedRoomInGame(String matchId) {
        roomRepository.findByMatchId(matchId).ifPresentOrElse(room -> {
            if (GameConstants.ROOM_STATUS_WAITING.equals(room.getStatus())) {
                room.setStatus(GameConstants.ROOM_STATUS_IN_GAME);
                roomRepository.save(room);
                markPlayersInGame(roomPlayerRepository.findByRoomId(room.getId()));
                log.info("[RankedMM] Room {} (matchId={}) -> IN_GAME (match_ready sent)", room.getId(), matchId);
            }
        }, () -> log.warn("[RankedMM] markRankedRoomInGame: no room found for matchId={}", matchId));
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
            setReadyAfterPositionChange(room, requester);
            
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
            connectionManager.broadcastToRoom(roomId, "team_changed",
                    java.util.Map.of(
                            "userId", userId,
                            "newTeam", targetTeam,
                            "newSlot", targetSlot,
                            "room", roomResponseAssembler.toResponseById(roomId)));

            // Return a dummy DTO to indicate success (no actual swap request created)
            SwapRequestDTO dto = new SwapRequestDTO();
            dto.setRoomId(roomId);
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
                    throw new BusinessException(ErrorCodes.ROOM_SWAP_REQUEST_PENDING,
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
        dto.setRoomId(roomId);
        dto.setRequesterId(userId);
        dto.setRequesterUsername(requesterUser != null ? requesterUser.getUsername() : "Unknown");
        dto.setRequesterTeam(requester.getTeam());
        dto.setRequesterSlot(requester.getSlot());
        dto.setTargetUserId(targetUserId);
        dto.setTargetTeam(targetTeam);
        dto.setTargetSlot(targetSlot);
        dto.setStatus("PENDING");

        // Broadcast swap request event via WebSocket
        connectionManager.sendToUser(dto.getTargetUserId(), "swap_request", dto);

        return dto;
    }

    @Transactional
    public RoomResponse acceptSwapRequest(Long userId, Long roomId, Long requestId) {
        SwapRequest swapRequest = swapRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_SWAP_NOT_TARGET,
                        "Swap request not found"));

        Long actualRoomId = resolveSwapRoomId(roomId, swapRequest);
        Room room = roomRepository.findById(actualRoomId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                        "Room not found"));

        // Verify user is the target
        if (!swapRequest.getTargetId().equals(userId)) {
            throw new BusinessException(ErrorCodes.ROOM_SWAP_NOT_TARGET,
                    "You are not the target of this swap request");
        }

        if (!"PENDING".equals(swapRequest.getStatus())) {
            throw new BusinessException(ErrorCodes.ROOM_SWAP_EXPIRED,
                    "Swap request is no longer pending");
        }
        if (swapRequest.getExpiresAt() != null && swapRequest.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
            swapRequest.setStatus("EXPIRED");
            swapRequestRepository.save(swapRequest);
            connectionManager.broadcastToRoom(actualRoomId, "swap_request_status", swapStatusPayload(actualRoomId, requestId, "EXPIRED"));
            throw new BusinessException(ErrorCodes.ROOM_SWAP_EXPIRED,
                    "Swap request has expired");
        }

        // Get both players
        RoomPlayer requester = roomPlayerRepository.findByRoomIdAndUserId(actualRoomId, swapRequest.getRequesterId())
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_PLAYER_NOT_FOUND,
                        "Requester not in room"));

        RoomPlayer target = roomPlayerRepository.findByRoomIdAndUserId(actualRoomId, swapRequest.getTargetId())
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
            connectionManager.broadcastToRoom(actualRoomId, "swap_request_status", swapStatusPayload(actualRoomId, requestId, "REJECTED"));
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
            setReadyAfterPositionChange(room, requester);
            roomPlayerRepository.save(requester);
            roomPlayerRepository.flush();

            // Step 2: move target into requester's original slot
            target.setTeam(origRequesterTeam);
            target.setSlot(origRequesterSlot);
            setReadyAfterPositionChange(room, target);
            roomPlayerRepository.save(target);
            roomPlayerRepository.flush();

            // Step 3: move requester into target's original slot
            requester.setTeam(origTargetTeam);
            requester.setSlot(origTargetSlot);
            setReadyAfterPositionChange(room, requester);
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

        RoomResponse response = roomResponseAssembler.toResponse(room, null);
        
        // Broadcast swap accepted event via WebSocket
        connectionManager.broadcastToRoom(actualRoomId, "swap_accepted", response);
        
        return response;
    }

    @Transactional
    public void rejectSwapRequest(Long userId, Long roomId, Long requestId) {
        SwapRequest swapRequest = swapRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_SWAP_NOT_TARGET,
                        "Swap request not found"));
        Long actualRoomId = resolveSwapRoomId(roomId, swapRequest);

        // Verify user is the target
        if (!swapRequest.getTargetId().equals(userId)) {
            throw new BusinessException(ErrorCodes.ROOM_SWAP_NOT_TARGET,
                    "You are not the target of this swap request");
        }

        if (!"PENDING".equals(swapRequest.getStatus())) {
            throw new BusinessException(ErrorCodes.ROOM_SWAP_EXPIRED,
                    "Swap request is no longer pending");
        }
        if (swapRequest.getExpiresAt() != null && swapRequest.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
            swapRequest.setStatus("EXPIRED");
            swapRequestRepository.save(swapRequest);
            connectionManager.broadcastToRoom(actualRoomId, "swap_request_status", swapStatusPayload(actualRoomId, requestId, "EXPIRED"));
            throw new BusinessException(ErrorCodes.ROOM_SWAP_EXPIRED,
                    "Swap request has expired");
        }

        swapRequest.setStatus("REJECTED");
        swapRequestRepository.save(swapRequest);
        
        // Broadcast swap rejected event via WebSocket
        connectionManager.broadcastToRoom(actualRoomId, "swap_request_status", swapStatusPayload(actualRoomId, requestId, "REJECTED"));
    }

    /**
     * Cancel swap request (requester only)
     */
    @Transactional
    public void cancelSwapRequest(Long userId, Long roomId, Long requestId) {
        SwapRequest swapRequest = swapRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_SWAP_NOT_REQUESTER,
                        "Swap request not found"));
        Long actualRoomId = resolveSwapRoomId(roomId, swapRequest);

        // Verify user is the requester
        if (!swapRequest.getRequesterId().equals(userId)) {
            throw new BusinessException(ErrorCodes.ROOM_SWAP_NOT_REQUESTER,
                    "You are not the requester of this swap request");
        }

        if (!"PENDING".equals(swapRequest.getStatus())) {
            throw new BusinessException(ErrorCodes.ROOM_SWAP_EXPIRED,
                    "Swap request is no longer pending");
        }

        swapRequest.setStatus("CANCELLED");
        swapRequestRepository.save(swapRequest);
        
        // Broadcast swap cancelled event via WebSocket
        connectionManager.broadcastToRoom(actualRoomId, "swap_request_status", swapStatusPayload(actualRoomId, requestId, "CANCELLED"));
    }

    public List<SwapRequestDTO> getPendingSwapRequests(Long userId, Long roomId) {
        List<SwapRequest> requests = swapRequestRepository.findPendingRequestsForUser(userId);
        
        return requests.stream()
                .filter(r -> r.getRoomId().equals(roomId))
                .map(r -> {
                    User requester = userRepository.findById(r.getRequesterId()).orElse(null);
                    SwapRequestDTO dto = new SwapRequestDTO();
                    dto.setRequestId(r.getId());
                    dto.setRoomId(r.getRoomId());
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

        RoomResponse response = roomResponseAssembler.toResponse(room, null);
        
        // Broadcast room update via WebSocket (owner changed)
        connectionManager.broadcastToRoom(roomId, "room_updated", response);
        
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

        boolean modeChanged = false;

        // Update mode if provided
        if (request.getMode() != null && !request.getMode().isEmpty()) {
            // Validate mode
            if (!gameModeService.isGameModeAvailable(request.getMode())) {
                throw new BusinessException(ErrorCodes.ROOM_NOT_FOUND, "Invalid or unavailable game mode: " + request.getMode());
            }

            // Check if mode change requires player count adjustment
            int currentMaxPlayers = gameModeService.getTotalPlayers(room.getMode());
            int newMaxPlayers = gameModeService.getTotalPlayers(request.getMode());
            int currentPlayers = roomPlayerRepository.findByRoomId(roomId).size();

            if (newMaxPlayers < currentPlayers) {
                throw new BusinessException(ErrorCodes.ROOM_FULL, 
                    String.format("Cannot switch to %s mode. Currently %d players in room, but %s mode only allows %d players.", 
                        request.getMode(), currentPlayers, request.getMode(), newMaxPlayers));
            }

            room.setMode(request.getMode());
            modeChanged = true;
        }

        if (request.getMapId() != null) {
            room.setMapId(normalizeMapId(request.getMapId()));
        } else if (room.getMapId() == null || room.getMapId().isBlank()) {
            room.setMapId(DEFAULT_MAP_ID);
        }

        // Update public/private if provided
        if (request.getIsPublic() != null) {
            room.setIsPublic(request.getIsPublic());
        }

        applyRoomLockSettings(room, request);

        room = roomRepository.save(room);
        if (modeChanged) {
            normalizeRoomSlotsForMode(room);
        }

        RoomResponse response = roomResponseAssembler.toResponse(room, null);
        
        // Broadcast room update via WebSocket (settings changed)
        connectionManager.broadcastToRoom(roomId, "room_updated", response);
        
        return response;
    }

    private String resolveCreateRoomPassword(boolean locked, String rawPassword) {
        if (!locked) {
            return null;
        }

        if (!hasText(rawPassword)) {
            throw new BusinessException(ErrorCodes.ROOM_PASSWORD_INVALID,
                    "Locked rooms require a password");
        }

        return bcrypt.encode(rawPassword.trim());
    }

    private void verifyRoomPassword(Room room, String rawPassword) {
        if (!isRoomPasswordProtected(room)) {
            return;
        }

        if (!hasPasswordHash(room)) {
            throw new BusinessException(ErrorCodes.ROOM_LOCKED,
                    "Room is locked but has no password configured");
        }

        if (!hasText(rawPassword) || !bcrypt.matches(rawPassword.trim(), room.getPassword())) {
            throw new BusinessException(ErrorCodes.ROOM_PASSWORD_INVALID,
                    "Room password is required or incorrect");
        }
    }

    private void applyRoomLockSettings(Room room, UpdateRoomSettingsRequest request) {
        if (room == null || request == null) {
            return;
        }

        String rawPassword = request.getPassword();
        boolean passwordProvided = rawPassword != null;

        if (passwordProvided && rawPassword.trim().isEmpty()) {
            room.setIsLocked(false);
            room.setPassword(null);
            return;
        }

        Boolean requestedLocked = request.getIsLocked();
        if (passwordProvided) {
            requestedLocked = true;
        }

        if (requestedLocked == null) {
            if (hasPasswordHash(room)) {
                room.setIsLocked(true);
            }
            return;
        }

        if (!requestedLocked) {
            room.setIsLocked(false);
            room.setPassword(null);
            return;
        }

        room.setIsLocked(true);
        if (passwordProvided) {
            room.setPassword(bcrypt.encode(rawPassword.trim()));
        } else if (!hasPasswordHash(room)) {
            throw new BusinessException(ErrorCodes.ROOM_PASSWORD_INVALID,
                    "Locked rooms require a password");
        }
    }

    private boolean isRoomPasswordProtected(Room room) {
        return room != null && (Boolean.TRUE.equals(room.getIsLocked()) || hasPasswordHash(room));
    }

    private boolean hasPasswordHash(Room room) {
        return room != null && room.getPassword() != null && !room.getPassword().isBlank();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isRoomFull(Room room) {
        int maxPlayers = gameModeService.getTotalPlayers(room.getMode());
        int currentPlayers = roomPlayerRepository.countByRoomId(room.getId());
        return currentPlayers >= maxPlayers;
    }

    private RoomListItemResponse toRoomListItem(Room room) {
        int currentPlayers = roomPlayerRepository.countByRoomId(room.getId());
        int maxPlayers = gameModeService.getTotalPlayers(room.getMode());
        User owner = userRepository.findById(room.getOwnerId()).orElse(null);

        return RoomListItemResponse.builder()
                .roomId(room.getId())
                .roomCode(room.getRoomCode())
                .mode(room.getMode())
                .mapId(room.getMapId())
                .status(room.getStatus())
                .isPublic(room.getIsPublic())
                .isLocked(isRoomPasswordProtected(room))
                .ownerId(room.getOwnerId())
                .ownerUsername(owner != null ? owner.getUsername() : "Unknown")
                .currentPlayers(currentPlayers)
                .maxPlayers(maxPlayers)
                .build();
    }

    private String normalizeOptionalFilter(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void ensureUserNotInMatchmakingQueue(Long userId) {
        matchmakingEntryRepository.findByUserId(userId).ifPresent(entry -> {
            if ("SEARCHING".equals(entry.getStatus()) || "MATCHED".equals(entry.getStatus())) {
                throw new BusinessException(ErrorCodes.MATCH_JOIN_FAILED,
                        "Cancel matchmaking before creating or joining a custom room");
            }
        });
    }

    private void updateCurrentRoomStatus(Long userId, Long roomId) {
        try {
            playerStatusService.updateCurrentRoom(userId, roomId);
        } catch (Exception e) {
            log.warn("Failed to update current room status for user {} room {}: {}", userId, roomId, e.getMessage());
        }
    }

    private void markPlayersInGame(List<RoomPlayer> players) {
        if (players == null) {
            return;
        }

        for (RoomPlayer player : players) {
            if (player == null) {
                continue;
            }

            try {
                playerStatusService.setInGame(player.getUserId());
            } catch (Exception e) {
                log.warn("Failed to set user {} IN_GAME: {}", player.getUserId(), e.getMessage());
            }
        }
    }

    private Long resolveSwapRoomId(Long requestedRoomId, SwapRequest swapRequest) {
        Long actualRoomId = swapRequest.getRoomId();
        if (requestedRoomId == null || !requestedRoomId.equals(actualRoomId)) {
            log.warn("Swap request {} received mismatched roomId path={} actual={}; using actual roomId.",
                    swapRequest.getId(), requestedRoomId, actualRoomId);
        }
        return actualRoomId;
    }

    private java.util.Map<String, Object> swapStatusPayload(Long roomId, Long requestId, String status) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("requestId", requestId);
        payload.put("status", status);

        try {
            payload.put("room", roomResponseAssembler.toResponseById(roomId));
        } catch (Exception e) {
            log.warn("Failed to attach room payload to swap status roomId={} requestId={}: {}",
                    roomId, requestId, e.getMessage());
        }

        return payload;
    }

    private void setReadyAfterPositionChange(Room room, RoomPlayer player) {
        if (room == null || player == null) {
            return;
        }

        // The room owner is the authoritative starter, so moving slots must not
        // put a solo host back into a blocked "waiting for ready" state.
        player.setIsReady(room.getOwnerId().equals(player.getUserId()));
    }

    private void ensureOwnerReady(Room room, List<RoomPlayer> players) {
        if (room == null || players == null) {
            return;
        }

        for (RoomPlayer player : players) {
            if (player != null
                    && room.getOwnerId().equals(player.getUserId())
                    && !Boolean.TRUE.equals(player.getIsReady())) {
                player.setIsReady(true);
                roomPlayerRepository.save(player);
            }
        }
    }

    private boolean isReadyForStart(Room room, RoomPlayer player) {
        if (room == null || player == null) {
            return false;
        }

        return Boolean.TRUE.equals(player.getIsReady())
                || room.getOwnerId().equals(player.getUserId());
    }

    private void normalizeRoomSlotsForMode(Room room) {
        int slotsPerTeam = Math.max(1, gameModeService.getPlayersPerTeam(room.getMode()));
        List<RoomPlayer> players = roomPlayerRepository.findByRoomId(room.getId());
        players.sort(Comparator
                .comparing((RoomPlayer p) -> p.getTeam() == null ? Integer.MAX_VALUE : p.getTeam())
                .thenComparing(p -> p.getSlot() == null ? Integer.MAX_VALUE : p.getSlot())
                .thenComparing(RoomPlayer::getId));

        boolean[][] occupied = new boolean[GameConstants.TEAM_2 + 1][slotsPerTeam];
        List<RoomPlayer> needsMove = new java.util.ArrayList<>();

        for (RoomPlayer player : players) {
            Integer team = player.getTeam();
            Integer slot = player.getSlot();
            if (team != null
                    && slot != null
                    && isValidTeam(team)
                    && slot >= 0
                    && slot < slotsPerTeam
                    && !occupied[team][slot]) {
                occupied[team][slot] = true;
            } else {
                needsMove.add(player);
            }
        }

        for (RoomPlayer player : needsMove) {
            int preferredTeam = isValidTeam(player.getTeam()) ? player.getTeam() : GameConstants.TEAM_1;
            int slot = findFreeSlot(occupied, preferredTeam);
            int team = preferredTeam;

            if (slot < 0) {
                team = preferredTeam == GameConstants.TEAM_1 ? GameConstants.TEAM_2 : GameConstants.TEAM_1;
                slot = findFreeSlot(occupied, team);
            }

            if (slot < 0) {
                throw new BusinessException(ErrorCodes.ROOM_FULL,
                        "Could not place all players in the selected mode.");
            }

            occupied[team][slot] = true;
            player.setTeam(team);
            player.setSlot(slot);
            player.setIsReady(room.getOwnerId().equals(player.getUserId()));
            roomPlayerRepository.save(player);
        }
    }

    private static boolean isValidTeam(Integer team) {
        return team != null && (team == GameConstants.TEAM_1 || team == GameConstants.TEAM_2);
    }

    private static int findFreeSlot(boolean[][] occupied, int team) {
        for (int slot = 0; slot < occupied[team].length; slot++) {
            if (!occupied[team][slot]) {
                return slot;
            }
        }
        return -1;
    }

    private String normalizeMapId(String mapId) {
        if (mapId == null || mapId.isBlank()) {
            return DEFAULT_MAP_ID;
        }

        return mapId.trim();
    }
}

