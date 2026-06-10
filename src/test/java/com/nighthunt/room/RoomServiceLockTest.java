package com.nighthunt.room;

import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.friend.service.PlayerStatusService;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.gamemode.service.GameModeService;
import com.nighthunt.match.repository.MatchRepository;
import com.nighthunt.match.service.MatchPresenceService;
import com.nighthunt.matchmaking.repository.MatchmakingEntryRepository;
import com.nighthunt.messaging.service.MessageBrokerService;
import com.nighthunt.party.service.PartyCustomModeService;
import com.nighthunt.relay.service.RelaySessionManager;
import com.nighthunt.room.dto.CreateRoomRequest;
import com.nighthunt.room.dto.QuickPlayRequest;
import com.nighthunt.room.dto.RoomResponse;
import com.nighthunt.room.entity.Room;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import com.nighthunt.room.repository.SwapRequestRepository;
import com.nighthunt.room.service.RoomResponseAssembler;
import com.nighthunt.room.service.RoomService;
import com.nighthunt.room.service.TeamSlotAllocator;
import com.nighthunt.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomServiceLockTest {

    @Mock private RoomRepository roomRepository;
    @Mock private RoomPlayerRepository roomPlayerRepository;
    @Mock private UserRepository userRepository;
    @Mock private MatchRepository matchRepository;
    @Mock private MatchmakingEntryRepository matchmakingEntryRepository;
    @Mock private SwapRequestRepository swapRequestRepository;
    @Mock private ConnectionManager connectionManager;
    @Mock private MessageBrokerService messageBroker;
    @Mock private RoomResponseAssembler roomResponseAssembler;
    @Mock private TeamSlotAllocator teamSlotAllocator;
    @Mock private RelaySessionManager relaySessionManager;
    @Mock private PartyCustomModeService partyCustomModeService;
    @Mock private GameModeService gameModeService;
    @Mock private PlayerStatusService playerStatusService;
    @Mock private MatchPresenceService matchPresenceService;
    @Mock private RoomService self;

    private RoomService service;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(10);

    @BeforeEach
    void setUp() {
        service = new RoomService(
                roomRepository,
                roomPlayerRepository,
                userRepository,
                matchRepository,
                matchmakingEntryRepository,
                swapRequestRepository,
                connectionManager,
                messageBroker,
                roomResponseAssembler,
                teamSlotAllocator,
                relaySessionManager,
                partyCustomModeService,
                gameModeService,
                playerStatusService,
                matchPresenceService);
        ReflectionTestUtils.setField(service, "self", self);
    }

    @Test
    void joinRoomByCode_rejectsMissingPasswordForLockedRoom() {
        Room room = room(true, bcrypt.encode("secret"));
        when(roomRepository.findByRoomCode("LOCKED")).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.joinRoomByCode(22L, "LOCKED", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCodes.ROOM_PASSWORD_INVALID));

        verify(roomPlayerRepository, never()).save(any());
    }

    @Test
    void joinRoomByCode_treatsLegacyPasswordRowAsLocked() {
        Room room = room(false, bcrypt.encode("secret"));
        when(roomRepository.findByRoomCode("LEGACY")).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.joinRoomByCode(22L, "LEGACY", "wrong"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCodes.ROOM_PASSWORD_INVALID));

        verify(roomPlayerRepository, never()).save(any());
    }

    @Test
    void quickPlay_ignoresLockedRoomEvenIfRepositoryReturnsIt() {
        Room lockedRoom = room(true, bcrypt.encode("secret"));
        when(roomRepository.findQuickJoinRooms(GameConstants.ROOM_STATUS_WAITING))
                .thenReturn(List.of(lockedRoom));

        QuickPlayRequest request = new QuickPlayRequest();
        request.setMode("2v2");
        request.setMapId("map_01");
        RoomResponse created = RoomResponse.builder().roomId(99L).build();
        when(self.createRoom(eq(22L), any(CreateRoomRequest.class))).thenReturn(created);

        RoomResponse result = service.quickPlay(22L, request);

        assertThat(result).isSameAs(created);
        verify(self, never()).joinRoomByCode(any(), any(), any());
        verify(self).createRoom(eq(22L), any(CreateRoomRequest.class));
    }

    private static Room room(boolean locked, String password) {
        return Room.builder()
                .id(10L)
                .roomCode("LOCKED")
                .mode("2v2")
                .mapId("map_01")
                .status(GameConstants.ROOM_STATUS_WAITING)
                .isPublic(true)
                .isLocked(locked)
                .password(password)
                .ownerId(1L)
                .build();
    }
}
