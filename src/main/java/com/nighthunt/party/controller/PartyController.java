package com.nighthunt.party.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.party.dto.InviteToPartyRequest;
import com.nighthunt.party.dto.PartyDTO;
import com.nighthunt.party.dto.PartyInvitationDTO;
import com.nighthunt.party.dto.PartyMatchmakingRequest;
import com.nighthunt.party.service.PartyMatchmakingService;
import com.nighthunt.party.service.PartyRoomService;
import com.nighthunt.party.service.PartyService;
import com.nighthunt.room.dto.RoomResponse;
import com.nighthunt.security.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for party system operations.
 * 
 * Base path: /api/party
 * 
 * All endpoints require authentication (JWT token).
 */
@RestController
@RequestMapping("/party")
@RequiredArgsConstructor
public class PartyController {

    private final PartyService partyService;
    private final PartyMatchmakingService partyMatchmakingService;
    private final PartyRoomService partyRoomService;

    // ──────────────────────────────────────────────────────────────────────────
    // PARTY CREATION
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * POST /api/party/create
     * Create a new party. User becomes the host.
     * 
     * Response: PartyDTO
     */
    @PostMapping("/create")
    public ApiResponse<PartyDTO> createParty() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        return ApiResponse.ok(partyService.createParty(userId));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PARTY INVITATION
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * POST /api/party/invite
     * Invite a user to the party (host or members can invite).
     * 
     * Request body: { "inviteeUserId": 123 }
     * Response: PartyInvitationDTO
     */
    @PostMapping("/invite")
    public ApiResponse<PartyInvitationDTO> inviteToParty(@Valid @RequestBody InviteToPartyRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        return ApiResponse.ok(partyService.inviteToParty(userId, request.getInviteeUserId()));
    }

    /**
     * GET /api/party/invitations
     * Get all pending party invitations for the user.
     * 
     * Response: List<PartyInvitationDTO>
     */
    @GetMapping("/invitations")
    public ApiResponse<List<PartyInvitationDTO>> getPendingInvitations() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        return ApiResponse.ok(partyService.getPendingInvitations(userId));
    }

    /**
     * POST /api/party/invitations/{invitationId}/accept
     * Accept a party invitation.
     * 
     * Path param: invitationId (Long)
     * Response: PartyDTO (joined party)
     */
    @PostMapping("/invitations/{invitationId}/accept")
    public ApiResponse<PartyDTO> acceptInvitation(@PathVariable Long invitationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        return ApiResponse.ok(partyService.acceptInvitation(userId, invitationId));
    }

    /**
     * POST /api/party/invitations/{invitationId}/decline
     * Decline a party invitation.
     * 
     * Path param: invitationId (Long)
     * Response: Success message
     */
    @PostMapping("/invitations/{invitationId}/decline")
    public ApiResponse<Void> declineInvitation(@PathVariable Long invitationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        partyService.declineInvitation(userId, invitationId);
        return ApiResponse.okMessage("Invitation declined");
    }

    /**
     * DELETE /api/party/invitations/{invitationId}/cancel
     * Cancel a sent party invitation (inviter cancels their own invite).
     *
     * Path param: invitationId (Long)
     * Response: Success message
     */
    @DeleteMapping("/invitations/{invitationId}/cancel")
    public ApiResponse<Void> cancelInvitation(@PathVariable Long invitationId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");

        partyService.cancelInvitation(userId, invitationId);
        return ApiResponse.okMessage("Invitation cancelled");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PARTY MANAGEMENT
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * GET /api/party/current
     * Get current party of the user.
     * 
     * Response: PartyDTO
     */
    @GetMapping("/current")
    public ApiResponse<PartyDTO> getCurrentParty() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        return ApiResponse.ok(partyService.getCurrentParty(userId));
    }

    /**
     * POST /api/party/leave
     * Leave the current party.
     * 
     * Response: Success message
     */
    @PostMapping("/leave")
    public ApiResponse<Void> leaveParty() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        partyService.leaveParty(userId);
        return ApiResponse.okMessage("Left party");
    }

    /**
     * POST /api/party/kick/{userId}
     * Kick a member from the party (host only).
     * 
     * Path param: userId (Long) - User to kick
     * Response: Success message
     */
    @PostMapping("/kick/{kickedUserId}")
    public ApiResponse<Void> kickMember(@PathVariable Long kickedUserId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        partyService.kickMember(userId, kickedUserId);
        return ApiResponse.okMessage("Member kicked");
    }

    /**
     * POST /api/party/disband
     * Disband the party (host only).
     * 
     * Response: Success message
     */
    @PostMapping("/disband")
    public ApiResponse<Void> disbandParty() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        partyService.disbandParty(userId);
        return ApiResponse.okMessage("Party disbanded");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PARTY MATCHMAKING
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * POST /api/party/queue
     * Queue party for matchmaking (host only).
     * All party members are added to queue together.
     * 
     * Request body: { "gameMode": "2v2", "allowFill": true }
     * Response: Success message
     */
    @PostMapping("/queue")
    public ApiResponse<Void> queueParty(@Valid @RequestBody PartyMatchmakingRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        partyMatchmakingService.queueParty(userId, request);
        return ApiResponse.okMessage("Party queued for matchmaking");
    }

    /**
     * POST /api/party/cancel-queue
     * Cancel party matchmaking queue.
     * Any party member can cancel (will remove all members from queue).
     * 
     * Response: Success message
     */
    @PostMapping("/cancel-queue")
    public ApiResponse<Void> cancelQueue() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        partyMatchmakingService.cancelQueue(userId);
        return ApiResponse.okMessage("Party queue cancelled");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PARTY CUSTOM LOBBY
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * POST /api/party/join-room
     * Join custom lobby with entire party (host only).
     * All party members are automatically added to the room.
     * 
     * Request body: { "roomCode": "ABC123", "password": "optional" }
     * Response: RoomResponse
     */
    @PostMapping("/join-room")
    public ApiResponse<RoomResponse> joinRoomWithParty(@RequestBody JoinRoomRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        
        return ApiResponse.ok(partyRoomService.joinRoomWithParty(
            userId, 
            request.getRoomCode(), 
            request.getPassword()
        ));
    }
    
    /**
     * Request DTO for joining room with party.
     */
    @lombok.Data
    public static class JoinRoomRequest {
        private String roomCode;
        private String password;
    }
}
