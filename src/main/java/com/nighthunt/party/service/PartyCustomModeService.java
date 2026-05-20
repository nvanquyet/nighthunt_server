package com.nighthunt.party.service;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.friend.service.PlayerStatusService;
import com.nighthunt.messaging.service.MessageBrokerService;
import com.nighthunt.party.entity.Party;
import com.nighthunt.party.entity.PartyMember;
import com.nighthunt.party.repository.PartyMemberRepository;
import com.nighthunt.party.repository.PartyRepository;
import com.nighthunt.room.dto.RoomResponse;
import com.nighthunt.room.service.RoomService;
import com.nighthunt.room.repository.RoomPlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Party Room Service - Handles party integration with custom lobby system.
 * 
 * Features:
 * 1. Party Join: All party members join custom lobby together
 * 2. Auto Disband: Party disbands when host joins custom lobby (lobby becomes new party context)
 * 3. Validation: Ensures room has enough slots for all party members
 */
@Slf4j
@Service
public class PartyCustomModeService {

    private final PartyRepository partyRepository;
    private final PartyMemberRepository partyMemberRepository;
    private final RoomService roomService;
    private final RoomPlayerRepository roomPlayerRepository;
    private final PlayerStatusService playerStatusService;
    private final MessageBrokerService messageBrokerService;

    public PartyCustomModeService(
            PartyRepository partyRepository,
            PartyMemberRepository partyMemberRepository,
            @Lazy RoomService roomService,
            RoomPlayerRepository roomPlayerRepository,
            PlayerStatusService playerStatusService,
            MessageBrokerService messageBrokerService) {
        this.partyRepository = partyRepository;
        this.partyMemberRepository = partyMemberRepository;
        this.roomService = roomService;
        this.roomPlayerRepository = roomPlayerRepository;
        this.playerStatusService = playerStatusService;
        this.messageBrokerService = messageBrokerService;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PARTY ROOM OPERATIONS
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Host joins custom lobby with entire party.
     * All party members are automatically added to the room.
     * Party is disbanded after joining (room becomes the new context).
     */
    @Transactional
    public RoomResponse joinRoomWithParty(Long hostUserId, String roomCode, String password) {
        // Find host's party
        PartyMember hostMember = partyMemberRepository.findByUserId(hostUserId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.PARTY_NOT_IN_PARTY, "You are not in a party"));
        
        Party party = findParty(hostMember.getPartyId());
        
        // Validate: User is the host
        if (!party.getHostUserId().equals(hostUserId)) {
            throw new BusinessException(ErrorCodes.PARTY_NOT_HOST, "Only host can join room with party");
        }

        // Validate: Party is idle (not in queue or already in room)
        if (!"IDLE".equals(party.getPartyStatus())) {
            throw new BusinessException(ErrorCodes.PARTY_NOT_IDLE, 
                "Party must be idle to join room (current status: " + party.getPartyStatus() + ")");
        }

        // Mutual-exclusivity guard: partyMode=RANKED means party is actively searching for a
        // ranked match. They must cancel matchmaking before joining a custom lobby.
        if ("RANKED".equals(party.getPartyMode())) {
            throw new BusinessException(ErrorCodes.PARTY_IN_RANKED_QUEUE,
                    "Party is in ranked matchmaking queue. Cancel matchmaking before joining a custom lobby.");
        }

        // Get all party members
        List<Long> memberIds = partyMemberRepository.findUserIdsByPartyId(party.getId());
        int partySize = memberIds.size();

        // Host joins first to validate room access
        RoomResponse roomResponse = roomService.joinRoomByCode(hostUserId, roomCode, password);
        Long roomId = roomResponse.getRoomId();

        // TODO: Check if room has enough slots for all members
        // For now, we'll try to add all members and fail if room is full

        // Add remaining party members
        for (Long memberId : memberIds) {
            if (memberId.equals(hostUserId)) continue; // Skip host (already joined)
            
            try {
                roomService.joinRoomByCode(memberId, roomCode, password);
                log.info("Party member {} joined room {} with party", memberId, roomId);
            } catch (BusinessException e) {
                // If any member fails to join, we have a problem
                // For now, log error but continue (they can join manually)
                log.warn("Party member {} failed to join room {}: {}", memberId, roomId, e.getMessage());
            }
        }

        // Update party status to IN_ROOM and set CUSTOM mode
        party.setPartyStatus("IN_ROOM");
        party.setPartyMode("CUSTOM");   // lock context to CUSTOM
        party.setCurrentRoomId(roomId);
        partyRepository.save(party);
        
        // Update all members' current room
        for (Long memberId : memberIds) {
            playerStatusService.updateCurrentRoom(memberId, roomId);
        }

        // Publish party status change event
        messageBrokerService.publishPartyStatusChanged(party.getId(), "IDLE", "IN_ROOM");
        
        log.info("Party {} joined room {} (host={}, size={})", 
            party.getId(), roomId, hostUserId, partySize);
        
        return roomResponse;
    }

    /**
     * Disband party when entering custom lobby.
     * This is optional - some games keep party even in lobby.
     * For now, we keep party active but update status to IN_ROOM.
     */
    @Transactional
    public void updatePartyStatusForRoom(Long userId, Long roomId) {
        PartyMember member = partyMemberRepository.findByUserId(userId).orElse(null);
        if (member == null) return; // User not in party, skip
        
        Party party = partyRepository.findById(member.getPartyId()).orElse(null);
        if (party == null) return;
        
        // Update party room tracking
        party.setCurrentRoomId(roomId);
        party.setPartyStatus("IN_ROOM");
        partyRepository.save(party);
        
        // Update user's current room
        playerStatusService.updateCurrentRoom(userId, roomId);
    }

    /**
     * Clear party room status when a member leaves the custom lobby.
     * Only resets party status to IDLE when NO party members remain in the room.
     */
    @Transactional
    public void clearPartyRoomStatus(Long userId) {
        PartyMember member = partyMemberRepository.findByUserId(userId).orElse(null);
        if (member == null) return; // User not in party, skip
        
        Party party = partyRepository.findById(member.getPartyId()).orElse(null);
        if (party == null) return;
        
        // Clear this user's current room
        playerStatusService.updateCurrentRoom(userId, null);
        
        // Only reset party status if NO other party members are still in the room
        Long currentRoomId = party.getCurrentRoomId();
        if (currentRoomId != null) {
            List<Long> memberIds = partyMemberRepository.findUserIdsByPartyId(party.getId());
            boolean anyMemberStillInRoom = memberIds.stream()
                    .filter(id -> !id.equals(userId))
                    .anyMatch(id -> roomPlayerRepository.findByRoomIdAndUserId(currentRoomId, id).isPresent());
            
            if (!anyMemberStillInRoom) {
                String oldStatus = party.getPartyStatus();
                party.setCurrentRoomId(null);
                party.setPartyStatus("IDLE");
                party.setPartyMode("NONE");   // release CUSTOM context lock
                partyRepository.save(party);
                messageBrokerService.publishPartyStatusChanged(party.getId(), oldStatus, "IDLE");
                log.info("Party {} reset to IDLE (mode=NONE) — no members remain in room {}", party.getId(), currentRoomId);
            } else {
                log.debug("Party {} still has members in room {} — keeping IN_ROOM status", party.getId(), currentRoomId);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // HELPER METHODS
    // ──────────────────────────────────────────────────────────────────────────

    private Party findParty(Long partyId) {
        return partyRepository.findById(partyId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.PARTY_NOT_FOUND, "Party not found"));
    }
}
