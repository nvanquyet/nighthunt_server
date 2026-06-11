package com.nighthunt.matchmaking;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.dedicatedserver.dto.ServerAllocateResponse;
import com.nighthunt.dedicatedserver.service.DedicatedServerService;
import com.nighthunt.config.gameconfig.RuntimeConfigService;
import com.nighthunt.friend.service.PlayerStatusService;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.gamemode.dto.GameModeDTO;
import com.nighthunt.gamemode.service.GameModeService;
import com.nighthunt.map.service.GameMapService;
import com.nighthunt.messaging.service.MessageBrokerService;
import com.nighthunt.matchmaking.entity.MatchmakingEntry;
import com.nighthunt.matchmaking.repository.MatchmakingEntryRepository;
import com.nighthunt.matchmaking.service.MatchmakingQueueService;
import com.nighthunt.party.entity.Party;
import com.nighthunt.party.repository.PartyMemberRepository;
import com.nighthunt.party.repository.PartyRepository;
import com.nighthunt.room.dto.RoomResponse;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
 *
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = LENIENT)
class MatchmakingQueueServiceTest {

    @Mock private MatchmakingEntryRepository entryRepo;
    @Mock private UserRepository             userRepo;
    @Mock private ConnectionManager          connectionManager;
    @Mock private RoomService                roomService;
    @Mock private RoomPlayerRepository       roomPlayerRepo;
    @Mock private RoomRepository             roomRepo;
    @Mock private PartyMemberRepository      partyMemberRepo;
    @Mock private PartyRepository            partyRepo;
    @Mock private DedicatedServerService     dsService;
    @Mock private GameModeService            gameModeService;
    @Mock private GameMapService             gameMapService;
    @Mock private PlayerStatusService        playerStatusService;
    @Mock private RuntimeConfigService          runtimeConfig;
    @Mock private MessageBrokerService       messageBrokerService;
    @Mock private StringRedisTemplate         redisTemplate;
    @Mock private ValueOperations<String, String> redisValues;

    private MatchmakingQueueService service;

    private static final Long   USER_A = 1L;
    private static final Long   USER_B = 2L;
    private static final String MODE   = "1v1";

    @BeforeEach
    void setUp() {
        service = new MatchmakingQueueService(
                entryRepo, userRepo, connectionManager, roomService,
                roomPlayerRepo, roomRepo, partyMemberRepo, partyRepo,
                dsService, gameModeService, gameMapService, playerStatusService, runtimeConfig,
                messageBrokerService, redisTemplate);

        // Default lenient stubs — not every test uses all of these
        lenient().when(runtimeConfig.getInt(eq("matchmaking.elo.initialRange"), anyInt())).thenReturn(100);
        lenient().when(runtimeConfig.getInt(eq("matchmaking.elo.expandStep"), anyInt())).thenReturn(50);
        lenient().when(runtimeConfig.getInt(eq("matchmaking.elo.expandIntervalSec"), anyInt())).thenReturn(15);
        lenient().when(runtimeConfig.getInt(eq("matchmaking.elo.maxRange"), anyInt())).thenReturn(500);
        lenient().when(roomPlayerRepo.existsUserInActiveRoom(anyLong())).thenReturn(false);
        lenient().when(partyMemberRepo.findByUserId(anyLong())).thenReturn(Optional.empty());
        lenient().when(redisTemplate.opsForValue()).thenReturn(redisValues);
        lenient().when(redisValues.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        lenient().when(redisTemplate.execute(
                any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(1L);
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
                .allowFill(true)
                .isDevMode(devMode)
                .isActive(true)
                .totalPlayers(2)
                .playersPerTeam(1)
                .platformFilter("ALL")
                .build();
    }

    private GameModeDTO make4v4Mode() {
        return GameModeDTO.builder()
                .modeKey("4v4")
                .displayName("4 vs 4")
                .modeStatus("AVAILABLE")
                .matchmakingEnabled(true)
                .allowFill(true)
                .isActive(true)
                .totalPlayers(8)
                .playersPerTeam(4)
                .platformFilter("ALL")
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
                .queueGroupId("solo:" + userId)
                .partySize(1)
                .allowFill(true)
                .build();
    }

    private MatchmakingEntry makePartyEntry(
            Long userId,
            Long partyId,
            int partySize,
            boolean allowFill,
            LocalDateTime queuedAt
    ) {
        return MatchmakingEntry.builder()
                .userId(userId)
                .elo(1000)
                .gameMode("4v4")
                .status("SEARCHING")
                .queuedAt(queuedAt)
                .searchMinElo(900)
                .searchMaxElo(1100)
                .queueGroupId("party:" + partyId)
                .partyId(partyId)
                .partySize(partySize)
                .allowFill(allowFill)
                .build();
    }

    private void stubRankedRoomCreation(int playerCount) {
        when(roomService.createRankedRoom(anyList(), eq("4v4"), isNull(), anyMap()))
                .thenReturn(RoomResponse.builder()
                        .roomId(99L)
                        .roomCode("ROOM01")
                        .matchId("match-1")
                        .players(List.of())
                        .build());
        when(dsService.allocateServerForMatch("vn", null, playerCount, "match-1"))
                .thenReturn(ServerAllocateResponse.builder()
                        .ip("127.0.0.1")
                        .port(7777)
                        .sessionToken("session")
                        .build());
    }

    // ── Test 1: Upsert deletes old entry before saving new one ────────────────

    @Test
    @DisplayName("enqueue replaces a stale SEARCHING entry (upsert)")
    void enqueue_upsertDeletesOldEntry() {
        when(userRepo.findById(USER_A)).thenReturn(Optional.of(makeUser(USER_A, 1000)));
        when(gameModeService.getGameModeByKey(MODE)).thenReturn(make1v1Mode(false));
        when(gameMapService.isMapValidForMatchmaking(any(), any(), anyInt())).thenReturn(true);
        when(partyMemberRepo.existsByUserId(USER_A)).thenReturn(false);

        service.enqueue(USER_A, MODE, null, null);

        // deleteByUserId must be called to remove any stale entry
        verify(entryRepo).deleteByUserId(USER_A);
        // A new entry must be saved
        verify(entryRepo).save(argThat(e -> e.getStatus().equals("SEARCHING")));
    }

    @Test
    @DisplayName("enqueue rejects inactive mode because scheduler will not process it")
    void enqueue_rejectsInactiveMode() {
        GameModeDTO inactiveMode = make1v1Mode(false);
        inactiveMode.setActive(false);
        when(gameModeService.getGameModeByKey(MODE)).thenReturn(inactiveMode);
        when(partyMemberRepo.existsByUserId(USER_A)).thenReturn(false);

        assertThatThrownBy(() -> service.enqueue(USER_A, MODE, "map_01", "PC"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Game mode not available for matchmaking");

        verify(entryRepo, never()).save(any());
        verify(userRepo, never()).findById(anyLong());
    }

    @Test
    @DisplayName("enqueuePartyMember stores premade party matchmaking metadata")
    void enqueuePartyMember_storesPartyMetadata() {
        when(userRepo.findById(USER_A)).thenReturn(Optional.of(makeUser(USER_A, 1000)));
        when(gameModeService.getGameModeByKey("4v4")).thenReturn(make4v4Mode());
        when(gameMapService.isMapValidForMatchmaking("map_01", "4v4", 8)).thenReturn(true);

        service.enqueuePartyMember(USER_A, "4v4", "map_01", "PC", 55L, 2, false);

        ArgumentCaptor<MatchmakingEntry> captor = ArgumentCaptor.forClass(MatchmakingEntry.class);
        verify(entryRepo).save(captor.capture());
        MatchmakingEntry saved = captor.getValue();

        assertThat(saved.getQueueGroupId()).isEqualTo("party:55");
        assertThat(saved.getPartyId()).isEqualTo(55L);
        assertThat(saved.getPartySize()).isEqualTo(2);
        assertThat(saved.isAllowFill()).isFalse();
        assertThat(saved.getPlatform()).isEqualTo("PC");
    }

    @Test
    @DisplayName("enqueuePartyMember rejects client fill override when mode config disables Fill Party")
    void enqueuePartyMember_rejectsDisabledFillOverride() {
        GameModeDTO mode = make4v4Mode();
        mode.setAllowFill(false);
        when(gameModeService.getGameModeByKey("4v4")).thenReturn(mode);

        assertThatThrownBy(() ->
                service.enqueuePartyMember(USER_A, "4v4", "map_01", "PC", 55L, 2, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Fill Party is disabled");

        verifyNoInteractions(userRepo);
    }

    @Test
    @DisplayName("processTick keeps locked underfilled premade parties on separate teams")
    void processTick_keepsLockedUnderfilledPremadesOnSeparateTeams() {
        LocalDateTime queuedAt = LocalDateTime.now().minusSeconds(1);
        List<MatchmakingEntry> entries = List.of(
                makePartyEntry(1L, 10L, 2, false, queuedAt),
                makePartyEntry(2L, 10L, 2, false, queuedAt.plusNanos(1)),
                makePartyEntry(3L, 20L, 2, false, queuedAt.plusNanos(2)),
                makePartyEntry(4L, 20L, 2, false, queuedAt.plusNanos(3)));

        when(entryRepo.findSearchingQueuedBefore(any())).thenReturn(List.of());
        when(gameModeService.getMatchmakingEnabledModes()).thenReturn(List.of(make4v4Mode()));
        when(entryRepo.findSearchingByMode("4v4")).thenReturn(entries);
        stubRankedRoomCreation(4);

        service.processTick();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, Integer>> teamCaptor = ArgumentCaptor.forClass(Map.class);
        verify(roomService).createRankedRoom(anyList(), eq("4v4"), isNull(), teamCaptor.capture());
        assertThat(teamCaptor.getValue())
                .containsEntry(1L, 1)
                .containsEntry(2L, 1)
                .containsEntry(3L, 2)
                .containsEntry(4L, 2);
    }

    @Test
    @DisplayName("processTick fills a premade team with temporary queue units without changing party membership")
    void processTick_fillsPremadeWithTemporaryUnitsOnly() {
        LocalDateTime queuedAt = LocalDateTime.now().minusSeconds(1);
        List<MatchmakingEntry> entries = List.of(
                makePartyEntry(1L, 10L, 2, true, queuedAt),
                makePartyEntry(2L, 10L, 2, true, queuedAt.plusNanos(1)),
                makePartyEntry(3L, 20L, 2, true, queuedAt.plusNanos(2)),
                makePartyEntry(4L, 20L, 2, true, queuedAt.plusNanos(3)),
                makePartyEntry(5L, 30L, 4, false, queuedAt.plusNanos(4)),
                makePartyEntry(6L, 30L, 4, false, queuedAt.plusNanos(5)),
                makePartyEntry(7L, 30L, 4, false, queuedAt.plusNanos(6)),
                makePartyEntry(8L, 30L, 4, false, queuedAt.plusNanos(7)));

        when(entryRepo.findSearchingQueuedBefore(any())).thenReturn(List.of());
        when(gameModeService.getMatchmakingEnabledModes()).thenReturn(List.of(make4v4Mode()));
        when(entryRepo.findSearchingByMode("4v4")).thenReturn(entries);
        stubRankedRoomCreation(8);

        service.processTick();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, Integer>> teamCaptor = ArgumentCaptor.forClass(Map.class);
        verify(roomService).createRankedRoom(anyList(), eq("4v4"), isNull(), teamCaptor.capture());
        assertThat(teamCaptor.getValue())
                .containsEntry(1L, 1)
                .containsEntry(2L, 1)
                .containsEntry(3L, 1)
                .containsEntry(4L, 1)
                .containsEntry(5L, 2)
                .containsEntry(6L, 2)
                .containsEntry(7L, 2)
                .containsEntry(8L, 2);
        verify(partyMemberRepo, never()).save(any());
    }

    // ── Test 2: processTick matches 2 SEARCHING entries ───────────────────────

    @Test
    @DisplayName("processTick forms 1v1, notifies match_found, allocates DS, and broadcasts match_ready")
    void processTick_formMatchWhen2PlayersQueued() {
        LocalDateTime queuedAt = LocalDateTime.now().minusSeconds(1);
        MatchmakingEntry entryA = makeEntry(USER_A, "SEARCHING", queuedAt);
        MatchmakingEntry entryB = makeEntry(USER_B, "SEARCHING", queuedAt.plusNanos(1));

        when(entryRepo.findSearchingQueuedBefore(any())).thenReturn(List.of());
        when(gameModeService.getMatchmakingEnabledModes()).thenReturn(List.of(make1v1Mode(false)));
        when(entryRepo.findSearchingByMode(MODE)).thenReturn(List.of(entryA, entryB));
        when(roomService.createRankedRoom(anyList(), eq(MODE), isNull(), anyMap()))
                .thenReturn(RoomResponse.builder()
                        .roomId(99L)
                        .roomCode("ROOM01")
                        .matchId("match-1")
                        .players(List.of())
                        .build());
        when(dsService.allocateServerForMatch("vn", null, 2, "match-1"))
                .thenReturn(ServerAllocateResponse.builder()
                        .ip("127.0.0.1")
                        .port(7777)
                        .sessionToken("session")
                        .build());

        service.processTick();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, Integer>> teamCaptor = ArgumentCaptor.forClass(Map.class);
        verify(roomService).createRankedRoom(eq(List.of(USER_A, USER_B)), eq(MODE), isNull(), teamCaptor.capture());
        assertThat(teamCaptor.getValue())
                .containsEntry(USER_A, 1)
                .containsEntry(USER_B, 2);

        verify(dsService).allocateServerForMatch("vn", null, 2, "match-1");
        verify(connectionManager).sendToUser(eq(USER_A), eq("match_found"), argThat(payload ->
                payload instanceof Map<?, ?> map
                        && MODE.equals(map.get("gameMode"))
                        && map.get("lobbyToken") instanceof String
                        && map.get("playerIds") instanceof List<?> players
                        && players.containsAll(List.of(USER_A, USER_B))));
        verify(connectionManager).sendToUser(eq(USER_B), eq("match_found"), anyMap());
        verify(connectionManager).sendToUser(eq(USER_A), eq("match_ready"), argThat(payload ->
                payload instanceof Map<?, ?> map
                        && "match-1".equals(map.get("matchId"))
                        && "127.0.0.1".equals(map.get("dsIp"))
                        && Integer.valueOf(7777).equals(map.get("dsPort"))
                        && "session".equals(map.get("sessionToken"))));
        verify(connectionManager).sendToUser(eq(USER_B), eq("match_ready"), anyMap());
        verify(entryRepo).delete(entryA);
        verify(entryRepo).delete(entryB);
        verify(roomService).markRankedRoomInGame("match-1");
    }

    @Test
    @DisplayName("processTick cancels matched players when DS allocation fails")
    void processTick_cancelsMatchWhenDsAllocationFails() {
        LocalDateTime queuedAt = LocalDateTime.now().minusSeconds(1);
        MatchmakingEntry entryA = makeEntry(USER_A, "SEARCHING", queuedAt);
        MatchmakingEntry entryB = makeEntry(USER_B, "SEARCHING", queuedAt.plusNanos(1));

        when(entryRepo.findSearchingQueuedBefore(any())).thenReturn(List.of());
        when(gameModeService.getMatchmakingEnabledModes()).thenReturn(List.of(make1v1Mode(false)));
        when(entryRepo.findSearchingByMode(MODE)).thenReturn(List.of(entryA, entryB));
        when(roomService.createRankedRoom(anyList(), eq(MODE), isNull(), anyMap()))
                .thenReturn(RoomResponse.builder()
                        .roomId(99L)
                        .roomCode("ROOM01")
                        .matchId("match-1")
                        .players(List.of())
                        .build());
        when(dsService.allocateServerForMatch("vn", null, 2, "match-1"))
                .thenThrow(new RuntimeException("docker failed"));

        service.processTick();

        verify(connectionManager).sendToUser(eq(USER_A), eq("match_found"), anyMap());
        verify(connectionManager).sendToUser(eq(USER_B), eq("match_found"), anyMap());
        verify(connectionManager).sendToUser(eq(USER_A), eq("match_cancelled"), argThat(payload ->
                payload instanceof Map<?, ?> map
                        && "Could not start dedicated server. Please try again.".equals(map.get("reason"))));
        verify(connectionManager).sendToUser(eq(USER_B), eq("match_cancelled"), anyMap());
        verify(connectionManager, never()).sendToUser(anyLong(), eq("match_ready"), anyMap());
        verify(roomService).disbandRoom(99L, USER_A);
        verify(entryRepo, never()).deleteByUserId(anyLong());
        verify(roomService, never()).markRankedRoomInGame(anyString());
        assertThat(entryA.getStatus()).isEqualTo("CANCELLED");
        assertThat(entryB.getStatus()).isEqualTo("CANCELLED");
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
