package com.nighthunt.friend.service;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.friend.dto.AddFriendRequest;
import com.nighthunt.friend.dto.FriendDTO;
import com.nighthunt.friend.dto.FriendRequestDTO;
import com.nighthunt.friend.entity.BlockedUser;
import com.nighthunt.friend.entity.Friend;
import com.nighthunt.friend.entity.FriendRequest;
import com.nighthunt.friend.repository.BlockedUserRepository;
import com.nighthunt.friend.repository.FriendRepository;
import com.nighthunt.friend.repository.FriendRequestRepository;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Friend Service - Manages friend relationships, requests, and blocking.
 * 
 * Friend system workflow:
 * 1. User A sends friend request to User B → Creates FriendRequest (PENDING)
 * 2. User B accepts request → Creates 2 Friend records (A→B, B→A) + Updates FriendRequest (ACCEPTED)
 * 3. User A can see B in friend list, B can see A in friend list
 * 4. Either user can remove friend → Deletes both Friend records
 * 5. Either user can block friend → Creates BlockedUser + Deletes both Friend records
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FriendService {

    private final FriendRepository friendRepository;
    private final FriendRequestRepository friendRequestRepository;
    private final BlockedUserRepository blockedUserRepository;
    private final UserRepository userRepository;
    private final com.nighthunt.messaging.service.MessageBrokerService messageBrokerService;
    private final FriendCacheService friendCacheService;

    // ──────────────────────────────────────────────────────────────────────────
    // FRIEND LIST
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Get all friends of the current user.
     * Returns friend list with online status and current activity.
     */
    @Transactional(readOnly = true)
    public List<FriendDTO> getFriends(Long userId) {
        List<Friend> friendRecords = friendRepository.findByUserId(userId);
        
        return friendRecords.stream()
                .map(this::toFriendDTO)
                .collect(Collectors.toList());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // FRIEND REQUEST OPERATIONS
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Send a friend request to another user.
     * 
     * Validations:
     * - Target user exists
     * - Not sending request to self
     * - Not already friends
     * - No pending request already exists
     * - Target user has not blocked requester
     */
    @Transactional
    public FriendRequestDTO sendFriendRequest(Long requesterId, AddFriendRequest request) {
        // Find target user
        User targetUser = findUserByUsernameOrId(request.getUsername(), request.getUserId());
        Long targetUserId = targetUser.getId();

        // Validate: Cannot send request to self
        if (requesterId.equals(targetUserId)) {
            throw new BusinessException(ErrorCodes.FRIEND_REQUEST_SELF, "Cannot send friend request to yourself");
        }

        // Validate: Check if already friends
        if (friendRepository.existsByUserIdAndFriendUserId(requesterId, targetUserId)) {
            throw new BusinessException(ErrorCodes.FRIEND_ALREADY_EXISTS, "Already friends with this user");
        }

        // Validate: Check request in same direction
        FriendRequest sameDirection = friendRequestRepository
            .findByRequesterUserIdAndAddresseeUserId(requesterId, targetUserId)
            .orElse(null);
        if (sameDirection != null && "PENDING".equals(sameDirection.getRequestStatus())) {
            throw new BusinessException(ErrorCodes.FRIEND_REQUEST_ALREADY_SENT, "Friend request already sent");
        }

        // Validate: Check reverse pending request (target already sent request to requester)
        FriendRequest reverseDirection = friendRequestRepository
            .findByRequesterUserIdAndAddresseeUserId(targetUserId, requesterId)
            .orElse(null);
        if (reverseDirection != null && "PENDING".equals(reverseDirection.getRequestStatus())) {
            throw new BusinessException(ErrorCodes.FRIEND_REQUEST_ALREADY_SENT,
                "User already sent you a friend request. Please accept or decline it first.");
        }

        // Validate: Check if target user blocked requester
        if (blockedUserRepository.existsByBlockerUserIdAndBlockedUserId(targetUserId, requesterId)) {
            throw new BusinessException(ErrorCodes.FRIEND_REQUEST_BLOCKED, "Cannot send friend request to this user");
        }

        FriendRequest friendRequest;
        if (sameDirection != null) {
            // Re-open previous DECLINED/CANCELLED/ACCEPTED record in same direction.
            sameDirection.setRequestStatus("PENDING");
            sameDirection.setExpiresAt(LocalDateTime.now().plusDays(30));
            friendRequest = friendRequestRepository.save(sameDirection);
            log.info("Friend request re-opened: {} → {} (requestId={})", requesterId, targetUserId, friendRequest.getId());
        } else {
            // Create new friend request
            friendRequest = FriendRequest.builder()
                .requesterUserId(requesterId)
                .addresseeUserId(targetUserId)
                .requestStatus("PENDING")
                .expiresAt(LocalDateTime.now().plusDays(30)) // 30 days expiry
                .build();

            friendRequest = friendRequestRepository.save(friendRequest);
            log.info("Friend request sent: {} → {}", requesterId, targetUserId);
        }
        
        // Publish WebSocket event to notify addressee
        User requester = findUser(requesterId);
        messageBrokerService.publishFriendRequestReceived(
            targetUserId, 
            requesterId, 
            requester.getUsername(), 
            friendRequest.getId()
        );
        
        return toFriendRequestDTO(friendRequest);
    }

    /**
     * Accept a friend request.
     * Creates bidirectional friendship and updates request status.
     */
    @Transactional
    public FriendDTO acceptFriendRequest(Long addresseeId, Long requestId) {
        FriendRequest request = findFriendRequest(requestId);
        
        // Validate: User is the addressee
        if (!request.getAddresseeUserId().equals(addresseeId)) {
            throw new BusinessException(ErrorCodes.FRIEND_REQUEST_NOT_FOR_YOU, "This request is not for you");
        }

        // Validate: Request is pending
        if (!"PENDING".equals(request.getRequestStatus())) {
            throw new BusinessException(ErrorCodes.FRIEND_REQUEST_NOT_PENDING, "Request is not pending");
        }

        Long requesterId = request.getRequesterUserId();
        
        // Create bidirectional friendship (2 rows)
        Friend friendship1 = Friend.builder()
                .userId(requesterId)
                .friendUserId(addresseeId)
                .friendshipStatus("ACTIVE")
                .build();
        
        Friend friendship2 = Friend.builder()
                .userId(addresseeId)
                .friendUserId(requesterId)
                .friendshipStatus("ACTIVE")
                .build();
        
        friendRepository.save(friendship1);
        friendRepository.save(friendship2);
        
        // Update request status
        request.setRequestStatus("ACCEPTED");
        friendRequestRepository.save(request);

        // Invalidate friend-ID cache for both users
        friendCacheService.evict(requesterId, addresseeId);
        
        log.info("Friend request accepted: {} accepted {}", addresseeId, requesterId);
        
        // Publish WebSocket event to notify requester
        User addressee = findUser(addresseeId);
        messageBrokerService.publishFriendRequestAccepted(
            requesterId, 
            addresseeId, 
            addressee.getUsername()
        );
        
        return toFriendDTO(friendship2);
    }

    /**
     * Decline a friend request.
     */
    @Transactional
    public void declineFriendRequest(Long addresseeId, Long requestId) {
        FriendRequest request = findFriendRequest(requestId);
        
        // Validate: User is the addressee
        if (!request.getAddresseeUserId().equals(addresseeId)) {
            throw new BusinessException(ErrorCodes.FRIEND_REQUEST_NOT_FOR_YOU, "This request is not for you");
        }

        // Validate: Request is pending
        if (!"PENDING".equals(request.getRequestStatus())) {
            throw new BusinessException(ErrorCodes.FRIEND_REQUEST_NOT_PENDING, "Request is not pending");
        }

        // Update request status
        request.setRequestStatus("DECLINED");
        friendRequestRepository.save(request);
        
        log.info("Friend request declined: {} declined {}", addresseeId, request.getRequesterUserId());
        
        // Publish WebSocket event to notify requester
        messageBrokerService.publishFriendRequestDeclined(
            request.getRequesterUserId(), 
            addresseeId
        );
    }

    /**
     * Cancel a friend request (requester can cancel before acceptance).
     */
    @Transactional
    public void cancelFriendRequest(Long requesterId, Long requestId) {
        FriendRequest request = findFriendRequest(requestId);
        
        // Validate: User is the requester
        if (!request.getRequesterUserId().equals(requesterId)) {
            throw new BusinessException(ErrorCodes.FRIEND_REQUEST_NOT_YOURS, "This request is not yours");
        }

        // Validate: Request is pending
        if (!"PENDING".equals(request.getRequestStatus())) {
            throw new BusinessException(ErrorCodes.FRIEND_REQUEST_NOT_PENDING, "Request is not pending");
        }

        // Update request status
        request.setRequestStatus("CANCELLED");
        friendRequestRepository.save(request);
        
        log.info("Friend request cancelled: {} cancelled request to {}", requesterId, request.getAddresseeUserId());

        // Notify the addressee in real time so their incoming-requests list refreshes.
        messageBrokerService.publishFriendRequestCancelled(requesterId, request.getAddresseeUserId());
    }

    /**
     * Get all incoming friend requests (requests received by user).
     */
    @Transactional(readOnly = true)
    public List<FriendRequestDTO> getIncomingRequests(Long userId) {
        List<FriendRequest> requests = friendRequestRepository.findByAddresseeUserIdAndRequestStatus(userId, "PENDING");
        
        return requests.stream()
                .map(this::toFriendRequestDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get all outgoing friend requests (requests sent by user).
     */
    @Transactional(readOnly = true)
    public List<FriendRequestDTO> getOutgoingRequests(Long userId) {
        List<FriendRequest> requests = friendRequestRepository.findByRequesterUserIdAndRequestStatus(userId, "PENDING");
        
        return requests.stream()
                .map(this::toFriendRequestDTO)
                .collect(Collectors.toList());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // FRIEND REMOVAL
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Remove a friend (unfriend).
     * Deletes both sides of friendship.
     */
    @Transactional
    public void removeFriend(Long userId, Long friendUserId) {
        // Validate: Friendship exists
        if (!friendRepository.existsByUserIdAndFriendUserId(userId, friendUserId)) {
            throw new BusinessException(ErrorCodes.FRIEND_NOT_FOUND, "Friend not found");
        }

        // Delete both sides of friendship
        friendRepository.deleteByUserIdAndFriendUserId(userId, friendUserId);
        friendRepository.deleteByUserIdAndFriendUserId(friendUserId, userId);

        // Invalidate friend-ID cache for both users
        friendCacheService.evict(userId, friendUserId);
        
        log.info("Friend removed: {} unfriended {}", userId, friendUserId);
        
        // Publish WebSocket event to notify both users
        messageBrokerService.publishFriendRemoved(userId, friendUserId);
        messageBrokerService.publishFriendRemoved(friendUserId, userId);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BLOCK OPERATIONS
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Block a user.
     * - Creates BlockedUser record
     * - Removes friendship if exists
     * - Deletes any pending friend requests
     */
    @Transactional
    public void blockUser(Long blockerId, Long blockedUserId) {
        // Validate: Cannot block self
        if (blockerId.equals(blockedUserId)) {
            throw new BusinessException(ErrorCodes.BLOCK_SELF, "Cannot block yourself");
        }

        // Validate: User exists
        findUser(blockedUserId);

        // Check if already blocked
        if (blockedUserRepository.existsByBlockerUserIdAndBlockedUserId(blockerId, blockedUserId)) {
            throw new BusinessException(ErrorCodes.BLOCK_ALREADY_EXISTS, "User already blocked");
        }

        // Create block record
        BlockedUser blockedUser = BlockedUser.builder()
                .blockerUserId(blockerId)
                .blockedUserId(blockedUserId)
                .build();
        
        blockedUserRepository.save(blockedUser);
        
        // Remove friendship if exists
        if (friendRepository.existsByUserIdAndFriendUserId(blockerId, blockedUserId)) {
            friendRepository.deleteByUserIdAndFriendUserId(blockerId, blockedUserId);
            friendRepository.deleteByUserIdAndFriendUserId(blockedUserId, blockerId);
            // Invalidate friend-ID cache since friendship was removed
            friendCacheService.evict(blockerId, blockedUserId);
        }

        // Delete pending friend requests (both directions)
        friendRequestRepository.findByRequesterUserIdAndAddresseeUserId(blockerId, blockedUserId)
                .ifPresent(req -> {
                    req.setRequestStatus("CANCELLED");
                    friendRequestRepository.save(req);
                });
        
        friendRequestRepository.findByRequesterUserIdAndAddresseeUserId(blockedUserId, blockerId)
                .ifPresent(req -> {
                    req.setRequestStatus("DECLINED");
                    friendRequestRepository.save(req);
                });
        
        log.info("User blocked: {} blocked {}", blockerId, blockedUserId);
        
        // Publish WebSocket event to notify blocked user (if they are friends)
        messageBrokerService.publishFriendBlocked(blockerId, blockedUserId);
    }

    /**
     * Unblock a user.
     */
    @Transactional
    public void unblockUser(Long blockerId, Long blockedUserId) {
        // Validate: Block exists
        if (!blockedUserRepository.existsByBlockerUserIdAndBlockedUserId(blockerId, blockedUserId)) {
            throw new BusinessException(ErrorCodes.BLOCK_NOT_FOUND, "User is not blocked");
        }

        blockedUserRepository.deleteByBlockerUserIdAndBlockedUserId(blockerId, blockedUserId);
        
        log.info("User unblocked: {} unblocked {}", blockerId, blockedUserId);
    }

    /**
     * Get list of blocked users.
     */
    @Transactional(readOnly = true)
    public List<Long> getBlockedUsers(Long userId) {
        return blockedUserRepository.findBlockedUserIdsByBlockerId(userId);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // HELPER METHODS
    // ──────────────────────────────────────────────────────────────────────────

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.USER_NOT_FOUND, "User not found"));
    }

    private User findUserByUsernameOrId(String username, Long userId) {
        if (username != null && !username.isBlank()) {
            return userRepository.findByUsername(username)
                    .orElseThrow(() -> new BusinessException(ErrorCodes.USER_NOT_FOUND, "User not found"));
        } else if (userId != null) {
            return findUser(userId);
        } else {
            throw new BusinessException(ErrorCodes.FRIEND_REQUEST_INVALID, "Username or userId must be provided");
        }
    }

    private FriendRequest findFriendRequest(Long requestId) {
        return friendRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.FRIEND_REQUEST_NOT_FOUND, "Friend request not found"));
    }

    private FriendDTO toFriendDTO(Friend friend) {
        User friendUser = findUser(friend.getFriendUserId());
        
        return FriendDTO.builder()
                .friendId(friend.getId())
                .userId(friendUser.getId())
                .username(friendUser.getUsername())
                .onlineStatus(friendUser.getOnlineStatus())
                .lastSeenAt(friendUser.getLastSeenAt())
                .friendshipStatus(friend.getFriendshipStatus())
                .currentPartyId(friendUser.getCurrentPartyId())
                .currentRoomId(friendUser.getCurrentRoomId())
                .friendsSince(friend.getCreatedAt())
                .build();
    }

    private FriendRequestDTO toFriendRequestDTO(FriendRequest request) {
        User requester = findUser(request.getRequesterUserId());
        User addressee = findUser(request.getAddresseeUserId());
        
        return FriendRequestDTO.builder()
                .requestId(request.getId())
                .requesterUserId(requester.getId())
                .requesterUsername(requester.getUsername())
                .addresseeUserId(addressee.getId())
                .addresseeUsername(addressee.getUsername())
                .requestStatus(request.getRequestStatus())
                .expiresAt(request.getExpiresAt())
                .createdAt(request.getCreatedAt())
                .build();
    }
}
