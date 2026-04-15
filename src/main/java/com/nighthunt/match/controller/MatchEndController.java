package com.nighthunt.match.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.dedicatedserver.service.DedicatedServerService;
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
 *       (authenticated with {@code X-DS-Secret} or {@code serverSecret} body field);
 *       validates DS identity, resolves matchId, triggers ELO update.</li>
 *   <li>{@code POST /api/match/end/custom} — called by the relay host client
 *       (authenticated as a normal user); no ELO update.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/match/end")
@RequiredArgsConstructor
public class MatchEndController {

    private final MatchResultService    matchResultService;
    private final DedicatedServerService dsService;

    /**
     * Ranked match end — triggers ELO update.
     *
     * <p>DS authentication: validates {@code serverId} + {@code serverSecret}
     * (body fields) or {@code X-DS-Secret} header via BCrypt against DB hash.
     * If {@code matchId} is absent, it is resolved from the DS entity.</p>
     */
    @PostMapping("/ranked")
    public ResponseEntity<ApiResponse<MatchEndResponse>> endRanked(
            @RequestBody MatchEndRequest req,
            @RequestHeader(value = "X-DS-Secret", required = false) String headerSecret) {

        // Resolve secret: body field takes priority, then header
        String plainSecret = (req.getServerSecret() != null && !req.getServerSecret().isBlank())
                ? req.getServerSecret() : headerSecret;

        // Authenticate DS and resolve matchId (throws if credentials invalid)
        if (req.getServerId() == null || req.getServerId().isBlank()) {
            log.warn("[MatchEnd] /ranked called without serverId — rejecting");
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("serverId is required for ranked match end", "BAD_REQUEST"));
        }

        String resolvedMatchId;
        try {
            resolvedMatchId = dsService.validateDsAndGetMatchId(req.getServerId(), plainSecret);
        } catch (IllegalArgumentException | SecurityException e) {
            log.warn("[MatchEnd] DS auth failed for serverId={}: {}", req.getServerId(), e.getMessage());
            return ResponseEntity.status(403)
                    .body(ApiResponse.error("Invalid DS credentials", "AUTH_FAILED"));
        }

        // Use DS-stored matchId when DS didn't know its own (assignexd after container start)
        if (req.getMatchId() == null || req.getMatchId().isBlank()) {
            req.setMatchId(resolvedMatchId);
        }

        if (req.getMatchId() == null || req.getMatchId().isBlank()) {
            log.warn("[MatchEnd] DS {} has no matchId — cannot process ranked end", req.getServerId());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("No matchId associated with this DS", "MATCH_NOT_FOUND"));
        }

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
