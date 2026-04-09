package com.nighthunt.party.service;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.friend.entity.Friend;
import com.nighthunt.friend.repository.FriendRepository;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.party.dto.PartyResponse;
import com.nighthunt.party.dto.PartyInviteResponse;
import com.nighthunt.party.entity.Party;
import com.nighthunt.party.entity.PartyMember;
import com.nighthunt.party.entity.PartyInvite;
import com.nighthunt.party.repository.PartyRepository;
import com.nighthunt.party.repository.PartyMemberRepository;
import com.nighthunt.party.repository.PartyInviteRepository;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PartyService}.
 * Tests party operations: create, invite, accept, kick, leave, transfer leadership.
 */
@ExtendWith(MockitoExtension.class)
class PartyServiceTest {

    @Mock PartyRepository partyRepository;
    @Mock PartyMemberRepository partyMemberRepository;
    @Mock PartyInviteRepository partyInviteRepository;
    @Mock UserRepository userRepository;
    @Mock FriendRepository friendRepository;
    @Mock ConnectionManager connectionManager;

    @InjectMocks PartyService partyService;

    private User user1;
    private User user2;
    private User user3;
    private User user4;

    @BeforeEach
    void setUp() {
        user1 = User.builder().id(1L).username("alice").email("alice@test.com").build();
        user2 = User.builder().id(2L).username("bob").email("bob@test.com").build();
        user3 = User.builder().id(3L).username("charlie").email("charlie@test.com").build();
        user4 = User.builder().id(4L).username("dave").email("dave@test.com").build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CREATE PARTY
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("createParty: successfully creates party with user as leader")
    void createParty_success() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.empty());

        Party savedParty = Party.builder()
                .id(1L).leaderId(1L).status("IDLE")
                .maxMembers(5).createdAt(LocalDateTime.now()).build();
        when(partyRepository.save(any(Party.class))).thenReturn(savedParty);

        PartyMember savedMember = PartyMember.builder()
                .id(1L).partyId(1L).userId(1L)
                .isLeader(true).status("ACTIVE").build();
        when(partyMemberRepository.save(any(PartyMember.class))).thenReturn(savedMember);
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(List.of(savedMember));

        PartyResponse response = partyService.createParty(1L);

        assertThat(response.getLeaderId()).isEqualTo(1L);
        assertThat(response.getStatus()).isEqualTo("IDLE");
        assertThat(response.getMembers()).hasSize(1);
        verify(connectionManager).sendToUser(1L, "party.created", any());
    }

    @Test
    @DisplayName("createParty: throws exception if user already in party")
    void createParty_alreadyInParty_throwsException() {
        PartyMember existingMember = PartyMember.builder()
                .id(1L).partyId(1L).userId(1L).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(existingMember));

        assertThatThrownBy(() -> partyService.createParty(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Already in a party");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GET PARTY
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getParty: returns party info with members")
    void getParty_success() {
        Party party = Party.builder()
                .id(1L).leaderId(1L).status("IDLE")
                .maxMembers(5).createdAt(LocalDateTime.now()).build();

        PartyMember member1 = PartyMember.builder()
                .id(1L).partyId(1L).userId(1L).isLeader(true).status("ACTIVE").build();
        PartyMember member2 = PartyMember.builder()
                .id(2L).partyId(1L).userId(2L).isLeader(false).status("ACTIVE").build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(Arrays.asList(member1, member2));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));

        PartyResponse response = partyService.getParty(1L);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getLeaderId()).isEqualTo(1L);
        assertThat(response.getMembers()).hasSize(2);
    }

    @Test
    @DisplayName("getParty: throws exception if user not in party")
    void getParty_notInParty_throwsException() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> partyService.getParty(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Not in a party");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // INVITE TO PARTY
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("inviteToParty: leader successfully invites friend")
    void inviteToParty_leaderInvitesFriend_success() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").maxMembers(5).build();
        PartyMember leaderMember = PartyMember.builder()
                .id(1L).partyId(1L).userId(1L).isLeader(true).build();
        Friend friendship = Friend.builder().id(1L).userId(1L).friendId(2L).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(leaderMember));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(friendRepository.findByUserIdAndFriendId(1L, 2L)).thenReturn(Optional.of(friendship));
        when(partyMemberRepository.findByUserId(2L)).thenReturn(Optional.empty());
        when(partyInviteRepository.findByPartyIdAndInviteeIdAndStatus(1L, 2L, "PENDING"))
                .thenReturn(Optional.empty());
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(List.of(leaderMember));

        PartyInvite savedInvite = PartyInvite.builder()
                .id(1L).partyId(1L).inviterId(1L).inviteeId(2L)
                .status("PENDING").createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(5)).build();
        when(partyInviteRepository.save(any(PartyInvite.class))).thenReturn(savedInvite);

        PartyInviteResponse response = partyService.inviteToParty(1L, 2L);

        assertThat(response.getInviterId()).isEqualTo(1L);
        assertThat(response.getInviteeId()).isEqualTo(2L);
        assertThat(response.getStatus()).isEqualTo("PENDING");
        verify(connectionManager).sendToUser(2L, "party.invite_received", any());
    }

    @Test
    @DisplayName("inviteToParty: throws exception if not leader")
    void inviteToParty_notLeader_throwsException() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member = PartyMember.builder()
                .id(2L).partyId(1L).userId(2L).isLeader(false).build();

        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(partyMemberRepository.findByUserId(2L)).thenReturn(Optional.of(member));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));

        assertThatThrownBy(() -> partyService.inviteToParty(2L, 3L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only party leader can invite");
    }

    @Test
    @DisplayName("inviteToParty: throws exception if party is full")
    void inviteToParty_partyFull_throwsException() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").maxMembers(2).build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(Arrays.asList(member1, member2));

        assertThatThrownBy(() -> partyService.inviteToParty(1L, 3L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Party is full");
    }

    @Test
    @DisplayName("inviteToParty: throws exception if invitee not friend")
    void inviteToParty_notFriend_throwsException() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").maxMembers(5).build();
        PartyMember leaderMember = PartyMember.builder()
                .id(1L).partyId(1L).userId(1L).isLeader(true).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(leaderMember));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(friendRepository.findByUserIdAndFriendId(1L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> partyService.inviteToParty(1L, 2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Can only invite friends");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ACCEPT PARTY INVITE
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("acceptPartyInvite: successfully joins party")
    void acceptPartyInvite_success() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").maxMembers(5).build();
        PartyInvite invite = PartyInvite.builder()
                .id(1L).partyId(1L).inviterId(1L).inviteeId(2L)
                .status("PENDING").expiresAt(LocalDateTime.now().plusMinutes(5)).build();
        PartyMember leaderMember = PartyMember.builder()
                .id(1L).partyId(1L).userId(1L).isLeader(true).build();

        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(partyMemberRepository.findByUserId(2L)).thenReturn(Optional.empty());
        when(partyInviteRepository.findByInviteeIdAndStatus(2L, "PENDING"))
                .thenReturn(List.of(invite));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(List.of(leaderMember));

        PartyMember savedMember = PartyMember.builder()
                .id(2L).partyId(1L).userId(2L).isLeader(false).status("ACTIVE").build();
        when(partyMemberRepository.save(any(PartyMember.class))).thenReturn(savedMember);

        PartyResponse response = partyService.acceptPartyInvite(2L);

        assertThat(response.getId()).isEqualTo(1L);
        verify(partyInviteRepository).save(argThat(inv -> inv.getStatus().equals("ACCEPTED")));
        verify(connectionManager).sendToUser(eq(1L), eq("party.member_joined"), any());
    }

    @Test
    @DisplayName("acceptPartyInvite: throws exception if no pending invites")
    void acceptPartyInvite_noPendingInvites_throwsException() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(partyMemberRepository.findByUserId(2L)).thenReturn(Optional.empty());
        when(partyInviteRepository.findByInviteeIdAndStatus(2L, "PENDING"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> partyService.acceptPartyInvite(2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No pending party invites");
    }

    @Test
    @DisplayName("acceptPartyInvite: throws exception if invite expired")
    void acceptPartyInvite_inviteExpired_throwsException() {
        PartyInvite expiredInvite = PartyInvite.builder()
                .id(1L).partyId(1L).inviterId(1L).inviteeId(2L)
                .status("PENDING").expiresAt(LocalDateTime.now().minusMinutes(1)).build();

        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(partyMemberRepository.findByUserId(2L)).thenReturn(Optional.empty());
        when(partyInviteRepository.findByInviteeIdAndStatus(2L, "PENDING"))
                .thenReturn(List.of(expiredInvite));

        assertThatThrownBy(() -> partyService.acceptPartyInvite(2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Party invite has expired");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // KICK MEMBER
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("kickMember: leader successfully kicks member")
    void kickMember_leaderKicksMember_success() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember leaderMember = PartyMember.builder()
                .id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember targetMember = PartyMember.builder()
                .id(2L).partyId(1L).userId(2L).isLeader(false).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(leaderMember));
        when(partyMemberRepository.findByUserId(2L)).thenReturn(Optional.of(targetMember));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(List.of(leaderMember));

        PartyResponse response = partyService.kickMember(1L, 2L);

        verify(partyMemberRepository).delete(targetMember);
        verify(connectionManager).sendToUser(2L, "party.member_kicked", any());
    }

    @Test
    @DisplayName("kickMember: throws exception if not leader")
    void kickMember_notLeader_throwsException() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member = PartyMember.builder()
                .id(2L).partyId(1L).userId(2L).isLeader(false).build();

        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(partyMemberRepository.findByUserId(2L)).thenReturn(Optional.of(member));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));

        assertThatThrownBy(() -> partyService.kickMember(2L, 3L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only party leader can kick members");
    }

    @Test
    @DisplayName("kickMember: cannot kick leader")
    void kickMember_cannotKickLeader_throwsException() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember leaderMember = PartyMember.builder()
                .id(1L).partyId(1L).userId(1L).isLeader(true).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(leaderMember));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));

        assertThatThrownBy(() -> partyService.kickMember(1L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot kick yourself");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LEAVE PARTY
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("leaveParty: member successfully leaves party")
    void leaveParty_memberLeaves_success() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember leaderMember = PartyMember.builder()
                .id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember member = PartyMember.builder()
                .id(2L).partyId(1L).userId(2L).isLeader(false).build();

        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(partyMemberRepository.findByUserId(2L)).thenReturn(Optional.of(member));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L))
                .thenReturn(Arrays.asList(leaderMember, member));

        partyService.leaveParty(2L);

        verify(partyMemberRepository).delete(member);
        verify(partyRepository, never()).delete(party); // Party still has leader
    }

    @Test
    @DisplayName("leaveParty: leader leaves, leadership transfers to next member")
    void leaveParty_leaderLeaves_transfersLeadership() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember leaderMember = PartyMember.builder()
                .id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember member2 = PartyMember.builder()
                .id(2L).partyId(1L).userId(2L).isLeader(false).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(leaderMember));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L))
                .thenReturn(Arrays.asList(leaderMember, member2));

        partyService.leaveParty(1L);

        verify(partyMemberRepository).delete(leaderMember);
        verify(partyRepository).save(argThat(p -> p.getLeaderId().equals(2L)));
        verify(partyMemberRepository).save(argThat(m -> 
            m.getUserId().equals(2L) && m.getIsLeader()));
    }

    @Test
    @DisplayName("leaveParty: last member leaves, party is disbanded")
    void leaveParty_lastMemberLeaves_disbands() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember leaderMember = PartyMember.builder()
                .id(1L).partyId(1L).userId(1L).isLeader(true).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(leaderMember));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(List.of(leaderMember));

        partyService.leaveParty(1L);

        verify(partyMemberRepository).delete(leaderMember);
        verify(partyRepository).delete(party);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TRANSFER LEADERSHIP
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("transferLeader: leader successfully transfers to member")
    void transferLeader_success() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember leaderMember = PartyMember.builder()
                .id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember targetMember = PartyMember.builder()
                .id(2L).partyId(1L).userId(2L).isLeader(false).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(leaderMember));
        when(partyMemberRepository.findByUserId(2L)).thenReturn(Optional.of(targetMember));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L))
                .thenReturn(Arrays.asList(leaderMember, targetMember));

        PartyResponse response = partyService.transferLeader(1L, 2L);

        verify(partyRepository).save(argThat(p -> p.getLeaderId().equals(2L)));
        verify(partyMemberRepository, times(2)).save(any(PartyMember.class));
        verify(connectionManager, times(2)).sendToUser(anyLong(), eq("party.leader_changed"), any());
    }

    @Test
    @DisplayName("transferLeader: throws exception if not leader")
    void transferLeader_notLeader_throwsException() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member = PartyMember.builder()
                .id(2L).partyId(1L).userId(2L).isLeader(false).build();

        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(partyMemberRepository.findByUserId(2L)).thenReturn(Optional.of(member));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));

        assertThatThrownBy(() -> partyService.transferLeader(2L, 3L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only party leader can transfer leadership");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MULTI-ACCOUNT SCENARIOS
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("multiAccount: A creates party, invites B, B accepts")
    void multiAccount_createInviteAccept_success() {
        // Step 1: Alice creates party
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.empty());

        Party savedParty = Party.builder().id(1L).leaderId(1L).status("IDLE").maxMembers(5).build();
        when(partyRepository.save(any(Party.class))).thenReturn(savedParty);

        PartyMember savedLeader = PartyMember.builder()
                .id(1L).partyId(1L).userId(1L).isLeader(true).build();
        when(partyMemberRepository.save(any(PartyMember.class)))
                .thenReturn(savedLeader);
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(List.of(savedLeader));

        PartyResponse partyResponse = partyService.createParty(1L);
        assertThat(partyResponse.getLeaderId()).isEqualTo(1L);

        // Step 2: Alice invites Bob
        Friend friendship = Friend.builder().id(1L).userId(1L).friendId(2L).build();
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(savedLeader));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(savedParty));
        when(friendRepository.findByUserIdAndFriendId(1L, 2L)).thenReturn(Optional.of(friendship));
        when(partyMemberRepository.findByUserId(2L)).thenReturn(Optional.empty());
        when(partyInviteRepository.findByPartyIdAndInviteeIdAndStatus(1L, 2L, "PENDING"))
                .thenReturn(Optional.empty());

        PartyInvite savedInvite = PartyInvite.builder()
                .id(1L).partyId(1L).inviterId(1L).inviteeId(2L)
                .status("PENDING").expiresAt(LocalDateTime.now().plusMinutes(5)).build();
        when(partyInviteRepository.save(any(PartyInvite.class))).thenReturn(savedInvite);

        PartyInviteResponse inviteResponse = partyService.inviteToParty(1L, 2L);
        assertThat(inviteResponse.getStatus()).isEqualTo("PENDING");

        // Step 3: Bob accepts invite
        when(partyInviteRepository.findByInviteeIdAndStatus(2L, "PENDING"))
                .thenReturn(List.of(savedInvite));

        PartyMember savedBob = PartyMember.builder()
                .id(2L).partyId(1L).userId(2L).isLeader(false).build();
        when(partyMemberRepository.save(any(PartyMember.class))).thenReturn(savedBob);
        when(partyMemberRepository.findByPartyId(1L))
                .thenReturn(Arrays.asList(savedLeader, savedBob));

        PartyResponse afterAccept = partyService.acceptPartyInvite(2L);
        assertThat(afterAccept.getMembers()).hasSize(2);
    }

    @Test
    @DisplayName("multiAccount: A creates party with 3 members, A leaves, leadership transfers")
    void multiAccount_leaderLeaves_transfersToNext() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember member1 = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember member2 = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();
        PartyMember member3 = PartyMember.builder().id(3L).partyId(1L).userId(3L).isLeader(false).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(member1));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L))
                .thenReturn(Arrays.asList(member1, member2, member3));

        partyService.leaveParty(1L);

        verify(partyRepository).save(argThat(p -> p.getLeaderId().equals(2L)));
        verify(partyMemberRepository).save(argThat(m -> 
            m.getUserId().equals(2L) && m.getIsLeader()));
    }

    @Test
    @DisplayName("multiAccount: Leader kicks member, member is removed")
    void multiAccount_leaderKicksMember() {
        Party party = Party.builder().id(1L).leaderId(1L).status("IDLE").build();
        PartyMember leader = PartyMember.builder().id(1L).partyId(1L).userId(1L).isLeader(true).build();
        PartyMember target = PartyMember.builder().id(2L).partyId(1L).userId(2L).isLeader(false).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(partyMemberRepository.findByUserId(1L)).thenReturn(Optional.of(leader));
        when(partyMemberRepository.findByUserId(2L)).thenReturn(Optional.of(target));
        when(partyRepository.findById(1L)).thenReturn(Optional.of(party));
        when(partyMemberRepository.findByPartyId(1L)).thenReturn(List.of(leader));

        partyService.kickMember(1L, 2L);

        verify(partyMemberRepository).delete(target);
        verify(connectionManager).sendToUser(2L, "party.member_kicked", any());
    }
}
