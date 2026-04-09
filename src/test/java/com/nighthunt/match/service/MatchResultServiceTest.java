package com.nighthunt.match.service;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.elo.service.EloService;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.match.dto.MatchEndRequest;
import com.nighthunt.match.dto.MatchEndResponse;
import com.nighthunt.match.entity.Match;
import com.nighthunt.match.entity.MatchPlayerResult;
import com.nighthunt.match.repository.MatchPlayerResultRepository;
import com.nighthunt.match.repository.MatchRepository;
import com.nighthunt.relay.service.RelaySessionManager;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MatchResultService}.
 */
@ExtendWith(MockitoExtension.class)
class MatchResultServiceTest {

    @Mock MatchRepository             matchRepository;
    @Mock MatchPlayerResultRepository resultRepository;
    @Mock UserRepository              userRepository;
    @Mock EloService                  eloService;
    @Mock RelaySessionManager         relaySessionManager;
    @Mock ConnectionManager           connectionManager;

    @InjectMocks MatchResultService matchResultService;

    // ── helpers ───────────────────────────────────────────────────────────────

    private Match matchInGame(String matchId) {
        return Match.builder()
                .id(1L).matchId(matchId).roomId(10L)
                .status("IN_GAME").build();
    }

    private User fakeUser(Long id, int elo) {
        return User.builder()
                .id(id).username("player" + id).email("p" + id + "@test.com")
                .elo(elo).tier("BRONZE")
                .totalWins(0).totalLosses(0).totalDraws(0)
                .build();
    }

    private MatchEndRequest buildRequest(String matchId, int winnerTeamId,
                                          String reason,
                                          List<MatchEndRequest.PlayerResultEntry> players) {
        MatchEndRequest req = new MatchEndRequest();
        req.setMatchId(matchId);
        req.setWinnerTeamId(winnerTeamId);
        req.setEndReason(reason);
        req.setPlayerResults(players);
        return req;
    }

    private MatchEndRequest.PlayerResultEntry playerEntry(Long userId, int teamId) {
        MatchEndRequest.PlayerResultEntry e = new MatchEndRequest.PlayerResultEntry();
        e.setUserId(userId);
        e.setTeamId(teamId);
        e.setDisplayName("Player" + userId);
        e.setKills(5);
        e.setDeaths(2);
        e.setScore(100);
        return e;
    }

    // ── matchNotFound ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("processMatchEnd: match not found → MATCH_NOT_FOUND")
    void matchNotFound_throws() {
        when(matchRepository.findByMatchId("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> matchResultService.processMatchEnd(
                buildRequest("unknown", 1, "TEAM_ELIMINATED", List.of()), false))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCodes.MATCH_NOT_FOUND);
    }

    @Test
    @DisplayName("processMatchEnd: already FINISHED → MATCH_NOT_FOUND")
    void alreadyFinished_throws() {
        Match finished = matchInGame("m1");
        finished.setStatus("FINISHED");
        when(matchRepository.findByMatchId("m1")).thenReturn(Optional.of(finished));

        assertThatThrownBy(() -> matchResultService.processMatchEnd(
                buildRequest("m1", 1, "TEAM_ELIMINATED", List.of()), false))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCodes.MATCH_NOT_FOUND);
    }

    // ── non-ranked match ──────────────────────────────────────────────────────

    @Test
    @DisplayName("processMatchEnd (non-ranked): ELO NOT updated, result rows persisted")
    void nonRanked_eloNotUpdated() {
        Match match = matchInGame("m1");
        when(matchRepository.findByMatchId("m1")).thenReturn(Optional.of(match));

        User u1 = fakeUser(1L, 1000);
        User u2 = fakeUser(2L, 1000);
        when(userRepository.findById(1L)).thenReturn(Optional.of(u1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(u2));
        when(resultRepository.save(any(MatchPlayerResult.class))).thenAnswer(inv -> inv.getArgument(0));
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));
        when(relaySessionManager.getByRoomId(anyLong())).thenReturn(Optional.empty());

        MatchEndRequest req = buildRequest("m1", 1, "TEAM_ELIMINATED",
                List.of(playerEntry(1L, 1), playerEntry(2L, 2)));
        matchResultService.processMatchEnd(req, false);

        // ELO not touched
        verifyNoInteractions(eloService);
        assertThat(u1.getElo()).isEqualTo(1000);
        assertThat(u2.getElo()).isEqualTo(1000);

        // Result rows persisted
        verify(resultRepository, times(2)).save(any(MatchPlayerResult.class));
    }

    // ── ranked match ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("processMatchEnd (ranked, team 1 wins): ELO delta applied to both players")
    void ranked_teamWin_eloApplied() {
        Match match = matchInGame("m2");
        when(matchRepository.findByMatchId("m2")).thenReturn(Optional.of(match));

        User winner = fakeUser(1L, 1000);
        User loser  = fakeUser(2L, 1000);
        when(userRepository.findById(1L)).thenReturn(Optional.of(winner));
        when(userRepository.findById(2L)).thenReturn(Optional.of(loser));
        when(eloService.calculateDelta(eq(1000), anyDouble(), eq(1.0))).thenReturn(20);
        when(eloService.calculateDelta(eq(1000), anyDouble(), eq(0.0))).thenReturn(-20);
        when(eloService.applyDelta(winner, 20)).thenReturn(20);
        when(eloService.applyDelta(loser,  -20)).thenReturn(-20);
        when(resultRepository.save(any(MatchPlayerResult.class))).thenAnswer(inv -> inv.getArgument(0));
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));
        when(relaySessionManager.getByRoomId(anyLong())).thenReturn(Optional.empty());

        MatchEndRequest req = buildRequest("m2", 1, "TEAM_ELIMINATED",
                List.of(playerEntry(1L, 1), playerEntry(2L, 2)));
        MatchEndResponse resp = matchResultService.processMatchEnd(req, true);

        verify(eloService).calculateDelta(eq(1000), anyDouble(), eq(1.0));  // winner
        verify(eloService).calculateDelta(eq(1000), anyDouble(), eq(0.0));  // loser
        verify(userRepository, times(2)).save(any(User.class));
        assertThat(resp.getWinnerTeamId()).isEqualTo(1);
        assertThat(resp.getPlayerResults()).hasSize(2);
    }

    @Test
    @DisplayName("processMatchEnd (ranked, draw): actualScore = 0.5 for all players")
    void ranked_draw_halfScoreForAll() {
        Match match = matchInGame("m3");
        when(matchRepository.findByMatchId("m3")).thenReturn(Optional.of(match));

        User u1 = fakeUser(1L, 1000);
        User u2 = fakeUser(2L, 1000);
        when(userRepository.findById(1L)).thenReturn(Optional.of(u1));
        when(userRepository.findById(2L)).thenReturn(Optional.of(u2));
        when(eloService.calculateDelta(anyInt(), anyDouble(), eq(0.5))).thenReturn(0);
        when(eloService.applyDelta(any(User.class), eq(0))).thenReturn(0);
        when(resultRepository.save(any(MatchPlayerResult.class))).thenAnswer(inv -> inv.getArgument(0));
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));
        when(relaySessionManager.getByRoomId(anyLong())).thenReturn(Optional.empty());

        // winnerTeamId = -1 → DRAW
        MatchEndRequest req = buildRequest("m3", -1, "DRAW",
                List.of(playerEntry(1L, 1), playerEntry(2L, 2)));
        matchResultService.processMatchEnd(req, true);

        // Both players get actualScore=0.5
        verify(eloService, times(2)).calculateDelta(anyInt(), anyDouble(), eq(0.5));
    }

    // ── match status update ───────────────────────────────────────────────────

    @Test
    @DisplayName("processMatchEnd: match status set to FINISHED after processing")
    void matchStatusUpdatedToFinished() {
        Match match = matchInGame("m4");
        when(matchRepository.findByMatchId("m4")).thenReturn(Optional.of(match));
        when(relaySessionManager.getByRoomId(anyLong())).thenReturn(Optional.empty());

        // No players — simplest valid call
        MatchEndRequest req = buildRequest("m4", 1, "TEAM_ELIMINATED", List.of());
        matchResultService.processMatchEnd(req, false);

        verify(matchRepository).save(argThat(m -> "FINISHED".equals(m.getStatus())));
    }

    // ── WebSocket broadcast ───────────────────────────────────────────────────

    @Test
    @DisplayName("processMatchEnd: match_ended event sent to every player")
    void matchEndedEventBroadcastToAllPlayers() {
        Match match = matchInGame("m5");
        when(matchRepository.findByMatchId("m5")).thenReturn(Optional.of(match));
        when(userRepository.findById(1L)).thenReturn(Optional.of(fakeUser(1L, 1000)));
        when(userRepository.findById(2L)).thenReturn(Optional.of(fakeUser(2L, 1000)));
        when(resultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(matchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(relaySessionManager.getByRoomId(anyLong())).thenReturn(Optional.empty());

        MatchEndRequest req = buildRequest("m5", 1, "TEAM_ELIMINATED",
                List.of(playerEntry(1L, 1), playerEntry(2L, 2)));
        matchResultService.processMatchEnd(req, false);

        verify(connectionManager).sendToUser(eq(1L), eq("match_ended"), any());
        verify(connectionManager).sendToUser(eq(2L), eq("match_ended"), any());
    }
}
