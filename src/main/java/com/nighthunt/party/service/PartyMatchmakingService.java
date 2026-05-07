package com.nighthunt.party.service;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.gamemode.service.GameModeService;
import com.nighthunt.matchmaking.service.MatchmakingQueueService;
import com.nighthunt.messaging.service.MessageBrokerService;
import com.nighthunt.party.dto.PartyMatchmakingRequest;
import com.nighthunt.party.entity.Party;
import com.nighthunt.party.entity.PartyMember;
import com.nighthunt.party.repository.PartyMemberRepository;
import com.nighthunt.party.repository.PartyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Party Matchmaking Service - Handles party queue for matchmaking.
 * 
 * Features:
 * 1. Party Queue: All party members queue together
 * 2. Fill Option: Allow random players to fill empty slots
 * 3. Validation: Party size must match game mode requirements
 * 
 * Example workflows:
 * - 2 players in party queue for 2v2 (allowFill=false) → Waits for another 2-player party
 * - 2 players in party queue for 2v2 (allowFill=true) → Can match with 2 solo players
 * - 3 players in party queue for 4v4 (allowFill=true) → Needs 1 solo player to fill
 * - 4 players in party queue for 4v4 (allowFill=false) → Full team, waits for enemy team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartyMatchmakingService {

    private final PartyRepository partyRepository;
    private final PartyMemberRepository partyMemberRepository;
    private final MatchmakingQueueService matchmakingQueueService;
    private final GameModeService gameModeService;
    private final MessageBrokerService messageBrokerService;

    // ──────────────────────────────────────────────────────────────────────────
    // PARTY MATCHMAKING
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Queue party for matchmaking (host only).
     * All party members are added to matchmaking queue together.
     */
    @Transactional
    public void queueParty(Long hostUserId, PartyMatchmakingRequest request) {
        // Find host's party
        PartyMember hostMember = partyMemberRepository.findByUserId(hostUserId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.PARTY_NOT_IN_PARTY, "You are not in a party"));
        
        Party party = findParty(hostMember.getPartyId());
        
        // Validate: User is the host
        if (!party.getHostUserId().equals(hostUserId)) {
            throw new BusinessException(ErrorCodes.PARTY_NOT_HOST, "Only host can start matchmaking");
        }

        // Validate: Party is not already in queue
        if ("IN_QUEUE".equals(party.getPartyStatus())) {
            throw new BusinessException(ErrorCodes.PARTY_NOT_IDLE, "Party is already in matchmaking queue");
        }

        // Validate: Game mode is available
        if (!gameModeService.isGameModeAvailable(request.getGameMode())) {
            throw new BusinessException(ErrorCodes.DS_GAME_MODE_UNAVAILABLE, "Game mode is not available: " + request.getGameMode());
        }

        // Get game mode info
        int playersPerTeam = gameModeService.getPlayersPerTeam(request.getGameMode());
        
        // Get party members
        List<Long> memberIds = partyMemberRepository.findUserIdsByPartyId(party.getId());
        int partySize = memberIds.size();

        // Validate party size
        if (partySize > playersPerTeam) {
            throw new BusinessException(ErrorCodes.PARTY_SIZE_MISMATCH,
                String.format("Party size (%d) exceeds team size (%d) for %s", 
                    partySize, playersPerTeam, request.getGameMode()));
        }

        // If fill option disabled, party must be full team
        if (!request.isAllowFill() && partySize < playersPerTeam) {
            throw new BusinessException(ErrorCodes.PARTY_SIZE_MISMATCH,
                String.format("Party must have %d players for %s (no fill)", 
                    playersPerTeam, request.getGameMode()));
        }

        // Update party status
        party.setPartyStatus("IN_QUEUE");
        partyRepository.save(party);
        
        // Add all members to matchmaking queue
        for (Long memberId : memberIds) {
            matchmakingQueueService.enqueue(memberId, request.getGameMode(), request.getMapId(), null);
            log.info("Party member {} queued for matchmaking (party={}, mode={}, mapId={})",
                memberId, party.getId(), request.getGameMode(), request.getMapId());
        }

        // Publish party status change event
        messageBrokerService.publishPartyStatusChanged(party.getId(), "IDLE", "IN_QUEUE");
        
        log.info("Party {} queued for matchmaking: mode={}, mapId={}, size={}, allowFill={}",
            party.getId(), request.getGameMode(), request.getMapId(), partySize, request.isAllowFill());
    }

    /**
     * Cancel party matchmaking queue (host only or any member leaving).
     */
    @Transactional
    public void cancelQueue(Long userId) {
        PartyMember member = partyMemberRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.PARTY_NOT_IN_PARTY, "You are not in a party"));
        
        Party party = findParty(member.getPartyId());
        
        // Only allow if party is in queue
        if (!"IN_QUEUE".equals(party.getPartyStatus())) {
            throw new BusinessException(ErrorCodes.PARTY_NOT_IDLE, "Party is not in matchmaking queue");
        }

        // Dequeue all members
        List<Long> memberIds = partyMemberRepository.findUserIdsByPartyId(party.getId());
        for (Long memberId : memberIds) {
            matchmakingQueueService.dequeue(memberId);
            log.info("Party member {} removed from matchmaking queue", memberId);
        }

        // Update party status back to IDLE
        party.setPartyStatus("IDLE");
        partyRepository.save(party);
        
        // Publish party status change event
        messageBrokerService.publishPartyStatusChanged(party.getId(), "IN_QUEUE", "IDLE");
        
        log.info("Party {} matchmaking cancelled by user {}", party.getId(), userId);
    }

    /**
     * Check if party is currently in matchmaking queue.
     */
    @Transactional(readOnly = true)
    public boolean isPartyInQueue(Long partyId) {
        Party party = findParty(partyId);
        return "IN_QUEUE".equals(party.getPartyStatus());
    }

    /**
     * Validate party composition for a game mode.
     * Returns true if party can queue for the mode (with or without fill).
     */
    @Transactional(readOnly = true)
    public boolean canPartyQueueForMode(Long partyId, String gameMode) {
        Party party = findParty(partyId);
        int partySize = partyMemberRepository.findUserIdsByPartyId(partyId).size();
        int playersPerTeam = gameModeService.getPlayersPerTeam(gameMode);
        
        // Party size must not exceed team size
        return partySize <= playersPerTeam;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // HELPER METHODS
    // ──────────────────────────────────────────────────────────────────────────

    private Party findParty(Long partyId) {
        return partyRepository.findById(partyId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.PARTY_NOT_FOUND, "Party not found"));
    }
}
