package com.nighthunt.party;

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
import com.nighthunt.party.service.PartyRankedModeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for party-mode mutual-exclusivity guards.
 *
 * Covers:
 * <ol>
 *   <li>Cannot start ranked queue when party is in CUSTOM mode (in a custom room)</li>
 *   <li>Cannot start ranked queue when party is already IN_QUEUE (RANKED)</li>
 *   <li>Happy path: IDLE/NONE party can queue ranked</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class PartyModeGuardTest {

    @Mock private PartyRepository          partyRepo;
    @Mock private PartyMemberRepository    partyMemberRepo;
    @Mock private MatchmakingQueueService  queueService;
    @Mock private GameModeService          gameModeService;
    @Mock private MessageBrokerService     messageBroker;

    private PartyRankedModeService service;

    private static final Long HOST_ID = 10L;
    private static final Long PARTY_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new PartyRankedModeService(
                partyRepo, partyMemberRepo, queueService, gameModeService, messageBroker);
    }

    private PartyMember hostMember() {
        return PartyMember.builder().userId(HOST_ID).partyId(PARTY_ID).joinOrder(0).build();
    }

    private PartyRankedQueueRequest req() {
        return PartyRankedQueueRequest.builder()
                .gameMode("1v1").allowFill(false).build();
    }

    // ── Test 1: CUSTOM mode blocks ranked queue ────────────────────────────────

    @Test
    @DisplayName("queueParty throws PARTY_IN_CUSTOM_MODE when partyMode=CUSTOM")
    void queueParty_throwsWhenCustomMode() {
        when(partyMemberRepo.findByUserId(HOST_ID)).thenReturn(Optional.of(hostMember()));

        Party customParty = Party.builder()
                .id(PARTY_ID).hostUserId(HOST_ID)
                .partyStatus("IN_ROOM").partyMode("CUSTOM").build();
        when(partyRepo.findByIdForUpdate(PARTY_ID)).thenReturn(Optional.of(customParty));

        assertThatThrownBy(() -> service.queueParty(HOST_ID, req()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCodes.PARTY_IN_CUSTOM_MODE);

        // Queue service must NOT be called
        verify(queueService, never()).enqueuePartyMember(anyLong(), anyString(), any(), any());
    }

    // ── Test 2: IN_QUEUE (RANKED) also blocks a second queue call ─────────────

    @Test
    @DisplayName("queueParty throws PARTY_NOT_IDLE when party is already IN_QUEUE")
    void queueParty_throwsWhenAlreadyInQueue() {
        when(partyMemberRepo.findByUserId(HOST_ID)).thenReturn(Optional.of(hostMember()));

        Party rankParty = Party.builder()
                .id(PARTY_ID).hostUserId(HOST_ID)
                .partyStatus("IN_QUEUE").partyMode("RANKED").build();
        when(partyRepo.findByIdForUpdate(PARTY_ID)).thenReturn(Optional.of(rankParty));

        assertThatThrownBy(() -> service.queueParty(HOST_ID, req()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCodes.PARTY_NOT_IDLE);

        verify(queueService, never()).enqueuePartyMember(anyLong(), anyString(), any(), any());
    }

    // ── Test 3: non-host cannot start queue ───────────────────────────────────

    @Test
    @DisplayName("queueParty throws PARTY_NOT_HOST when non-host tries to start queue")
    void queueParty_throwsWhenNotHost() {
        Long memberId = 99L;
        PartyMember nonHostMember = PartyMember.builder().userId(memberId).partyId(PARTY_ID).joinOrder(1).build();
        when(partyMemberRepo.findByUserId(memberId)).thenReturn(Optional.of(nonHostMember));

        Party idleParty = Party.builder()
                .id(PARTY_ID).hostUserId(HOST_ID)   // HOST_ID is host, memberId is not
                .partyStatus("IDLE").partyMode("NONE").build();
        when(partyRepo.findByIdForUpdate(PARTY_ID)).thenReturn(Optional.of(idleParty));

        assertThatThrownBy(() -> service.queueParty(memberId, req()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCodes.PARTY_NOT_HOST);

        verify(queueService, never()).enqueuePartyMember(anyLong(), anyString(), any(), any());
    }

    // ── Test 4: IDLE/NONE party CAN queue ranked ──────────────────────────────

    @Test
    @DisplayName("queueParty succeeds and sets partyMode=RANKED for IDLE/NONE party")
    void queueParty_succeedsAndSetsRankedMode() {
        when(partyMemberRepo.findByUserId(HOST_ID)).thenReturn(Optional.of(hostMember()));

        Party idleParty = Party.builder()
                .id(PARTY_ID).hostUserId(HOST_ID)
                .partyStatus("IDLE").partyMode("NONE").maxMembers(2).build();
        when(partyRepo.findByIdForUpdate(PARTY_ID)).thenReturn(Optional.of(idleParty));
        when(gameModeService.isGameModeAvailable("1v1")).thenReturn(true);
        when(gameModeService.getPlayersPerTeam("1v1")).thenReturn(1);
        when(partyMemberRepo.findUserIdsByPartyId(PARTY_ID)).thenReturn(List.of(HOST_ID));

        service.queueParty(HOST_ID, req());

        // Party must be saved with IN_QUEUE + RANKED
        verify(partyRepo).save(argThat(p ->
                "IN_QUEUE".equals(p.getPartyStatus()) && "RANKED".equals(p.getPartyMode())));

        // enqueuePartyMember must be called for the host with premade party metadata
        verify(queueService).enqueuePartyMember(
                eq(HOST_ID), eq("1v1"), isNull(), isNull(), eq(PARTY_ID), eq(1), eq(false));
    }
}
