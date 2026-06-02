package com.nighthunt.party.service;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.gamemode.service.GameModeService;
import com.nighthunt.matchmaking.service.MatchmakingQueueService;
import com.nighthunt.messaging.service.MessageBrokerService;
import com.nighthunt.party.dto.PartyRankedQueueRequest;
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
 * PartyRankedModeService — Manages the lifecycle of a party entering ranked matchmaking.
 *
 * <p>A party in RANKED mode ({@code partyMode = "RANKED"}) cannot simultaneously join a
 * custom lobby. Use {@link PartyCustomModeService} for custom-room operations.
 *
 * <p>Workflows:
 * <ul>
 *   <li>2 players in party → queue for 1v1 (allowFill=false) → waits for another pair</li>
 *   <li>2 players in party → queue for 2v2 (allowFill=true) → fills remaining with randoms</li>
 *   <li>3 players in party → queue for 4v4 (allowFill=true) → needs 1 solo fill</li>
 *   <li>4 players in party → queue for 4v4 (allowFill=false) → full team, waits for enemy</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartyRankedModeService {

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
    public void queueParty(Long hostUserId, PartyRankedQueueRequest request) {
        // Find host's party
        PartyMember hostMember = partyMemberRepository.findByUserId(hostUserId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.PARTY_NOT_IN_PARTY, "You are not in a party"));
        
        Party party = findPartyForUpdate(hostMember.getPartyId());
        
        // Validate: User is the host
        if (!party.getHostUserId().equals(hostUserId)) {
            throw new BusinessException(ErrorCodes.PARTY_NOT_HOST, "Only host can start matchmaking");
        }

        // Guard 1 — Mutual-exclusivity: partyMode=CUSTOM means party is in a custom lobby.
        // Check this FIRST so the user gets a clear message, not the generic "must be IDLE".
        if ("CUSTOM".equals(party.getPartyMode())) {
            throw new BusinessException(ErrorCodes.PARTY_IN_CUSTOM_MODE,
                    "Party is in a custom lobby. Leave the room before starting ranked matchmaking.");
        }

        // Guard 2 — Status must be IDLE (covers IN_QUEUE / IN_GAME / DISBANDED edge cases)
        if (!"IDLE".equals(party.getPartyStatus())) {
            throw new BusinessException(ErrorCodes.PARTY_NOT_IDLE,
                    "Party must be idle before matchmaking (current status: " + party.getPartyStatus() + ")");
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

        // Update party status + mode
        party.setPartyStatus("IN_QUEUE");
        party.setPartyMode("RANKED");   // lock context to RANKED
        partyRepository.save(party);
        
        // Add all members to matchmaking queue
        for (Long memberId : memberIds) {
            matchmakingQueueService.enqueuePartyMember(
                    memberId,
                    request.getGameMode(),
                    request.getMapId(),
                    request.getPlatform(),
                    party.getId(),
                    partySize,
                    request.isAllowFill()
            );
            log.info("Party member {} queued for matchmaking (party={}, mode={}, mapId={}, platform={})",
                memberId, party.getId(), request.getGameMode(), request.getMapId(), request.getPlatform());
        }

        // Publish party status change event
        messageBrokerService.publishPartyStatusChanged(party.getId(), "IDLE", "IN_QUEUE");
        
        log.info("Party {} queued for matchmaking: mode={}, mapId={}, size={}, allowFill={}, platform={}",
            party.getId(), request.getGameMode(), request.getMapId(), partySize, request.isAllowFill(), request.getPlatform());
    }

    /**
     * Cancel party matchmaking queue (host only or any member leaving).
     */
    @Transactional
    public void cancelQueue(Long userId) {
        PartyMember member = partyMemberRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.PARTY_NOT_IN_PARTY, "You are not in a party"));
        
        Party party = findPartyForUpdate(member.getPartyId());
        
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

        // Update party status back to IDLE and clear mode
        party.setPartyStatus("IDLE");
        party.setPartyMode("NONE");   // release context lock
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

    private Party findPartyForUpdate(Long partyId) {
        return partyRepository.findByIdForUpdate(partyId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.PARTY_NOT_FOUND, "Party not found"));
    }
}
