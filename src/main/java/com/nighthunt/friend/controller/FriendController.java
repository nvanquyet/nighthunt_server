package com.nighthunt.friend.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.friend.dto.AddFriendRequest;
import com.nighthunt.friend.dto.FriendDTO;
import com.nighthunt.friend.dto.FriendRequestDTO;
import com.nighthunt.friend.service.FriendService;
import com.nighthunt.security.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for friend system operations.
 * 
 * Base path: /api/friends
 * 
 * All endpoints require authentication (JWT token).
 */
@RestController
@RequestMapping("/friends")
@RequiredArgsConstructor
public class FriendController {

    private final FriendService friendService;

    // ──────────────────────────────────────────────────────────────────────────
    // FRIEND LIST
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * GET /api/friends
     * Get all friends of the authenticated user.
     * 
     * Response: List of FriendDTO with online status and current activity.
     */
    @GetMapping
    public ApiResponse<List<FriendDTO>> getFriends() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        return ApiResponse.ok(friendService.getFriends(userId));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // FRIEND REQUESTS
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * POST /api/friends/requests
     * Send a friend request to another user.
     * 
     * Request body: { "username": "player123" } or { "userId": 456 }
     * Response: FriendRequestDTO
     */
    @PostMapping("/requests")
    public ApiResponse<FriendRequestDTO> sendFriendRequest(@Valid @RequestBody AddFriendRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        return ApiResponse.ok(friendService.sendFriendRequest(userId, request));
    }

    /**
     * GET /api/friends/requests/incoming
     * Get all incoming friend requests (requests received).
     * 
     * Response: List of FriendRequestDTO (PENDING status only)
     */
    @GetMapping("/requests/incoming")
    public ApiResponse<List<FriendRequestDTO>> getIncomingRequests() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        return ApiResponse.ok(friendService.getIncomingRequests(userId));
    }

    /**
     * GET /api/friends/requests/outgoing
     * Get all outgoing friend requests (requests sent).
     * 
     * Response: List of FriendRequestDTO (PENDING status only)
     */
    @GetMapping("/requests/outgoing")
    public ApiResponse<List<FriendRequestDTO>> getOutgoingRequests() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        return ApiResponse.ok(friendService.getOutgoingRequests(userId));
    }

    /**
     * POST /api/friends/requests/{requestId}/accept
     * Accept a friend request.
     * 
     * Path param: requestId (Long)
     * Response: FriendDTO (newly created friendship)
     */
    @PostMapping("/requests/{requestId}/accept")
    public ApiResponse<FriendDTO> acceptFriendRequest(@PathVariable Long requestId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        return ApiResponse.ok(friendService.acceptFriendRequest(userId, requestId));
    }

    /**
     * POST /api/friends/requests/{requestId}/decline
     * Decline a friend request.
     * 
     * Path param: requestId (Long)
     * Response: Success message
     */
    @PostMapping("/requests/{requestId}/decline")
    public ApiResponse<Void> declineFriendRequest(@PathVariable Long requestId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        friendService.declineFriendRequest(userId, requestId);
        return ApiResponse.okMessage("Friend request declined");
    }

    /**
     * DELETE /api/friends/requests/{requestId}
     * Cancel a friend request (requester only).
     * 
     * Path param: requestId (Long)
     * Response: Success message
     */
    @DeleteMapping("/requests/{requestId}")
    public ApiResponse<Void> cancelFriendRequest(@PathVariable Long requestId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        friendService.cancelFriendRequest(userId, requestId);
        return ApiResponse.okMessage("Friend request cancelled");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // FRIEND REMOVAL
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * DELETE /api/friends/{friendUserId}
     * Remove a friend (unfriend).
     * 
     * Path param: friendUserId (Long)
     * Response: Success message
     */
    @DeleteMapping("/{friendUserId}")
    public ApiResponse<Void> removeFriend(@PathVariable Long friendUserId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        friendService.removeFriend(userId, friendUserId);
        return ApiResponse.okMessage("Friend removed");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BLOCK OPERATIONS
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * POST /api/friends/block/{blockedUserId}
     * Block a user.
     * - Removes friendship if exists
     * - Deletes pending friend requests
     * - User cannot send friend requests or party invites
     * 
     * Path param: blockedUserId (Long)
     * Response: Success message
     */
    @PostMapping("/block/{blockedUserId}")
    public ApiResponse<Void> blockUser(@PathVariable Long blockedUserId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        friendService.blockUser(userId, blockedUserId);
        return ApiResponse.okMessage("User blocked");
    }

    /**
     * DELETE /api/friends/block/{blockedUserId}
     * Unblock a user.
     * 
     * Path param: blockedUserId (Long)
     * Response: Success message
     */
    @DeleteMapping("/block/{blockedUserId}")
    public ApiResponse<Void> unblockUser(@PathVariable Long blockedUserId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        friendService.unblockUser(userId, blockedUserId);
        return ApiResponse.okMessage("User unblocked");
    }

    /**
     * GET /api/friends/blocked
     * Get list of blocked user IDs.
     * 
     * Response: List<Long> (user IDs)
     */
    @GetMapping("/blocked")
    public ApiResponse<List<Long>> getBlockedUsers() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        return ApiResponse.ok(friendService.getBlockedUsers(userId));
    }
}
