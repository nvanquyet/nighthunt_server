package com.nighthunt.party.service;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.matchmaking.service.MatchmakingQueueService;
import com.nighthunt.party.dto.PartyMatchmakingRequest;
import com.nighthunt.party.entity.Party;
import com.nighthunt.party.entity.PartyMember;
import com.nighthunt.party.repository.PartyRepository;
import com.nighthunt.party.repository.PartyMemberRepository;
import com.nighthunt.gamemode.service.GameModeService;
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
 * Unit tests for {@link PartyMatchmakingService}.
 * Tests party matchmaking queue operations with fill option and validation.
 */
@ExtendWith(MockitoExtension.class)
class PartyMatchmakingServiceTest {

    @Mock PartyRepository partyRepository;
    @Mock PartyMemberRepository partyMemberRepository;
    @Mock UserRepository userRepository;
    @Mock MatchmakingQueueService matchmakingQueueService;
    @Mock GameModeService gameModeService;
    @Mock ConnectionManager connectionManager;

    @InjectMocks PartyMatchmakingService partyMatchmakingService;

    private User user1;
    private User user2;
    private User user3;
    private User user4;

    @BeforeEach
    void setUp() {
        user1 = User.builder().id(1L).username("alice").elo(1500).build();
        user2 = User.builder().id(2L).username("bob").elo(1520).build();
        user3 = User.builder().id(3L).username("charlie").elo(1510).build();
        user4 = User.builder().id(4L).username("dave").elo(1505).build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // QUEUE PARTY
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("queueParty: successfully queues 2-player party for 2v2 with allowFill")
    void queueParty_twoPlayers_2v2_allowFill_success() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();

        PartyMatchmakingRequest request = new PartyMatchmakingRequest();
        request.setGameMode("2v2");
        request.setAllowFill(true);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(Arrays.asList(member1, member2));
        when(gameModeService.getPlayersPerTeam("2v2")).thenReturn(2);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));

        partyMatchmakingService.queueParty(1L, request);

        verify(matchmakingQueueService, times(2)).enqueue(anyLong(), eq("2v2"));
        verify(partyRepository).save(argThat(p -> p.getStatus().equals("IN_QUEUE")));
        verify(connectionManager, times(2)).sendToUser(anyLong(), eq("party.status.changed"), any());
    }

    @Test
    @DisplayName("queueParty: successfully queues full 2v2 party without fill option")
    void queueParty_fullTeam_2v2_noFill_success() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();

        PartyMatchmakingRequest request = new PartyMatchmakingRequest();
        request.setGameMode("2v2");
        request.setAllowFill(false);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(Arrays.asList(member1, member2));
        when(gameModeService.getPlayersPerTeam("2v2")).thenReturn(2);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));

        partyMatchmakingService.queueParty(1L, request);

        verify(matchmakingQueueService, times(2)).enqueue(anyLong(), eq("2v2"));
        verify(partyRepository).save(argThat(p -> p.getStatus().equals("IN_QUEUE")));
    }

    @Test
    @DisplayName("queueParty: throws exception when party not full and no fill option")
    void queueParty_notFull_noFill_throwsException() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();

        PartyMatchmakingRequest request = new PartyMatchmakingRequest();
        request.setGameMode("2v2");
        request.setAllowFill(false);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(List.of(member1));
        when(gameModeService.getPlayersPerTeam("2v2")).thenReturn(2);

        assertThatThrownBy(() -> partyMatchmakingService.queueParty(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Party must be full");
    }

    @Test
    @DisplayName("queueParty: throws exception when party size exceeds mode capacity")
    void queueParty_tooManyPlayers_throwsException() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();
        PartyMember member3 = PartyMember.builder().id(3L).partyId(1L).userId(3L).isLeader(false).build();

        PartyMatchmakingRequest request = new PartyMatchmakingRequest();
        request.setGameMode("2v2");
        request.setAllowFill(true);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L))
                .thenReturn(Arrays.asList(member1, member2, member3));
        when(gameModeService.getPlayersPerTeam("2v2")).thenReturn(2);

        assertThatThrownBy(() -> partyMatchmakingService.queueParty(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Party size exceeds mode capacity");
    }

    @Test
    @DisplayName("queueParty: throws exception when party already in queue")
    void queueParty_alreadyInQueue_throwsException() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IN_QUEUE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();

        PartyMatchmakingRequest request = new PartyMatchmakingRequest();
        request.setGameMode("2v2");
        request.setAllowFill(true);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));

        assertThatThrownBy(() -> partyMatchmakingService.queueParty(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Party is already in queue");
    }

    @Test
    @DisplayName("queueParty: throws exception when not party leader")
    void queueParty_notLeader_throwsException() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();

        PartyMatchmakingRequest request = new PartyMatchmakingRequest();
        request.setGameMode("2v2");
        request.setAllowFill(true);

        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(partyMemberRepository.findByUserId(2L)).thenReturn(Optional.of(member2));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));

        assertThatThrownBy(() -> partyMatchmakingService.queueParty(2L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only party leader can queue");
    }

    @Test
    @DisplayName("queueParty: successfully queues 3-player party for 5v5 with fill")
    void queueParty_threePlayers_5v5_allowFill_success() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();
        PartyMember member3 = PartyMember.builder().id(3L).partyId(1L).userId(3L).isLeader(false).build();

        PartyMatchmakingRequest request = new PartyMatchmakingRequest();
        request.setGameMode("5v5");
        request.setAllowFill(true);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L))
                .thenReturn(Arrays.asList(member1, member2, member3));
        when(gameModeService.getPlayersPerTeam("5v5")).thenReturn(5);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(userRepository.findById(3L)).thenReturn(Optional.of(user3));

        partyMatchmakingService.queueParty(1L, request);

        verify(matchmakingQueueService, times(3)).enqueue(anyLong(), eq("5v5"));
        verify(partyRepository).save(argThat(p -> p.getStatus().equals("IN_QUEUE")));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CANCEL QUEUE
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("cancelQueue: successfully cancels party queue")
    void cancelQueue_success() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IN_QUEUE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(Arrays.asList(member1, member2));

        partyMatchmakingService.cancelQueue(1L);

        verify(matchmakingQueueService, times(2)).dequeue(anyLong());
        verify(partyRepository).save(argThat(p -> p.getStatus().equals("IDLE")));
        verify(connectionManager, times(2)).sendToUser(anyLong(), eq("party.status.changed"), any());
    }

    @Test
    @DisplayName("cancelQueue: throws exception when party not in queue")
    void cancelQueue_notInQueue_throwsException() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));

        assertThatThrownBy(() -> partyMatchmakingService.cancelQueue(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Party is not in queue");
    }

    @Test
    @DisplayName("cancelQueue: any member can cancel (not just leader)")
    void cancelQueue_anyMemberCanCancel() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IN_QUEUE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();

        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(partyMemberRepository.findByUserId(2L)).thenReturn(Optional.of(member2));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(Arrays.asList(member1, member2));

        partyMatchmakingService.cancelQueue(2L);

        verify(matchmakingQueueService, times(2)).dequeue(anyLong());
        verify(partyRepository).save(argThat(p -> p.getStatus().equals("IDLE")));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // IS PARTY IN QUEUE
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isPartyInQueue: returns true when party is in queue")
    void isPartyInQueue_inQueue_returnsTrue() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IN_QUEUE").build();

        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));

        boolean result = partyMatchmakingService.isPartyInQueue(1L);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isPartyInQueue: returns false when party is idle")
    void isPartyInQueue_idle_returnsFalse() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();

        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));

        boolean result = partyMatchmakingService.isPartyInQueue(1L);

        assertThat(result).isFalse();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CAN PARTY QUEUE FOR MODE
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("canPartyQueueForMode: returns true when party size valid")
    void canPartyQueueForMode_validSize_returnsTrue() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).build();

        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(Arrays.asList(member1, member2));
        when(gameModeService.getPlayersPerTeam("2v2")).thenReturn(2);

        boolean result = partyMatchmakingService.canPartyQueueForMode(1L, "2v2");

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("canPartyQueueForMode: returns false when party size exceeds mode")
    void canPartyQueueForMode_tooLarge_returnsFalse() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).build();
        PartyMember member3 = PartyMember.builder().id(3L).partyId(1L).userId(3L).build();

        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L))
                .thenReturn(Arrays.asList(member1, member2, member3));
        when(gameModeService.getPlayersPerTeam("2v2")).thenReturn(2);

        boolean result = partyMatchmakingService.canPartyQueueForMode(1L, "2v2");

        assertThat(result).isFalse();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MULTI-ACCOUNT MATCHMAKING SCENARIOS
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("multiAccount: 2-player party queues for 2v2 with fill, matches with 2 solo players")
    void multiAccount_partyQueueWithFill_matchesWithSolo() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();

        PartyMatchmakingRequest request = new PartyMatchmakingRequest();
        request.setGameMode("2v2");
        request.setAllowFill(true);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(Arrays.asList(member1, member2));
        when(gameModeService.getPlayersPerTeam("2v2")).thenReturn(2);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));

        partyMatchmakingService.queueParty(1L, request);

        // Verify both party members are queued (can match with solo players)
        verify(matchmakingQueueService).enqueue(1L, "2v2");
        verify(matchmakingQueueService).enqueue(2L, "2v2");
        verify(partyRepository).save(argThat(p -> p.getStatus().equals("IN_QUEUE")));
    }

    @Test
    @DisplayName("multiAccount: Full 2v2 party queues without fill, waits for another full party")
    void multiAccount_fullPartyNoFill_waitsForAnotherParty() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();

        PartyMatchmakingRequest request = new PartyMatchmakingRequest();
        request.setGameMode("2v2");
        request.setAllowFill(false); // Must match with another 2v2 party

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(Arrays.asList(member1, member2));
        when(gameModeService.getPlayersPerTeam("2v2")).thenReturn(2);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));

        partyMatchmakingService.queueParty(1L, request);

        // Both members are queued, but matchmaking system should match parties together
        verify(matchmakingQueueService, times(2)).enqueue(anyLong(), eq("2v2"));
        verify(partyRepository).save(argThat(p -> p.getStatus().equals("IN_QUEUE")));
    }

    @Test
    @DisplayName("multiAccount: 4-player party queues for 5v5 with fill")
    void multiAccount_fourPlayerParty_5v5_withFill() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();
        PartyMember member3 = PartyMember.builder().id(3L).partyId(1L).userId(3L).isLeader(false).build();
        PartyMember member4 = PartyMember.builder().id(4L).partyId(1L).userId(4L).isLeader(false).build();

        PartyMatchmakingRequest request = new PartyMatchmakingRequest();
        request.setGameMode("5v5");
        request.setAllowFill(true);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L))
                .thenReturn(Arrays.asList(member1, member2, member3, member4));
        when(gameModeService.getPlayersPerTeam("5v5")).thenReturn(5);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(userRepository.findById(3L)).thenReturn(Optional.of(user3));
        when(userRepository.findById(4L)).thenReturn(Optional.of(user4));

        partyMatchmakingService.queueParty(1L, request);

        // All 4 members queued, need 1 more solo player to fill
        verify(matchmakingQueueService, times(4)).enqueue(anyLong(), eq("5v5"));
        verify(partyRepository).save(argThat(p -> p.getStatus().equals("IN_QUEUE")));
    }
}
