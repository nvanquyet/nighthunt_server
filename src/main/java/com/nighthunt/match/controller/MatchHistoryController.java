package com.nighthunt.match.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.match.entity.Match;
import com.nighthunt.match.entity.MatchPlayerResult;
import com.nighthunt.match.repository.MatchPlayerResultRepository;
import com.nighthunt.match.repository.MatchRepository;
import com.nighthunt.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Match history & detail endpoints.
 *
 * <pre>
 *   GET /api/match/history?page=0&size=20&mode=ranked_tdm&status=FINISHED
 *       → admin: all matches (paginated, filterable)
 *       → player: own match history (filtered by userId)
 *
 *   GET /api/match/{matchId}/details
 *       → full match record + per-player results
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/match")
@RequiredArgsConstructor
public class MatchHistoryController {

    private final MatchRepository             matchRepo;
    private final MatchPlayerResultRepository playerResultRepo;

    // ── GET /match/history ────────────────────────────────────────────────────

    /**
     * Returns paginated match history.
     * - ADMIN (X-Admin-Secret caller uses /dashboard/* instead — this is for JWT-authed admins):
     *   returns all matches, filterable by status & mode.
     * - Regular player: returns only matches they participated in.
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/history")
    public ApiResponse<Map<String, Object>> getMatchHistory(
            @RequestParam(defaultValue = "0")   int page,
            @RequestParam(defaultValue = "20")  int size,
            @RequestParam(required = false)      String mode,
            @RequestParam(required = false)      String status) {

        page = Math.max(0, page);
        size = Math.min(Math.max(1, size), 100);

        Long userId = SecurityUtils.getCurrentUserId();
        boolean isAdmin = isAdminRole();

        List<Map<String, Object>> rows;
        long totalElements;

        if (isAdmin) {
            // Admin: filterable full history
            Page<Match> pageResult = matchRepo.findFiltered(
                    status, mode,
                    PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
            rows = pageResult.getContent().stream().map(this::matchToMap).toList();
            totalElements = pageResult.getTotalElements();
        } else {
            // Player: own matches only — join through MatchPlayerResult
            List<MatchPlayerResult> myResults = playerResultRepo.findByUserId(userId);
            List<String> myMatchIds = myResults.stream()
                    .map(MatchPlayerResult::getMatchId).distinct().toList();

            // Apply filters in memory (acceptable for realistic player history sizes)
            var filtered = myMatchIds.stream()
                    .map(matchRepo::findByMatchId)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(m -> status == null || status.equalsIgnoreCase(m.getStatus()))
                    .filter(m -> mode   == null || mode.equalsIgnoreCase(m.getGameMode()))
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .toList();

            totalElements = filtered.size();
            int from = page * size;
            int to   = Math.min(from + size, filtered.size());
            rows = (from >= filtered.size())
                    ? List.of()
                    : filtered.subList(from, to).stream().map(this::matchToMap).toList();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page",    page);
        result.put("size",    size);
        result.put("total",   totalElements);
        result.put("matches", rows);
        return ApiResponse.ok(result);
    }

    // ── GET /match/{matchId}/details ──────────────────────────────────────────

    /**
     * Returns full match details + per-player results.
     * Players can only view matches they participated in (or ADMIN can view any).
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{matchId}/details")
    public ApiResponse<Map<String, Object>> getMatchDetails(@PathVariable String matchId) {
        // Validate matchId format — UUIDs only
        if (!matchId.matches("[a-zA-Z0-9\\-]{8,50}")) {
            return ApiResponse.error("Invalid matchId", "BAD_REQUEST");
        }

        Match match = matchRepo.findByMatchId(matchId).orElse(null);
        if (match == null) {
            return ApiResponse.error("Match not found: " + matchId, "NOT_FOUND");
        }

        Long userId  = SecurityUtils.getCurrentUserId();
        boolean isAdmin = isAdminRole();

        if (!isAdmin) {
            // Verify the calling user participated in this match
            List<MatchPlayerResult> myResults = playerResultRepo.findByUserId(userId);
            boolean participated = myResults.stream()
                    .anyMatch(r -> matchId.equals(r.getMatchId()));
            if (!participated) {
                return ApiResponse.error("Access denied", "FORBIDDEN");
            }
        }

        List<MatchPlayerResult> playerResults = playerResultRepo.findByMatchId(matchId);
        List<Map<String, Object>> players = playerResults.stream()
                .map(this::playerResultToMap)
                .toList();

        Map<String, Object> details = matchToMap(match);
        details.put("players", players);
        return ApiResponse.ok(details);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static boolean isAdminRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    private Map<String, Object> matchToMap(Match m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("matchId",      m.getMatchId());
        map.put("roomId",       m.getRoomId());
        map.put("status",       m.getStatus());
        map.put("gameMode",     m.getGameMode());
        map.put("winnerTeamId", m.getWinnerTeamId());
        map.put("endReason",    m.getEndReason());
        map.put("startedAt",    m.getStartedAt());
        map.put("finishedAt",   m.getFinishedAt());
        map.put("createdAt",    m.getCreatedAt());
        long durationSec = 0;
        if (m.getStartedAt() != null && m.getFinishedAt() != null) {
            durationSec = java.time.Duration.between(m.getStartedAt(), m.getFinishedAt()).getSeconds();
        }
        map.put("durationSeconds", durationSec);
        return map;
    }

    private Map<String, Object> playerResultToMap(MatchPlayerResult r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId",      r.getUserId());
        map.put("displayName", r.getDisplayName());
        map.put("teamId",      r.getTeamId());
        map.put("kills",       r.getKills());
        map.put("deaths",      r.getDeaths());
        map.put("score",       r.getScore());
        map.put("eloBefore",   r.getEloBefore());
        map.put("eloAfter",    r.getEloAfter());
        map.put("eloChange",   r.getEloChange());
        map.put("placement",   r.getPlacement());  // 1=win, 2=lose, 0=draw
        return map;
    }
}
