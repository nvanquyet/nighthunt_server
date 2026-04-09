package com.nighthunt.friend.service;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.friend.dto.FriendResponse;
import com.nighthunt.friend.dto.FriendRequestResponse;
import com.nighthunt.friend.entity.Friend;
import com.nighthunt.friend.entity.FriendRequest;
import com.nighthunt.friend.entity.BlockedUser;
import com.nighthunt.friend.repository.FriendRepository;
import com.nighthunt.friend.repository.FriendRequestRepository;
import com.nighthunt.friend.repository.BlockedUserRepository;
import com.nighthunt.game.websocket.port.ConnectionManager;
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
 * Unit tests for {@link FriendService}.
 * Tests friend operations: add, remove, block, unblock.
 */
@ExtendWith(MockitoExtension.class)
class FriendServiceTest {

    @Mock FriendRepository friendRepository;
    @Mock FriendRequestRepository friendRequestRepository;
    @Mock BlockedUserRepository blockedUserRepository;
    @Mock UserRepository userRepository;
    @Mock ConnectionManager connectionManager;

    @InjectMocks FriendService friendService;

    private User user1;
    private User user2;
    private User user3;

    @BeforeEach
    void setUp() {
        user1 = User.builder().id(1L).username("alice").email("alice@test.com").build();
        user2 = User.builder().id(2L).username("bob").email("bob@test.com").build();
        user3 = User.builder().id(3L).username("charlie").email("charlie@test.com").build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GET FRIENDS
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getFriends: returns empty list when user has no friends")
    void getFriends_noFriends_returnsEmptyList() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(friendRepository.findByUserId(1L)).thenReturn(List.of());

        List<FriendResponse> friends = friendService.getFriends(1L);

        assertThat(friends).isEmpty();
        verify(friendRepository).findByUserId(1L);
    }

    @Test
    @DisplayName("getFriends: returns list of friends with correct data")
    void getFriends_hasFriends_returnsList() {
        Friend friend1 = Friend.builder()
                .id(1L).userId(1L).friendId(2L)
                .createdAt(LocalDateTime.now())
                .build();
        Friend friend2 = Friend.builder()
                .id(2L).userId(1L).friendId(3L)
                .createdAt(LocalDateTime.now())
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(friendRepository.findByUserId(1L)).thenReturn(Arrays.asList(friend1, friend2));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(userRepository.findById(3L)).thenReturn(Optional.of(user3));

        List<FriendResponse> friends = friendService.getFriends(1L);

        assertThat(friends).hasSize(2);
        assertThat(friends.get(0).getFriendUsername()).isEqualTo("bob");
        assertThat(friends.get(1).getFriendUsername()).isEqualTo("charlie");
    }

    @Test
    @DisplayName("getFriends: throws exception when user not found")
    void getFriends_userNotFound_throwsException() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> friendService.getFriends(999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("User not found");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // REMOVE FRIEND
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("removeFriend: successfully removes bidirectional friendship")
    void removeFriend_success_removesBothDirections() {
        Friend friendship1 = Friend.builder().id(1L).userId(1L).friendId(2L).build();
        Friend friendship2 = Friend.builder().id(2L).userId(2L).friendId(1L).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(friendRepository.findByUserIdAndFriendId(1L, 2L)).thenReturn(Optional.of(friendship1));
        when(friendRepository.findByUserIdAndFriendId(2L, 1L)).thenReturn(Optional.of(friendship2));

        friendService.removeFriend(1L, 2L);

        verify(friendRepository).delete(friendship1);
        verify(friendRepository).delete(friendship2);
        verify(connectionManager, times(2)).sendToUser(anyLong(), eq("friend.removed"), any());
    }

    @Test
    @DisplayName("removeFriend: throws exception when friendship not found")
    void removeFriend_notFound_throwsException() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(friendRepository.findByUserIdAndFriendId(1L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> friendService.removeFriend(1L, 2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Friendship not found");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SEND FRIEND REQUEST
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("sendFriendRequest: successfully creates request")
    void sendFriendRequest_success_createsRequest() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(friendRequestRepository.findBySenderIdAndReceiverId(1L, 2L)).thenReturn(Optional.empty());
        when(friendRepository.findByUserIdAndFriendId(1L, 2L)).thenReturn(Optional.empty());
        when(blockedUserRepository.findByUserIdAndBlockedUserId(2L, 1L)).thenReturn(Optional.empty());

        FriendRequest savedRequest = FriendRequest.builder()
                .id(1L).senderId(1L).receiverId(2L)
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .build();
        when(friendRequestRepository.save(any(FriendRequest.class))).thenReturn(savedRequest);

        FriendRequestResponse response = friendService.sendFriendRequest(1L, 2L);

        assertThat(response.getSenderId()).isEqualTo(1L);
        assertThat(response.getReceiverId()).isEqualTo(2L);
        assertThat(response.getStatus()).isEqualTo("PENDING");
        verify(connectionManager).sendToUser(2L, "friend.request.received", any());
    }

    @Test
    @DisplayName("sendFriendRequest: cannot send to self")
    void sendFriendRequest_toSelf_throwsException() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));

        assertThatThrownBy(() -> friendService.sendFriendRequest(1L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot send friend request to yourself");
    }

    @Test
    @DisplayName("sendFriendRequest: cannot send if already friends")
    void sendFriendRequest_alreadyFriends_throwsException() {
        Friend existing = Friend.builder().id(1L).userId(1L).friendId(2L).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(friendRepository.findByUserIdAndFriendId(1L, 2L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> friendService.sendFriendRequest(1L, 2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Already friends");
    }

    @Test
    @DisplayName("sendFriendRequest: cannot send if already pending")
    void sendFriendRequest_alreadyPending_throwsException() {
        FriendRequest existing = FriendRequest.builder()
                .id(1L).senderId(1L).receiverId(2L).status("PENDING").build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(friendRequestRepository.findBySenderIdAndReceiverId(1L, 2L))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> friendService.sendFriendRequest(1L, 2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Friend request already pending");
    }

    @Test
    @DisplayName("sendFriendRequest: cannot send if blocked by target user")
    void sendFriendRequest_targetBlockedSender_throwsException() {
        BlockedUser block = BlockedUser.builder()
                .id(1L).userId(2L).blockedUserId(1L).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(friendRequestRepository.findBySenderIdAndReceiverId(1L, 2L)).thenReturn(Optional.empty());
        when(friendRepository.findByUserIdAndFriendId(1L, 2L)).thenReturn(Optional.empty());
        when(blockedUserRepository.findByUserIdAndBlockedUserId(2L, 1L))
                .thenReturn(Optional.of(block));

        assertThatThrownBy(() -> friendService.sendFriendRequest(1L, 2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot send friend request to this user");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ACCEPT FRIEND REQUEST
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("acceptFriendRequest: successfully creates bidirectional friendship")
    void acceptFriendRequest_success_createsFriendship() {
        FriendRequest request = FriendRequest.builder()
                .id(1L).senderId(1L).receiverId(2L)
                .status("PENDING")
                .build();

        when(friendRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));

        Friend savedFriend1 = Friend.builder().id(1L).userId(1L).friendId(2L).build();
        Friend savedFriend2 = Friend.builder().id(2L).userId(2L).friendId(1L).build();
        when(friendRepository.save(any(Friend.class)))
                .thenReturn(savedFriend1)
                .thenReturn(savedFriend2);

        friendService.acceptFriendRequest(2L, 1L);

        verify(friendRequestRepository).save(argThat(req -> 
            req.getStatus().equals("ACCEPTED")));
        verify(friendRepository, times(2)).save(any(Friend.class));
        verify(connectionManager).sendToUser(1L, "friend.request.accepted", any());
    }

    @Test
    @DisplayName("acceptFriendRequest: throws exception if not receiver")
    void acceptFriendRequest_notReceiver_throwsException() {
        FriendRequest request = FriendRequest.builder()
                .id(1L).senderId(1L).receiverId(2L)
                .status("PENDING")
                .build();

        when(friendRequestRepository.findById(1L)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> friendService.acceptFriendRequest(3L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Not authorized");
    }

    @Test
    @DisplayName("acceptFriendRequest: throws exception if already accepted")
    void acceptFriendRequest_alreadyAccepted_throwsException() {
        FriendRequest request = FriendRequest.builder()
                .id(1L).senderId(1L).receiverId(2L)
                .status("ACCEPTED")
                .build();

        when(friendRequestRepository.findById(1L)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> friendService.acceptFriendRequest(2L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Friend request already processed");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // REJECT FRIEND REQUEST
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("rejectFriendRequest: successfully rejects request")
    void rejectFriendRequest_success() {
        FriendRequest request = FriendRequest.builder()
                .id(1L).senderId(1L).receiverId(2L)
                .status("PENDING")
                .build();

        when(friendRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));

        friendService.rejectFriendRequest(2L, 1L);

        verify(friendRequestRepository).save(argThat(req -> 
            req.getStatus().equals("REJECTED")));
        verify(connectionManager).sendToUser(1L, "friend.request.rejected", any());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BLOCK USER
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("blockUser: successfully blocks user and removes friendship")
    void blockUser_success_removesExistingFriendship() {
        Friend friendship1 = Friend.builder().id(1L).userId(1L).friendId(2L).build();
        Friend friendship2 = Friend.builder().id(2L).userId(2L).friendId(1L).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(friendRepository.findByUserIdAndFriendId(1L, 2L)).thenReturn(Optional.of(friendship1));
        when(friendRepository.findByUserIdAndFriendId(2L, 1L)).thenReturn(Optional.of(friendship2));
        when(blockedUserRepository.findByUserIdAndBlockedUserId(1L, 2L)).thenReturn(Optional.empty());

        BlockedUser savedBlock = BlockedUser.builder()
                .id(1L).userId(1L).blockedUserId(2L).build();
        when(blockedUserRepository.save(any(BlockedUser.class))).thenReturn(savedBlock);

        friendService.blockUser(1L, 2L);

        verify(friendRepository).delete(friendship1);
        verify(friendRepository).delete(friendship2);
        verify(blockedUserRepository).save(any(BlockedUser.class));
    }

    @Test
    @DisplayName("blockUser: cannot block self")
    void blockUser_self_throwsException() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));

        assertThatThrownBy(() -> friendService.blockUser(1L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot block yourself");
    }

    @Test
    @DisplayName("blockUser: cannot block already blocked user")
    void blockUser_alreadyBlocked_throwsException() {
        BlockedUser existing = BlockedUser.builder()
                .id(1L).userId(1L).blockedUserId(2L).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(blockedUserRepository.findByUserIdAndBlockedUserId(1L, 2L))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> friendService.blockUser(1L, 2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("User already blocked");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UNBLOCK USER
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("unblockUser: successfully unblocks user")
    void unblockUser_success() {
        BlockedUser block = BlockedUser.builder()
                .id(1L).userId(1L).blockedUserId(2L).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(blockedUserRepository.findByUserIdAndBlockedUserId(1L, 2L))
                .thenReturn(Optional.of(block));

        friendService.unblockUser(1L, 2L);

        verify(blockedUserRepository).delete(block);
    }

    @Test
    @DisplayName("unblockUser: throws exception if user not blocked")
    void unblockUser_notBlocked_throwsException() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(blockedUserRepository.findByUserIdAndBlockedUserId(1L, 2L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> friendService.unblockUser(1L, 2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("User not blocked");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MULTI-ACCOUNT SCENARIOS
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("multiAccount: A sends request to B, B accepts, both become friends")
    void multiAccount_friendRequestFlow_success() {
        // Step 1: Alice sends request to Bob
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(friendRequestRepository.findBySenderIdAndReceiverId(1L, 2L)).thenReturn(Optional.empty());
        when(friendRepository.findByUserIdAndFriendId(1L, 2L)).thenReturn(Optional.empty());
        when(blockedUserRepository.findByUserIdAndBlockedUserId(2L, 1L)).thenReturn(Optional.empty());

        FriendRequest savedRequest = FriendRequest.builder()
                .id(1L).senderId(1L).receiverId(2L).status("PENDING").build();
        when(friendRequestRepository.save(any(FriendRequest.class))).thenReturn(savedRequest);

        FriendRequestResponse requestResponse = friendService.sendFriendRequest(1L, 2L);
        assertThat(requestResponse.getStatus()).isEqualTo("PENDING");

        // Step 2: Bob accepts request
        when(friendRequestRepository.findById(1L)).thenReturn(Optional.of(savedRequest));
        Friend friend1 = Friend.builder().id(1L).userId(1L).friendId(2L).build();
        Friend friend2 = Friend.builder().id(2L).userId(2L).friendId(1L).build();
        when(friendRepository.save(any(Friend.class))).thenReturn(friend1).thenReturn(friend2);

        friendService.acceptFriendRequest(2L, 1L);

        // Verify bidirectional friendship created
        verify(friendRepository, times(2)).save(any(Friend.class));
        verify(connectionManager).sendToUser(1L, "friend.request.accepted", any());
    }

    @Test
    @DisplayName("multiAccount: A sends request to B, B rejects, no friendship created")
    void multiAccount_friendRequestRejected_noFriendship() {
        FriendRequest request = FriendRequest.builder()
                .id(1L).senderId(1L).receiverId(2L).status("PENDING").build();

        when(friendRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));

        friendService.rejectFriendRequest(2L, 1L);

        verify(friendRepository, never()).save(any(Friend.class));
        verify(friendRequestRepository).save(argThat(req -> req.getStatus().equals("REJECTED")));
    }

    @Test
    @DisplayName("multiAccount: A and B are friends, A blocks B, friendship removed")
    void multiAccount_blockRemovesFriendship() {
        Friend friendship1 = Friend.builder().id(1L).userId(1L).friendId(2L).build();
        Friend friendship2 = Friend.builder().id(2L).userId(2L).friendId(1L).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(friendRepository.findByUserIdAndFriendId(1L, 2L)).thenReturn(Optional.of(friendship1));
        when(friendRepository.findByUserIdAndFriendId(2L, 1L)).thenReturn(Optional.of(friendship2));
        when(blockedUserRepository.findByUserIdAndBlockedUserId(1L, 2L)).thenReturn(Optional.empty());

        BlockedUser savedBlock = BlockedUser.builder().id(1L).userId(1L).blockedUserId(2L).build();
        when(blockedUserRepository.save(any(BlockedUser.class))).thenReturn(savedBlock);

        friendService.blockUser(1L, 2L);

        verify(friendRepository).delete(friendship1);
        verify(friendRepository).delete(friendship2);
        verify(blockedUserRepository).save(any(BlockedUser.class));
    }

    @Test
    @DisplayName("multiAccount: A blocks B, B cannot send friend request to A")
    void multiAccount_blockedUserCannotSendRequest() {
        BlockedUser block = BlockedUser.builder().id(1L).userId(1L).blockedUserId(2L).build();

        when(userRepository.findById(2L)).thenReturn(Optional.of(user2));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user1));
        when(friendRequestRepository.findBySenderIdAndReceiverId(2L, 1L)).thenReturn(Optional.empty());
        when(friendRepository.findByUserIdAndFriendId(2L, 1L)).thenReturn(Optional.empty());
        when(blockedUserRepository.findByUserIdAndBlockedUserId(1L, 2L))
                .thenReturn(Optional.of(block));

        assertThatThrownBy(() -> friendService.sendFriendRequest(2L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot send friend request to this user");
    }
}
