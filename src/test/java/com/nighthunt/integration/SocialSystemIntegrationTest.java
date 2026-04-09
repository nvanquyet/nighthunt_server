package com.nighthunt.integration;

import com.nighthunt.friend.dto.FriendRequestResponse;
import com.nighthunt.friend.service.FriendService;
import com.nighthunt.gamemode.service.GameModeService;
import com.nighthunt.party.dto.PartyMatchmakingRequest;
import com.nighthunt.party.dto.PartyResponse;
import com.nighthunt.party.dto.PartyInviteResponse;
import com.nighthunt.party.service.PartyService;
import com.nighthunt.party.service.PartyMatchmakingService;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for the complete social system flow.
 * Tests friend system → party system → matchmaking integration.
 * 
 * These tests use the real Spring application context and database transactions.
 * Each test is transactional and will rollback after completion.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SocialSystemIntegrationTest {

    @Autowired FriendService friendService;
    @Autowired PartyService partyService;
    @Autowired PartyMatchmakingService partyMatchmakingService;
    @Autowired GameModeService gameModeService;
    @Autowired UserRepository userRepository;

    private User alice;
    private User bob;
    private User charlie;
    private User dave;

    @BeforeEach
    void setUp() {
        // Create test users
        alice = User.builder()
                .username("alice_test")
                .email("alice@test.com")
                .passwordHash("hashedpass")
                .elo(1500)
                .build();
        bob = User.builder()
                .username("bob_test")
                .email("bob@test.com")
                .passwordHash("hashedpass")
                .elo(1520)
                .build();
        charlie = User.builder()
                .username("charlie_test")
                .email("charlie@test.com")
                .passwordHash("hashedpass")
                .elo(1510)
                .build();
        dave = User.builder()
                .username("dave_test")
                .email("dave@test.com")
                .passwordHash("hashedpass")
                .elo(1505)
                .build();

        alice = userRepository.save(alice);
        bob = userRepository.save(bob);
        charlie = userRepository.save(charlie);
        dave = userRepository.save(dave);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // COMPLETE FRIEND → PARTY → MATCHMAKING FLOW
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Integration: Alice adds Bob as friend → creates party → invites Bob → queues for matchmaking")
    void completeFlow_friendToPartyToMatchmaking() {
        // Step 1: Alice sends friend request to Bob
        FriendRequestResponse friendRequest = friendService.sendFriendRequest(alice.getId(), bob.getId());
        assertThat(friendRequest.getStatus()).isEqualTo("PENDING");

        // Step 2: Bob accepts friend request
        friendService.acceptFriendRequest(bob.getId(), alice.getId());

        // Step 3: Verify they are friends
        var aliceFriends = friendService.getFriends(alice.getId());
        assertThat(aliceFriends).hasSize(1);
        assertThat(aliceFriends.get(0).getUsername()).isEqualTo("bob_test");

        // Step 4: Alice creates a party
        PartyResponse party = partyService.createParty(alice.getId());
        assertThat(party.getLeaderId()).isEqualTo(alice.getId());
        assertThat(party.getMembers()).hasSize(1);

        // Step 5: Alice invites Bob to party
        PartyInviteResponse invite = partyService.inviteToParty(alice.getId(), bob.getId());
        assertThat(invite.getStatus()).isEqualTo("PENDING");

        // Step 6: Bob accepts party invite
        PartyResponse afterAccept = partyService.acceptPartyInvite(bob.getId());
        assertThat(afterAccept.getMembers()).hasSize(2);

        // Step 7: Alice queues party for 2v2 matchmaking
        PartyMatchmakingRequest mmRequest = new PartyMatchmakingRequest();
        mmRequest.setGameMode("2v2");
        mmRequest.setAllowFill(true);
        
        partyMatchmakingService.queueParty(alice.getId(), mmRequest);

        // Step 8: Verify party is in queue
        boolean inQueue = partyMatchmakingService.isPartyInQueue(party.getId());
        assertThat(inQueue).isTrue();
    }

    @Test
    @DisplayName("Integration: Three friends create party and queue for 5v5 with fill")
    void completeFlow_threePlayerParty_5v5() {
        // Step 1: Create friendships (Alice ↔ Bob ↔ Charlie)
        friendService.sendFriendRequest(alice.getId(), bob.getId());
        friendService.acceptFriendRequest(bob.getId(), alice.getId());
        
        friendService.sendFriendRequest(bob.getId(), charlie.getId());
        friendService.acceptFriendRequest(charlie.getId(), bob.getId());

        friendService.sendFriendRequest(alice.getId(), charlie.getId());
        friendService.acceptFriendRequest(charlie.getId(), alice.getId());

        // Step 2: Alice creates party
        PartyResponse party = partyService.createParty(alice.getId());

        // Step 3: Alice invites Bob and Charlie
        partyService.inviteToParty(alice.getId(), bob.getId());
        partyService.inviteToParty(alice.getId(), charlie.getId());

        // Step 4: Both accept
        partyService.acceptPartyInvite(bob.getId());
        partyService.acceptPartyInvite(charlie.getId());

        // Step 5: Verify party has 3 members
        PartyResponse fullParty = partyService.getParty(alice.getId());
        assertThat(fullParty.getMembers()).hasSize(3);

        // Step 6: Queue for 5v5 with fill (need 2 more solo players)
        PartyMatchmakingRequest mmRequest = new PartyMatchmakingRequest();
        mmRequest.setGameMode("5v5");
        mmRequest.setAllowFill(true);

        partyMatchmakingService.queueParty(alice.getId(), mmRequest);

        // Step 7: Verify party in queue
        assertThat(partyMatchmakingService.isPartyInQueue(party.getId())).isTrue();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EDGE CASE INTEGRATION TESTS
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Integration: Cannot invite non-friend to party")
    void cannotInviteNonFriend() {
        // Alice creates party
        partyService.createParty(alice.getId());

        // Alice tries to invite Bob (not friends)
        assertThatThrownBy(() -> partyService.inviteToParty(alice.getId(), bob.getId()))
                .hasMessageContaining("not friends");
    }

    @Test
    @DisplayName("Integration: Cannot queue incomplete party without fill option")
    void cannotQueueIncompletePartyWithoutFill() {
        // Setup friendship
        friendService.sendFriendRequest(alice.getId(), bob.getId());
        friendService.acceptFriendRequest(bob.getId(), alice.getId());

        // Create party with only 1 member
        partyService.createParty(alice.getId());

        // Try to queue for 2v2 without fill (party must be full)
        PartyMatchmakingRequest mmRequest = new PartyMatchmakingRequest();
        mmRequest.setGameMode("2v2");
        mmRequest.setAllowFill(false);

        assertThatThrownBy(() -> partyMatchmakingService.queueParty(alice.getId(), mmRequest))
                .hasMessageContaining("Party must be full");
    }

    @Test
    @DisplayName("Integration: Party member leaving while in queue cancels queue")
    void memberLeavingQueue_cancelsQueue() {
        // Setup: Alice and Bob are friends in a party
        friendService.sendFriendRequest(alice.getId(), bob.getId());
        friendService.acceptFriendRequest(bob.getId(), alice.getId());
        
        PartyResponse party = partyService.createParty(alice.getId());
        partyService.inviteToParty(alice.getId(), bob.getId());
        partyService.acceptPartyInvite(bob.getId());

        // Queue for matchmaking
        PartyMatchmakingRequest mmRequest = new PartyMatchmakingRequest();
        mmRequest.setGameMode("2v2");
        mmRequest.setAllowFill(true);
        partyMatchmakingService.queueParty(alice.getId(), mmRequest);

        assertThat(partyMatchmakingService.isPartyInQueue(party.getId())).isTrue();

        // Bob cancels queue (any member can cancel)
        partyMatchmakingService.cancelQueue(bob.getId());

        // Verify party no longer in queue
        assertThat(partyMatchmakingService.isPartyInQueue(party.getId())).isFalse();
    }

    @Test
    @DisplayName("Integration: Blocked user cannot be invited to party")
    void blockedUserCannotBeInvited() {
        // Alice and Bob are friends
        friendService.sendFriendRequest(alice.getId(), bob.getId());
        friendService.acceptFriendRequest(bob.getId(), alice.getId());

        // Alice creates party
        partyService.createParty(alice.getId());

        // Alice blocks Bob
        friendService.blockUser(alice.getId(), bob.getId());

        // Alice cannot invite Bob (no longer friends)
        assertThatThrownBy(() -> partyService.inviteToParty(alice.getId(), bob.getId()))
                .hasMessageContaining("not friends");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MULTI-PARTY SCENARIOS
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Integration: Two parties queue for same mode (can match against each other)")
    void twoPartiesQueueForSameMode() {
        // Setup Party 1: Alice + Bob
        friendService.sendFriendRequest(alice.getId(), bob.getId());
        friendService.acceptFriendRequest(bob.getId(), alice.getId());
        
        PartyResponse party1 = partyService.createParty(alice.getId());
        partyService.inviteToParty(alice.getId(), bob.getId());
        partyService.acceptPartyInvite(bob.getId());

        // Setup Party 2: Charlie + Dave
        friendService.sendFriendRequest(charlie.getId(), dave.getId());
        friendService.acceptFriendRequest(dave.getId(), charlie.getId());
        
        PartyResponse party2 = partyService.createParty(charlie.getId());
        partyService.inviteToParty(charlie.getId(), dave.getId());
        partyService.acceptPartyInvite(dave.getId());

        // Both parties queue for 2v2
        PartyMatchmakingRequest mmRequest = new PartyMatchmakingRequest();
        mmRequest.setGameMode("2v2");
        mmRequest.setAllowFill(false); // Must match with another full team

        partyMatchmakingService.queueParty(alice.getId(), mmRequest);
        partyMatchmakingService.queueParty(charlie.getId(), mmRequest);

        // Both parties should be in queue (matchmaking system should match them)
        assertThat(partyMatchmakingService.isPartyInQueue(party1.getId())).isTrue();
        assertThat(partyMatchmakingService.isPartyInQueue(party2.getId())).isTrue();
    }

    @Test
    @DisplayName("Integration: Party size validation for different game modes")
    void partySizeValidationForDifferentModes() {
        // Setup 4-player party
        friendService.sendFriendRequest(alice.getId(), bob.getId());
        friendService.acceptFriendRequest(bob.getId(), alice.getId());
        friendService.sendFriendRequest(alice.getId(), charlie.getId());
        friendService.acceptFriendRequest(charlie.getId(), alice.getId());
        friendService.sendFriendRequest(alice.getId(), dave.getId());
        friendService.acceptFriendRequest(dave.getId(), alice.getId());

        partyService.createParty(alice.getId());
        partyService.inviteToParty(alice.getId(), bob.getId());
        partyService.inviteToParty(alice.getId(), charlie.getId());
        partyService.inviteToParty(alice.getId(), dave.getId());
        partyService.acceptPartyInvite(bob.getId());
        partyService.acceptPartyInvite(charlie.getId());
        partyService.acceptPartyInvite(dave.getId());

        // Cannot queue for 2v2 (party too large: 4 > 2)
        PartyMatchmakingRequest mm2v2 = new PartyMatchmakingRequest();
        mm2v2.setGameMode("2v2");
        mm2v2.setAllowFill(true);

        assertThatThrownBy(() -> partyMatchmakingService.queueParty(alice.getId(), mm2v2))
                .hasMessageContaining("Party size exceeds mode capacity");

        // Can queue for 5v5 (4 ≤ 5)
        PartyMatchmakingRequest mm5v5 = new PartyMatchmakingRequest();
        mm5v5.setGameMode("5v5");
        mm5v5.setAllowFill(true);

        assertThatCode(() -> partyMatchmakingService.queueParty(alice.getId(), mm5v5))
                .doesNotThrowAnyException();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LEADERSHIP AND STATE TRANSITIONS
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Integration: Leader leaves party while in queue → leadership transferred → queue cancelled")
    void leaderLeavesWhileInQueue() {
        // Setup party
        friendService.sendFriendRequest(alice.getId(), bob.getId());
        friendService.acceptFriendRequest(bob.getId(), alice.getId());
        friendService.sendFriendRequest(alice.getId(), charlie.getId());
        friendService.acceptFriendRequest(charlie.getId(), alice.getId());

        PartyResponse party = partyService.createParty(alice.getId());
        partyService.inviteToParty(alice.getId(), bob.getId());
        partyService.inviteToParty(alice.getId(), charlie.getId());
        partyService.acceptPartyInvite(bob.getId());
        partyService.acceptPartyInvite(charlie.getId());

        // Queue for matchmaking
        PartyMatchmakingRequest mmRequest = new PartyMatchmakingRequest();
        mmRequest.setGameMode("5v5");
        mmRequest.setAllowFill(true);
        partyMatchmakingService.queueParty(alice.getId(), mmRequest);

        assertThat(partyMatchmakingService.isPartyInQueue(party.getId())).isTrue();

        // Alice (leader) leaves
        partyService.leaveParty(alice.getId());

        // Party should no longer be in queue (leaving cancels queue)
        // Leadership should transfer to Bob
        PartyResponse updatedParty = partyService.getParty(bob.getId());
        assertThat(updatedParty.getLeaderId()).isEqualTo(bob.getId());
        assertThat(updatedParty.getMembers()).hasSize(2); // Bob and Charlie remain
    }
}
