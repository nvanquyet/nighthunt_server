package com.nighthunt.match.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.match.dto.MatchEndRequest;
import com.nighthunt.match.dto.MatchEndResponse;
import com.nighthunt.match.service.MatchResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * BE-30 — Match end controller.
 *
 * <p>Two variants:
 * <ul>
 *   <li>{@code POST /api/match/end/ranked} — called by a Dedicated Server
 *       (authenticated with {@code X-DS-Secret}); triggers ELO update.</li>
 *   <li>{@code POST /api/match/end/custom} — called by the relay host client
 *       (authenticated as a normal user); no ELO update.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/match/end")
@RequiredArgsConstructor
public class MatchEndController {

    private final MatchResultService matchResultService;

    /**
     * Ranked match end — triggers ELO update.
     * Called by DS using {@code X-DS-Secret} header (validated via SecurityConfig).
     */
    @PostMapping("/ranked")
    public ResponseEntity<ApiResponse<MatchEndResponse>> endRanked(
            @RequestBody MatchEndRequest req) {
        MatchEndResponse resp = matchResultService.processMatchEnd(req, true);
        return ResponseEntity.ok(ApiResponse.ok(resp));
    }

    /**
     * Custom match end — persists stats but skips ELO.
     * Called by the custom lobby host (normal JWT auth).
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/custom")
    public ResponseEntity<ApiResponse<MatchEndResponse>> endCustom(
            @RequestBody MatchEndRequest req) {
        MatchEndResponse resp = matchResultService.processMatchEnd(req, false);
        return ResponseEntity.ok(ApiResponse.ok(resp));
    }
}
