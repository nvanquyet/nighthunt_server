package com.nighthunt.party.service;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.party.dto.PartyRoomJoinRequest;
import com.nighthunt.party.dto.PartyRoomJoinResponse;
import com.nighthunt.party.entity.Party;
import com.nighthunt.party.entity.PartyMember;
import com.nighthunt.party.repository.PartyRepository;
import com.nighthunt.party.repository.PartyMemberRepository;
import com.nighthunt.room.entity.Room;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomRepository;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PartyRoomService}.
 * Tests party joining custom rooms together with validation.
 */
@ExtendWith(MockitoExtension.class)
class PartyRoomServiceTest {

    @Mock PartyRepository partyRepository;
    @Mock PartyMemberRepository partyMemberRepository;
    @Mock RoomRepository roomRepository;
    @Mock RoomPlayerRepository roomPlayerRepository;
    @Mock UserRepository userRepository;
    @Mock ConnectionManager connectionManager;

    @InjectMocks PartyRoomService partyRoomService;

    private User user1;
    private User user2;
    private User user3;
    private User user4;

    @BeforeEach
    void setUp() {
        user1 = User.builder().id(1L).username("alice").build();
        user2 = User.builder().id(2L).username("bob").build();
        user3 = User.builder().id(3L).username("charlie").build();
        user4 = User.builder().id(4L).username("dave").build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // JOIN ROOM WITH PARTY
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("joinRoomWithParty: leader joins with 2-player party successfully")
    void joinRoomWithParty_twoPlayers_success() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();
        Room room = Room.builder()
                .id(1L)
                .hostId(3L)
                .roomCode("ABC123")
                .gameMode("2v2")
                .maxPlayers(4)
                .status("WAITING")
                .build();

        PartyRoomJoinRequest request = new PartyRoomJoinRequest();
        request.setRoomCode("ABC123");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(Arrays.asList(member1, member2));
        when(roomRepository.findByRoomCode("ABC123")).thenReturn(Optional.of(room));
        when(roomPlayerRepository.countByRoomId(1L)).thenReturn(1L); // Only host
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));

        PartyRoomJoinResponse response = partyRoomService.joinRoomWithParty(1L, request);

        assertThat(response.getRoomCode()).isEqualTo("ABC123");
        assertThat(response.getPartyMembersJoined()).isEqualTo(2);
        verify(roomPlayerRepository, times(2)).save(any(RoomPlayer.class));
        verify(partyRepository).save(argThat(p -> p.getStatus().equals("IN_ROOM")));
        verify(connectionManager, atLeast(2)).sendToUser(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("joinRoomWithParty: throws exception when not party leader")
    void joinRoomWithParty_notLeader_throwsException() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();

        PartyRoomJoinRequest request = new PartyRoomJoinRequest();
        request.setRoomCode("ABC123");

        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(partyMemberRepository.findByUserId(2L)).thenReturn(Optional.of(member2));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));

        assertThatThrownBy(() -> partyRoomService.joinRoomWithParty(2L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only party leader can join room");
    }

    @Test
    @DisplayName("joinRoomWithParty: throws exception when room not found")
    void joinRoomWithParty_roomNotFound_throwsException() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();

        PartyRoomJoinRequest request = new PartyRoomJoinRequest();
        request.setRoomCode("INVALID");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(List.of(member1));
        when(roomRepository.findByRoomCode("INVALID")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> partyRoomService.joinRoomWithParty(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Room not found");
    }

    @Test
    @DisplayName("joinRoomWithParty: throws exception when room is full")
    void joinRoomWithParty_roomFull_throwsException() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();
        Room room = Room.builder()
                .id(1L)
                .hostId(3L)
                .roomCode("ABC123")
                .maxPlayers(4)
                .status("WAITING")
                .build();

        PartyRoomJoinRequest request = new PartyRoomJoinRequest();
        request.setRoomCode("ABC123");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(Arrays.asList(member1, member2));
        when(roomRepository.findByRoomCode("ABC123")).thenReturn(Optional.of(room));
        when(roomPlayerRepository.countByRoomId(1L)).thenReturn(3L); // 3 players already, only space for 1

        assertThatThrownBy(() -> partyRoomService.joinRoomWithParty(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Not enough space in room for party");
    }

    @Test
    @DisplayName("joinRoomWithParty: throws exception when room already started")
    void joinRoomWithParty_roomStarted_throwsException() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();
        Room room = Room.builder()
                .id(1L)
                .hostId(3L)
                .roomCode("ABC123")
                .status("IN_GAME")
                .build();

        PartyRoomJoinRequest request = new PartyRoomJoinRequest();
        request.setRoomCode("ABC123");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(List.of(member1));
        when(roomRepository.findByRoomCode("ABC123")).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> partyRoomService.joinRoomWithParty(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Room is not accepting players");
    }

    @Test
    @DisplayName("joinRoomWithParty: throws exception when party already in room")
    void joinRoomWithParty_alreadyInRoom_throwsException() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IN_ROOM").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();

        PartyRoomJoinRequest request = new PartyRoomJoinRequest();
        request.setRoomCode("ABC123");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));

        assertThatThrownBy(() -> partyRoomService.joinRoomWithParty(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Party is already in a room");
    }

    @Test
    @DisplayName("joinRoomWithParty: throws exception when party in queue")
    void joinRoomWithParty_partyInQueue_throwsException() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IN_QUEUE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();

        PartyRoomJoinRequest request = new PartyRoomJoinRequest();
        request.setRoomCode("ABC123");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));

        assertThatThrownBy(() -> partyRoomService.joinRoomWithParty(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Party is in matchmaking queue");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LEAVE ROOM WITH PARTY
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("leaveRoomWithParty: leader removes entire party from room")
    void leaveRoomWithParty_success() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IN_ROOM").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();
        RoomPlayer roomPlayer1 = RoomPlayer.builder().id(1L).roomId(1L).userId(1L).build();
        RoomPlayer roomPlayer2 = RoomPlayer.builder().id(2L).roomId(1L).userId(2L).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(Arrays.asList(member1, member2));
        when(roomPlayerRepository.findByUserId(1L)).thenReturn(Optional.of(roomPlayer1));
        when(roomPlayerRepository.findByUserId(2L)).thenReturn(Optional.of(roomPlayer2));

        partyRoomService.leaveRoomWithParty(1L);

        verify(roomPlayerRepository, times(2)).delete(any(RoomPlayer.class));
        verify(partyRepository).save(argThat(p -> p.getStatus().equals("IDLE")));
        verify(connectionManager, atLeast(2)).sendToUser(anyLong(), eq("party.status.changed"), any());
    }

    @Test
    @DisplayName("leaveRoomWithParty: throws exception when not leader")
    void leaveRoomWithParty_notLeader_throwsException() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IN_ROOM").build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();

        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(partyMemberRepository.findByUserId(2L)).thenReturn(Optional.of(member2));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));

        assertThatThrownBy(() -> partyRoomService.leaveRoomWithParty(2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only party leader can remove party from room");
    }

    @Test
    @DisplayName("leaveRoomWithParty: throws exception when party not in room")
    void leaveRoomWithParty_notInRoom_throwsException() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));

        assertThatThrownBy(() -> partyRoomService.leaveRoomWithParty(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Party is not in a room");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // IS PARTY IN ROOM
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isPartyInRoom: returns true when party in room")
    void isPartyInRoom_inRoom_returnsTrue() {
        Party party = Party.builder().id(1L).status("IN_ROOM").build();
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));

        boolean result = partyRoomService.isPartyInRoom(1L);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isPartyInRoom: returns false when party idle")
    void isPartyInRoom_idle_returnsFalse() {
        Party party = Party.builder().id(1L).status("IDLE").build();
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));

        boolean result = partyRoomService.isPartyInRoom(1L);

        assertThat(result).isFalse();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MULTI-ACCOUNT ROOM SCENARIOS
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("multiAccount: Party of 3 joins room with 1 player, room now has 4 players")
    void multiAccount_partyJoinsRoom_allMembersAdded() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();
        PartyMember member3 = PartyMember.builder().id(3L).partyId(1L).userId(3L).isLeader(false).build();
        Room room = Room.builder()
                .id(1L)
                .hostId(4L)
                .roomCode("ROOM123")
                .gameMode("5v5")
                .maxPlayers(10)
                .status("WAITING")
                .build();

        PartyRoomJoinRequest request = new PartyRoomJoinRequest();
        request.setRoomCode("ROOM123");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L))
                .thenReturn(Arrays.asList(member1, member2, member3));
        when(roomRepository.findByRoomCode("ROOM123")).thenReturn(Optional.of(room));
        when(roomPlayerRepository.countByRoomId(1L)).thenReturn(1L); // Only host
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(userRepository.findById(3L)).thenReturn(Optional.of(user3));

        PartyRoomJoinResponse response = partyRoomService.joinRoomWithParty(1L, request);

        assertThat(response.getPartyMembersJoined()).isEqualTo(3);
        verify(roomPlayerRepository, times(3)).save(any(RoomPlayer.class));
        verify(partyRepository).save(argThat(p -> p.getStatus().equals("IN_ROOM")));
    }

    @Test
    @DisplayName("multiAccount: Party leader leaves room, all party members removed")
    void multiAccount_leaderLeavesRoom_allMembersRemoved() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IN_ROOM").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();
        RoomPlayer roomPlayer1 = RoomPlayer.builder().id(1L).roomId(1L).userId(1L).build();
        RoomPlayer roomPlayer2 = RoomPlayer.builder().id(2L).roomId(1L).userId(2L).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(Arrays.asList(member1, member2));
        when(roomPlayerRepository.findByUserId(1L)).thenReturn(Optional.of(roomPlayer1));
        when(roomPlayerRepository.findByUserId(2L)).thenReturn(Optional.of(roomPlayer2));

        partyRoomService.leaveRoomWithParty(1L);

        // Both party members removed from room
        verify(roomPlayerRepository).delete(roomPlayer1);
        verify(roomPlayerRepository).delete(roomPlayer2);
        verify(partyRepository).save(argThat(p -> p.getStatus().equals("IDLE")));
    }

    @Test
    @DisplayName("multiAccount: Party tries to join room but not enough space")
    void multiAccount_partyJoinFails_notEnoughSpace() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();
        PartyMember member3 = PartyMember.builder().id(3L).partyId(1L).userId(3L).isLeader(false).build();
        Room room = Room.builder()
                .id(1L)
                .hostId(4L)
                .roomCode("ROOM123")
                .maxPlayers(4)
                .status("WAITING")
                .build();

        PartyRoomJoinRequest request = new PartyRoomJoinRequest();
        request.setRoomCode("ROOM123");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L))
                .thenReturn(Arrays.asList(member1, member2, member3));
        when(roomRepository.findByRoomCode("ROOM123")).thenReturn(Optional.of(room));
        when(roomPlayerRepository.countByRoomId(1L)).thenReturn(2L); // 2 players, only space for 2 more

        assertThatThrownBy(() -> partyRoomService.joinRoomWithParty(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Not enough space in room for party");

        // Verify no players were added
        verify(roomPlayerRepository, never()).save(any(RoomPlayer.class));
        verify(partyRepository, never()).save(any(Party.class));
    }
}
