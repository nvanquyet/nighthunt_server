package com.nighthunt.matchmaking;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.dedicatedserver.dto.ServerAllocateResponse;
import com.nighthunt.dedicatedserver.service.DedicatedServerService;
import com.nighthunt.friend.service.PlayerStatusService;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.gamemode.dto.GameModeDTO;
import com.nighthunt.gamemode.service.GameModeService;
import com.nighthunt.map.service.GameMapService;
import com.nighthunt.matchmaking.entity.MatchmakingEntry;
import com.nighthunt.matchmaking.repository.MatchmakingEntryRepository;
import com.nighthunt.matchmaking.service.MatchmakingQueueService;
import com.nighthunt.party.entity.Party;
import com.nighthunt.party.repository.PartyMemberRepository;
import com.nighthunt.party.repository.PartyRepository;
import com.nighthunt.room.dto.RoomResponse;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.service.RoomService;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import static org.mockito.quality.Strictness.LENIENT;

/**
 * Unit tests for {@link MatchmakingQueueService}.
 *
 * Covers:
 * <ol>
 *   <li>Enqueue upsert — re-queuing replaces old SEARCHING entry</li>
 *   <li>sweepStaleQueueEntries — old SEARCHING entry → CANCELLED + WS notify</li>
 *   <li>sweepStaleQueueEntries — fresh entry is NOT cancelled</li>
 *   <li>ensureUserCanEnterMatchmaking — CUSTOM party mode blocks queue</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = LENIENT)
class MatchmakingQueueServiceTest {

    @Mock private MatchmakingEntryRepository entryRepo;
    @Mock private UserRepository             userRepo;
    @Mock private ConnectionManager          connectionManager;
    @Mock private RoomService                roomService;
    @Mock private RoomPlayerRepository       roomPlayerRepo;
    @Mock private PartyMemberRepository      partyMemberRepo;
    @Mock private PartyRepository            partyRepo;
    @Mock private DedicatedServerService     dsService;
    @Mock private GameModeService            gameModeService;
    @Mock private GameMapService             gameMapService;
    @Mock private PlayerStatusService        playerStatusService;

    private MatchmakingQueueService service;

    private static final Long   USER_A = 1L;
    private static final Long   USER_B = 2L;
    private static final String MODE   = "1v1";

    @BeforeEach
    void setUp() {
        service = new MatchmakingQueueService(
                entryRepo, userRepo, connectionManager, roomService,
                roomPlayerRepo, partyMemberRepo, partyRepo,
                dsService, gameModeService, gameMapService, playerStatusService);

        // Default lenient stubs — not every test uses all of these
        lenient().when(roomPlayerRepo.existsUserInActiveRoom(anyLong())).thenReturn(false);
        lenient().when(partyMemberRepo.findByUserId(anyLong())).thenReturn(Optional.empty());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User makeUser(Long id, int elo) {
        return User.builder().id(id).username("user_" + id)
                .email(id + "@test.com").passwordHash("x").elo(elo).build();
    }

    private GameModeDTO make1v1Mode(boolean devMode) {
        return GameModeDTO.builder()
                .modeKey(MODE)
                .displayName("1 vs 1")
                .modeStatus("AVAILABLE")
                .matchmakingEnabled(true)
                .isDevMode(devMode)
                .totalPlayers(2)
                .playersPerTeam(1)
                .build();
    }

    private MatchmakingEntry makeEntry(Long userId, String status, LocalDateTime queuedAt) {
        return MatchmakingEntry.builder()
                .userId(userId)
                .elo(1000)
                .gameMode(MODE)
                .status(status)
                .queuedAt(queuedAt)
                .searchMinElo(900)
                .searchMaxElo(1100)
                .build();
    }

    // ── Test 1: Upsert deletes old entry before saving new one ────────────────

    @Test
    @DisplayName("enqueue replaces a stale SEARCHING entry (upsert)")
    void enqueue_upsertDeletesOldEntry() {
        when(userRepo.findById(USER_A)).thenReturn(Optional.of(makeUser(USER_A, 1000)));
        when(gameModeService.getGameModeByKey(MODE)).thenReturn(make1v1Mode(false));
        when(gameMapService.isMapValid(any())).thenReturn(true);
        when(partyMemberRepo.existsByUserId(USER_A)).thenReturn(false);

        service.enqueue(USER_A, MODE, null, null);

        // deleteByUserId must be called to remove any stale entry
        verify(entryRepo).deleteByUserId(USER_A);
        // A new entry must be saved
        verify(entryRepo).save(argThat(e -> e.getStatus().equals("SEARCHING")));
    }

    // ── Test 2: processTick matches 2 SEARCHING entries ───────────────────────

    @Test
    @DisplayName("processTick forms a match when 2 players queue 1v1")
    @org.junit.jupiter.api.Disabled("Integration-level: requires full RoomService + DS wiring; covered by E2E tests")
    void processTick_formMatchWhen2PlayersQueued() {
        // This test requires a real Spring context to properly stub the room/DS pipeline.
        // Covered by manual 2-player test sessions and future @SpringBootTest integration tests.
    }

    // ── Test 3: sweepStaleQueueEntries cancels entries > 15 minutes old ───────

    @Test
    @DisplayName("sweepStaleQueueEntries cancels entry older than 15 minutes")
    void sweep_cancelsStaleEntry() {
        MatchmakingEntry stale = makeEntry(USER_A, "SEARCHING",
                LocalDateTime.now().minusMinutes(16));
        when(entryRepo.findSearchingQueuedBefore(any())).thenReturn(List.of(stale));

        service.sweepStaleQueueEntries();

        assertThat(stale.getStatus()).isEqualTo("CANCELLED");
        verify(entryRepo).save(stale);
        verify(connectionManager).sendToUser(eq(USER_A), eq("match_cancelled"), any());
    }

    @Test
    @DisplayName("sweepStaleQueueEntries does NOT cancel a fresh entry")
    void sweep_doesNotCancelFreshEntry() {
        // fresh entry queued 5 minutes ago → not returned by cutoff query
        when(entryRepo.findSearchingQueuedBefore(any())).thenReturn(List.of());

        service.sweepStaleQueueEntries();

        verify(entryRepo, never()).save(any());
        verify(connectionManager, never()).sendToUser(anyLong(), eq("match_cancelled"), any());
    }

    // ── Test 4: party CUSTOM mode blocks ranked enqueue ───────────────────────

    @Test
    @DisplayName("ensureUserCanEnterMatchmaking throws when user's party is in CUSTOM mode")
    void enqueue_throwsWhenPartyInCustomMode() {
        when(userRepo.findById(USER_A)).thenReturn(Optional.of(makeUser(USER_A, 1000)));
        when(gameModeService.getGameModeByKey(MODE)).thenReturn(make1v1Mode(false));
        when(partyMemberRepo.existsByUserId(USER_A)).thenReturn(false);

        // Simulate: user is in a party whose partyMode=CUSTOM
        var partyMember = com.nighthunt.party.entity.PartyMember.builder()
                .userId(USER_A).partyId(55L).build();
        when(partyMemberRepo.findByUserId(USER_A)).thenReturn(Optional.of(partyMember));
        Party customParty = Party.builder()
                .id(55L).hostUserId(USER_A)
                .partyStatus("IN_ROOM").partyMode("CUSTOM").build();
        when(partyRepo.findById(55L)).thenReturn(Optional.of(customParty));

        assertThatThrownBy(() -> service.enqueue(USER_A, MODE, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("custom lobby")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCodes.PARTY_IN_CUSTOM_MODE);
    }
}
