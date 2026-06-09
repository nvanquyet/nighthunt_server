package com.nighthunt.match;

import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.friend.service.PlayerStatusService;
import com.nighthunt.game.websocket.dto.MatchPresenceNoticeDTO;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.match.adapter.RedisMatchPresenceCache;
import com.nighthunt.match.dto.MatchPresenceRequest;
import com.nighthunt.match.dto.MatchPresenceState;
import com.nighthunt.match.model.MatchPresenceSnapshot;
import com.nighthunt.match.service.AbandonPenaltyService;
import com.nighthunt.match.service.MatchPresenceService;
import com.nighthunt.room.dto.RoomResponse;
import com.nighthunt.room.entity.Room;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import com.nighthunt.room.service.RoomResponseAssembler;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class MatchPresenceServiceTest {
    @Mock private RoomRepository roomRepository;
    @Mock private RoomPlayerRepository roomPlayerRepository;
    @Mock private UserRepository userRepository;
    @Mock private ConnectionManager connectionManager;
    @Mock private RoomResponseAssembler roomResponseAssembler;
    @Mock private RedisMatchPresenceCache presenceCache;
    @Mock private PlayerStatusService playerStatusService;
    @Mock private AbandonPenaltyService abandonPenaltyService;  // wired after P2 changes

    private MatchPresenceService service;
    private Room room;
    private RoomPlayer player;

    @BeforeEach
    void setUp() {
        service = new MatchPresenceService(
                roomRepository,
                roomPlayerRepository,
                userRepository,
                connectionManager,
                roomResponseAssembler,
                presenceCache,
                playerStatusService,
                abandonPenaltyService);


        room = Room.builder()
                .id(7L)
                .ownerId(1L)
                .status(GameConstants.ROOM_STATUS_IN_GAME)
                .matchId("match-1")
                .build();
        player = RoomPlayer.builder()
                .roomId(7L)
                .userId(42L)
                .team(1)
                .slot(0)
                .isReady(true)
                .build();

        lenient().when(roomPlayerRepository.findActiveRoomsByUserId(42L)).thenReturn(List.of(player));
        lenient().when(roomRepository.findById(7L)).thenReturn(Optional.of(room));
        when(roomRepository.findByMatchId("match-1")).thenReturn(Optional.of(room));
        when(roomPlayerRepository.findByRoomIdAndUserId(7L, 42L)).thenReturn(Optional.of(player));
        when(userRepository.findById(42L)).thenReturn(Optional.of(User.builder()
                .id(42L)
                .username("Alice")
                .email("alice@example.com")
                .passwordHash("hash")
                .build()));
        lenient().when(roomResponseAssembler.toResponseById(7L)).thenReturn(RoomResponse.builder()
                .roomId(7L)
                .matchId("match-1")
                .status(GameConstants.ROOM_STATUS_IN_GAME)
                .build());
    }

    @Test
    @DisplayName("initial FishNet connected stores CONNECTED without broadcasting a reconnect notice")
    void initialFishNetConnected_savesWithoutNotice() {
        when(presenceCache.get("match-1", 42L)).thenReturn(Optional.empty());

        service.recordServerPresence(MatchPresenceRequest.builder()
                .matchId("match-1")
                .userId(42L)
                .state(MatchPresenceState.CONNECTED)
                .reason("FISHNET_CONNECTED")
                .build());

        ArgumentCaptor<MatchPresenceSnapshot> captor = ArgumentCaptor.forClass(MatchPresenceSnapshot.class);
        verify(presenceCache).save(captor.capture());
        MatchPresenceSnapshot snapshot = captor.getValue();
        assertThat(snapshot.getState()).isEqualTo(MatchPresenceState.CONNECTED);
        assertThat(snapshot.getReason()).isEqualTo("FISHNET_CONNECTED");
        assertThat(snapshot.getDisconnectedAt()).isNull();
        assertThat(snapshot.isAbandoned()).isFalse();

        verify(connectionManager, never()).broadcastToRoom(eq(7L), eq("match_presence_notice"), any(MatchPresenceNoticeDTO.class));
    }

    @Test
    @DisplayName("connected after a disconnect broadcasts a reconnect notice")
    void connectedAfterDisconnect_broadcastsReconnectNotice() {
        MatchPresenceSnapshot previous = MatchPresenceSnapshot.builder()
                .matchId("match-1")
                .roomId(7L)
                .userId(42L)
                .displayName("Alice")
                .state(MatchPresenceState.DISCONNECTED)
                .abandoned(false)
                .build();
        when(presenceCache.get("match-1", 42L)).thenReturn(Optional.of(previous));

        service.recordServerPresence(MatchPresenceRequest.builder()
                .matchId("match-1")
                .userId(42L)
                .state(MatchPresenceState.CONNECTED)
                .reason("FISHNET_CONNECTED")
                .build());

        ArgumentCaptor<MatchPresenceNoticeDTO> noticeCaptor = ArgumentCaptor.forClass(MatchPresenceNoticeDTO.class);
        verify(connectionManager).broadcastToRoom(eq(7L), eq("match_presence_notice"), noticeCaptor.capture());
        assertThat(noticeCaptor.getValue().getMessage()).isEqualTo("Player reconnected to the match.");
    }

    @Test
    @DisplayName("transport disconnect stores DISCONNECTED and keeps the room slot")
    void transportDisconnect_holdsSlotForGraceWindow() {
        when(presenceCache.get("match-1", 42L)).thenReturn(Optional.empty());

        service.recordTransportDisconnected(42L, "TRANSPORT_DROP");

        ArgumentCaptor<MatchPresenceSnapshot> captor = ArgumentCaptor.forClass(MatchPresenceSnapshot.class);
        verify(presenceCache).save(captor.capture());
        MatchPresenceSnapshot snapshot = captor.getValue();
        assertThat(snapshot.getState()).isEqualTo(MatchPresenceState.DISCONNECTED);
        assertThat(snapshot.getReason()).isEqualTo("TRANSPORT_DROP");
        assertThat(snapshot.getDisconnectedAt()).isNotNull();
        assertThat(snapshot.isAbandoned()).isFalse();

        verify(roomPlayerRepository, never()).deleteByRoomIdAndUserId(7L, 42L);
        verify(connectionManager).broadcastToRoom(eq(7L), eq("match_presence_notice"), any(MatchPresenceNoticeDTO.class));
    }

    @Test
    @DisplayName("logout/session termination marks ABANDONED and releases the room slot")
    void sessionTerminated_releasesSlotImmediately() {
        when(presenceCache.get("match-1", 42L)).thenReturn(Optional.empty());

        service.recordSessionTerminated(42L, "LOGOUT");

        ArgumentCaptor<MatchPresenceSnapshot> captor = ArgumentCaptor.forClass(MatchPresenceSnapshot.class);
        verify(presenceCache, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        MatchPresenceSnapshot snapshot = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(snapshot.getState()).isEqualTo(MatchPresenceState.ABANDONED);
        assertThat(snapshot.getReason()).isEqualTo("LOGOUT");
        assertThat(snapshot.isAbandoned()).isTrue();
        assertThat(snapshot.getAbandonedAt()).isNotNull();

        verify(roomPlayerRepository).deleteByRoomIdAndUserId(7L, 42L);
        verify(connectionManager).sendToUser(eq(42L), eq("you_were_kicked"), any());
        verify(connectionManager).broadcastToRoom(eq(7L), eq("match_presence_notice"), any(MatchPresenceNoticeDTO.class));
    }
}
