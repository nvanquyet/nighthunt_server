package com.nighthunt.party.service;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.friend.repository.BlockedUserRepository;
import com.nighthunt.friend.service.PlayerStatusService;
import com.nighthunt.messaging.service.MessageBrokerService;
import com.nighthunt.party.dto.*;
import com.nighthunt.party.entity.Party;
import com.nighthunt.party.entity.PartyInvitation;
import com.nighthunt.party.entity.PartyMember;
import com.nighthunt.party.repository.PartyInvitationRepository;
import com.nighthunt.party.repository.PartyMemberRepository;
import com.nighthunt.party.repository.PartyRepository;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Party Service - Manages pre-match party (squad) system.
 * 
 * Party workflow:
 * 1. User creates party → Becomes host
 * 2. Host invites friends → Creates PartyInvitation (30s timeout)
 * 3. Friend accepts → Joins party as member
 * 4. Host can kick members
 * 5. Members can leave party
 * 6. Host can disband party
 * 7. When host leaves → Next member becomes host (or party disbands if empty)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartyService {

    private final PartyRepository partyRepository;
    private final PartyMemberRepository partyMemberRepository;
    private final PartyInvitationRepository partyInvitationRepository;
    private final UserRepository userRepository;
    private final BlockedUserRepository blockedUserRepository;
    private final PlayerStatusService playerStatusService;
    private final MessageBrokerService messageBrokerService;

    private static final int INVITATION_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_MAX_MEMBERS = 4;

    // ──────────────────────────────────────────────────────────────────────────
    // PARTY CREATION
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Create a new party.
     * User becomes the host.
     */
    @Transactional
    public PartyDTO createParty(Long hostUserId) {
        User host = findUser(hostUserId);
        
        // Validate: User is not already in a party
        if (partyMemberRepository.existsByUserId(hostUserId)) {
            throw new BusinessException(ErrorCodes.PARTY_ALREADY_IN_PARTY, "You are already in a party");
        }

        // Create party
        Party party = Party.builder()
                .hostUserId(hostUserId)
                .partyStatus("IDLE")
                .maxMembers(DEFAULT_MAX_MEMBERS)
                .build();
        
        partyRepository.save(party);
        
        // Add host as first member
        PartyMember hostMember = PartyMember.builder()
                .partyId(party.getId())
                .userId(hostUserId)
                .joinOrder(0) // Host is always 0
                .build();
        
        partyMemberRepository.save(hostMember);
        
        // Update user's current party
        playerStatusService.updateCurrentParty(hostUserId, party.getId());
        
        log.info("Party created: party={}, host={}", party.getId(), hostUserId);
        
        // Publish WebSocket event
        messageBrokerService.publishPartyCreated(party.getId(), hostUserId, host.getUsername());
        
        return toPartyDTO(party);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PARTY INVITATION
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Invite a user to party (host or members can invite).
     */
    @Transactional
    public PartyInvitationDTO inviteToParty(Long inviterUserId, Long inviteeUserId) {
        User inviter = findUser(inviterUserId);
        User invitee = findUser(inviteeUserId);
        
        // Validate: Inviter is in a party
        PartyMember inviterMember = partyMemberRepository.findByUserId(inviterUserId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.PARTY_NOT_IN_PARTY, "You are not in a party"));
        
        Party party = findParty(inviterMember.getPartyId());
        
        // Validate: Party must be IDLE (not in queue or in room)
        if (!"IDLE".equals(party.getPartyStatus())) {
            throw new BusinessException(ErrorCodes.PARTY_NOT_IDLE, 
                    "Cannot invite while party is " + party.getPartyStatus());
        }

        // Validate: Party is not full
        long memberCount = partyMemberRepository.countByPartyId(party.getId());
        if (memberCount >= party.getMaxMembers()) {
            throw new BusinessException(ErrorCodes.PARTY_FULL, "Party is full");
        }

        // Validate: Invitee is not already in a party
        if (partyMemberRepository.existsByUserId(inviteeUserId)) {
            throw new BusinessException(ErrorCodes.PARTY_USER_ALREADY_IN_PARTY, "User is already in a party");
        }

        // Validate: No pending invitation exists
        if (partyInvitationRepository.hasPendingInvitation(party.getId(), inviteeUserId)) {
            throw new BusinessException(ErrorCodes.PARTY_INVITATION_EXISTS, "Invitation already sent to this user");
        }

        // Validate: Invitee has not blocked inviter
        if (blockedUserRepository.existsByBlockerUserIdAndBlockedUserId(inviteeUserId, inviterUserId)) {
            throw new BusinessException(ErrorCodes.PARTY_INVITATION_BLOCKED, "Cannot invite this user");
        }

        // Create invitation
        PartyInvitation invitation = PartyInvitation.builder()
                .partyId(party.getId())
                .inviterUserId(inviterUserId)
                .inviteeUserId(inviteeUserId)
                .invitationStatus("PENDING")
                .expiresAt(LocalDateTime.now().plusSeconds(INVITATION_TIMEOUT_SECONDS))
                .build();
        
        partyInvitationRepository.save(invitation);
        
        log.info("Party invitation sent: party={}, inviter={}, invitee={}", party.getId(), inviterUserId, inviteeUserId);
        
        // Publish WebSocket event
        messageBrokerService.publishPartyInvitationReceived(
            party.getId(), 
            inviteeUserId, 
            inviterUserId, 
            inviter.getUsername(), 
            invitation.getId()
        );
        
        return toPartyInvitationDTO(invitation);
    }

    /**
     * Accept a party invitation.
     */
    @Transactional
    public PartyDTO acceptInvitation(Long inviteeUserId, Long invitationId) {
        PartyInvitation invitation = findInvitation(invitationId);
        
        // Validate: User is the invitee
        if (!invitation.getInviteeUserId().equals(inviteeUserId)) {
            throw new BusinessException(ErrorCodes.PARTY_INVITATION_NOT_FOR_YOU, "This invitation is not for you");
        }

        // Validate: Invitation is pending
        if (!"PENDING".equals(invitation.getInvitationStatus())) {
            throw new BusinessException(ErrorCodes.PARTY_INVITATION_NOT_PENDING, "Invitation is not pending");
        }

        // Validate: Invitation has not expired
        if (invitation.getExpiresAt().isBefore(LocalDateTime.now())) {
            invitation.setInvitationStatus("EXPIRED");
            partyInvitationRepository.save(invitation);
            // Notify both sides so they can clean up UI.
            messageBrokerService.publishPartyInvitationExpired(
                invitation.getPartyId(), invitation.getInviterUserId(), invitation.getInviteeUserId(), invitation.getId());
            throw new BusinessException(ErrorCodes.PARTY_INVITATION_EXPIRED, "Invitation has expired");
        }

        Party party = findParty(invitation.getPartyId());
        
        // Validate: Party still exists and is not disbanded
        if ("DISBANDED".equals(party.getPartyStatus())) {
            throw new BusinessException(ErrorCodes.PARTY_DISBANDED, "Party has been disbanded");
        }

        // Validate: User is not already in a party
        if (partyMemberRepository.existsByUserId(inviteeUserId)) {
            throw new BusinessException(ErrorCodes.PARTY_ALREADY_IN_PARTY, "You are already in a party");
        }

        // Validate: Party is not full
        long memberCount = partyMemberRepository.countByPartyId(party.getId());
        if (memberCount >= party.getMaxMembers()) {
            throw new BusinessException(ErrorCodes.PARTY_FULL, "Party is full");
        }

        // Add user to party
        int joinOrder = (int) memberCount; // Next available order
        PartyMember member = PartyMember.builder()
                .partyId(party.getId())
                .userId(inviteeUserId)
                .joinOrder(joinOrder)
                .build();
        
        partyMemberRepository.save(member);
        
        // Update invitation status
        invitation.setInvitationStatus("ACCEPTED");
        partyInvitationRepository.save(invitation);
        
        // Update user's current party
        playerStatusService.updateCurrentParty(inviteeUserId, party.getId());
        
        log.info("Party invitation accepted: party={}, user={}", party.getId(), inviteeUserId);
        
        // Publish WebSocket event to all party members
        User invitee = findUser(inviteeUserId);
        messageBrokerService.publishPartyMemberJoined(
            party.getId(), 
            inviteeUserId, 
            invitee.getUsername(), 
            joinOrder
        );
        
        return toPartyDTO(party);
    }

    /**
     * Cancel a sent party invitation (by the inviter or a party member).
     */
    @Transactional
    public void cancelInvitation(Long inviterUserId, Long invitationId) {
        PartyInvitation invitation = findInvitation(invitationId);

        // Validate: User is the inviter
        if (!invitation.getInviterUserId().equals(inviterUserId)) {
            throw new BusinessException(ErrorCodes.PARTY_INVITATION_NOT_YOURS, "You did not send this invitation");
        }

        // Validate: Invitation is pending
        if (!"PENDING".equals(invitation.getInvitationStatus())) {
            throw new BusinessException(ErrorCodes.PARTY_INVITATION_NOT_PENDING, "Invitation is not pending");
        }

        invitation.setInvitationStatus("CANCELLED");
        partyInvitationRepository.save(invitation);

        log.info("Party invitation cancelled: inviter={}, invitation={}", inviterUserId, invitationId);

        // Notify invitee so their countdown popup is dismissed immediately.
        messageBrokerService.publishPartyInvitationCancelled(
            invitation.getPartyId(), inviterUserId, invitation.getInviteeUserId(), invitationId);
    }

    /**
     * Decline a party invitation.
     */
    @Transactional
    public void declineInvitation(Long inviteeUserId, Long invitationId) {
        PartyInvitation invitation = findInvitation(invitationId);
        
        // Validate: User is the invitee
        if (!invitation.getInviteeUserId().equals(inviteeUserId)) {
            throw new BusinessException(ErrorCodes.PARTY_INVITATION_NOT_FOR_YOU, "This invitation is not for you");
        }

        // Validate: Invitation is pending
        if (!"PENDING".equals(invitation.getInvitationStatus())) {
            throw new BusinessException(ErrorCodes.PARTY_INVITATION_NOT_PENDING, "Invitation is not pending");
        }

        // Update invitation status
        invitation.setInvitationStatus("DECLINED");
        partyInvitationRepository.save(invitation);

        log.info("Party invitation declined: invitee={}, invitation={}", inviteeUserId, invitationId);

        // Notify inviter so they can remove the pending-invite spinner.
        messageBrokerService.publishPartyInvitationDeclined(
            invitation.getPartyId(), invitation.getInviterUserId(), inviteeUserId, invitationId);
    }

    /**
     * Get all pending party invitations for a user.
     */
    @Transactional(readOnly = true)
    public List<PartyInvitationDTO> getPendingInvitations(Long userId) {
        List<PartyInvitation> invitations = partyInvitationRepository.findByInviteeUserIdAndInvitationStatus(userId, "PENDING");
        
        // Filter expired invitations
        LocalDateTime now = LocalDateTime.now();
        return invitations.stream()
                .filter(inv -> inv.getExpiresAt().isAfter(now))
                .map(this::toPartyInvitationDTO)
                .collect(Collectors.toList());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PARTY MANAGEMENT
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Get current party of a user.
     */
    @Transactional(readOnly = true)
    public PartyDTO getCurrentParty(Long userId) {
        PartyMember member = partyMemberRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.PARTY_NOT_IN_PARTY, "You are not in a party"));
        
        Party party = findParty(member.getPartyId());
        return toPartyDTO(party);
    }

    /**
     * Leave party (member leaves voluntarily).
     */
    @Transactional
    public void leaveParty(Long userId) {
        PartyMember member = partyMemberRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.PARTY_NOT_IN_PARTY, "You are not in a party"));
        
        Party party = findParty(member.getPartyId());
        
        // If user is host, transfer host or disband
        if (party.getHostUserId().equals(userId)) {
            handleHostLeaving(party);
        } else {
            // Regular member leaves
            removeMember(party, userId);
        }
    }

    /**
     * Kick a member from party (host only).
     */
    @Transactional
    public void kickMember(Long hostUserId, Long kickedUserId) {
        PartyMember hostMember = partyMemberRepository.findByUserId(hostUserId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.PARTY_NOT_IN_PARTY, "You are not in a party"));
        
        Party party = findParty(hostMember.getPartyId());
        
        // Validate: User is the host
        if (!party.getHostUserId().equals(hostUserId)) {
            throw new BusinessException(ErrorCodes.PARTY_NOT_HOST, "Only host can kick members");
        }

        // Validate: Cannot kick self
        if (hostUserId.equals(kickedUserId)) {
            throw new BusinessException(ErrorCodes.PARTY_CANNOT_KICK_SELF, "Cannot kick yourself (use leave instead)");
        }

        // Validate: Kicked user is in the party
        if (!partyMemberRepository.existsByPartyIdAndUserId(party.getId(), kickedUserId)) {
            throw new BusinessException(ErrorCodes.PARTY_USER_NOT_IN_PARTY, "User is not in this party");
        }

        // Remove member
        removeMember(party, kickedUserId);
        
        // Publish kick event
        messageBrokerService.publishPartyMemberKicked(party.getId(), kickedUserId, hostUserId);
        
        log.info("Party member kicked: party={}, kicked={}, kicker={}", party.getId(), kickedUserId, hostUserId);
    }

    /**
     * Transfer party leadership to another member (host only).
     * The current host remains in the party as a regular member.
     */
    @Transactional
    public PartyDTO transferLeader(Long hostUserId, Long newHostUserId) {
        PartyMember hostMember = partyMemberRepository.findByUserId(hostUserId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.PARTY_NOT_IN_PARTY, "You are not in a party"));

        Party party = findParty(hostMember.getPartyId());

        // Validate: caller is current host
        if (!party.getHostUserId().equals(hostUserId)) {
            throw new BusinessException(ErrorCodes.PARTY_NOT_HOST, "Only the host can transfer leadership");
        }

        // Validate: cannot transfer to self
        if (hostUserId.equals(newHostUserId)) {
            throw new BusinessException(ErrorCodes.PARTY_TRANSFER_SAME_USER, "Cannot transfer leadership to yourself");
        }

        // Validate: target is in the party
        if (!partyMemberRepository.existsByPartyIdAndUserId(party.getId(), newHostUserId)) {
            throw new BusinessException(ErrorCodes.PARTY_USER_NOT_IN_PARTY, "Target user is not in this party");
        }

        Long oldHostUserId = party.getHostUserId();
        party.setHostUserId(newHostUserId);
        partyRepository.save(party);

        messageBrokerService.publishPartyHostChanged(party.getId(), oldHostUserId, newHostUserId);
        log.info("[Party] Leader transferred: party={} oldHost={} newHost={}", party.getId(), oldHostUserId, newHostUserId);

        return toPartyDTO(party);
    }

    /**
     * Disband party (host only).
     */
    @Transactional
    public void disbandParty(Long hostUserId) {
        PartyMember hostMember = partyMemberRepository.findByUserId(hostUserId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.PARTY_NOT_IN_PARTY, "You are not in a party"));
        
        Party party = findParty(hostMember.getPartyId());
        
        // Validate: User is the host
        if (!party.getHostUserId().equals(hostUserId)) {
            throw new BusinessException(ErrorCodes.PARTY_NOT_HOST, "Only host can disband party");
        }

        // Disband party
        disbandPartyInternal(party);
        
        log.info("Party disbanded by host: party={}, host={}", party.getId(), hostUserId);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // INTERNAL HELPER METHODS
    // ──────────────────────────────────────────────────────────────────────────

    private void removeMember(Party party, Long userId) {
        // Remove member
        partyMemberRepository.deleteByPartyIdAndUserId(party.getId(), userId);
        
        // Update user's current party
        playerStatusService.updateCurrentParty(userId, null);
        
        // Publish member left event
        messageBrokerService.publishPartyMemberLeft(party.getId(), userId);
        
        // Check if party is empty
        long remainingMembers = partyMemberRepository.countByPartyId(party.getId());
        if (remainingMembers == 0) {
            disbandPartyInternal(party);
        }
    }

    private void handleHostLeaving(Party party) {
        Long oldHostUserId = party.getHostUserId();
        
        // Get remaining members
        List<PartyMember> members = partyMemberRepository.findByPartyIdOrderByJoinOrderAsc(party.getId());
        
        // Remove host
        partyMemberRepository.deleteByPartyIdAndUserId(party.getId(), oldHostUserId);
        playerStatusService.updateCurrentParty(oldHostUserId, null);
        
        // Publish host left event
        messageBrokerService.publishPartyMemberLeft(party.getId(), oldHostUserId);
        
        // Transfer host to next member or disband
        List<PartyMember> remainingMembers = members.stream()
                .filter(m -> !m.getUserId().equals(oldHostUserId))
                .collect(Collectors.toList());
        
        if (remainingMembers.isEmpty()) {
            // No members left, disband party
            disbandPartyInternal(party);
        } else {
            // Transfer host to next member (lowest joinOrder)
            PartyMember newHost = remainingMembers.get(0);
            party.setHostUserId(newHost.getUserId());
            partyRepository.save(party);
            
            // Publish host changed event
            messageBrokerService.publishPartyHostChanged(party.getId(), oldHostUserId, newHost.getUserId());
            
            log.info("Party host transferred: party={}, oldHost={}, newHost={}", 
                party.getId(), oldHostUserId, newHost.getUserId());
        }
    }

    private void disbandPartyInternal(Party party) {
        Long partyId = party.getId();
        
        // Get all members BEFORE deleting (subscriber queries will find empty after delete)
        List<Long> memberIds = partyMemberRepository.findUserIdsByPartyId(partyId);
        
        // Clear current party for all members
        for (Long memberId : memberIds) {
            playerStatusService.updateCurrentParty(memberId, null);
        }
        
        // Delete all members
        partyMemberRepository.deleteByPartyId(partyId);
        
        // Delete all pending invitations
        partyInvitationRepository.deleteByPartyId(partyId);
        
        // Mark party as disbanded
        party.setPartyStatus("DISBANDED");
        partyRepository.save(party);
        
        // Publish disbanded event — include memberIds so subscriber can notify every member
        messageBrokerService.publishPartyDisbanded(partyId, party.getHostUserId(), memberIds);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // DTO CONVERSION
    // ──────────────────────────────────────────────────────────────────────────

    private PartyDTO toPartyDTO(Party party) {
        List<PartyMember> members = partyMemberRepository.findByPartyIdOrderByJoinOrderAsc(party.getId());
        User host = findUser(party.getHostUserId());
        
        List<PartyMemberDTO> memberDTOs = members.stream()
                .map(this::toPartyMemberDTO)
                .collect(Collectors.toList());
        
        return PartyDTO.builder()
                .partyId(party.getId())
                .hostUserId(party.getHostUserId())
                .hostUsername(host.getUsername())
                .partyStatus(party.getPartyStatus())
                .currentRoomId(party.getCurrentRoomId())
                .currentMatchmakingId(party.getCurrentMatchmakingId())
                .maxMembers(party.getMaxMembers())
                .currentMemberCount(members.size())
                .members(memberDTOs)
                .createdAt(party.getCreatedAt())
                .build();
    }

    private PartyMemberDTO toPartyMemberDTO(PartyMember member) {
        User user = findUser(member.getUserId());
        
        // Determine isHost by checking party.hostUserId, not joinOrder
        // (joinOrder == 0 is the original host, but leadership can transfer)
        Party party = partyRepository.findById(member.getPartyId()).orElse(null);
        boolean isHost = party != null && member.getUserId().equals(party.getHostUserId());
        
        return PartyMemberDTO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .joinOrder(member.getJoinOrder())
                .onlineStatus(user.getOnlineStatus())
                .joinedAt(member.getJoinedAt())
                .isHost(isHost)
                .build();
    }

    private PartyInvitationDTO toPartyInvitationDTO(PartyInvitation invitation) {
        User inviter = findUser(invitation.getInviterUserId());
        User invitee = findUser(invitation.getInviteeUserId());
        
        long secondsRemaining = Duration.between(LocalDateTime.now(), invitation.getExpiresAt()).getSeconds();
        
        return PartyInvitationDTO.builder()
                .invitationId(invitation.getId())
                .partyId(invitation.getPartyId())
                .inviterUserId(inviter.getId())
                .inviterUsername(inviter.getUsername())
                .inviteeUserId(invitee.getId())
                .inviteeUsername(invitee.getUsername())
                .invitationStatus(invitation.getInvitationStatus())
                .expiresAt(invitation.getExpiresAt())
                .createdAt(invitation.getCreatedAt())
                .secondsRemaining((int) Math.max(0, secondsRemaining))
                .build();
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.USER_NOT_FOUND, "User not found"));
    }

    private Party findParty(Long partyId) {
        return partyRepository.findById(partyId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.PARTY_NOT_FOUND, "Party not found"));
    }

    private PartyInvitation findInvitation(Long invitationId) {
        return partyInvitationRepository.findById(invitationId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.PARTY_INVITATION_NOT_FOUND, "Invitation not found"));
    }
}
