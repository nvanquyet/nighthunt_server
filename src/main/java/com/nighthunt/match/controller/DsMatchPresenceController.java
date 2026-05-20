package com.nighthunt.match.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.dedicatedserver.service.DedicatedServerService;
import com.nighthunt.match.dto.MatchPresenceRequest;
import com.nighthunt.match.service.MatchPresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/ds/match/presence")
@RequiredArgsConstructor
public class DsMatchPresenceController {
    private final MatchPresenceService matchPresenceService;
    private final DedicatedServerService dsService;

    @PostMapping
    public ResponseEntity<ApiResponse<Void>> reportDsPresence(
            @RequestBody MatchPresenceRequest request,
            @RequestHeader(value = "X-DS-Secret", required = false) String headerSecret) {
        String plainSecret = (request.getServerSecret() != null && !request.getServerSecret().isBlank())
                ? request.getServerSecret() : headerSecret;
        if (request.getServerId() == null || request.getServerId().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("serverId is required", "BAD_REQUEST"));
        }

        try {
            String resolvedMatchId = dsService.validateDsAndGetMatchId(request.getServerId(), plainSecret);
            if (request.getMatchId() == null || request.getMatchId().isBlank()) {
                request.setMatchId(resolvedMatchId);
            }
        } catch (IllegalArgumentException | SecurityException ex) {
            log.warn("[MatchPresence] DS auth failed for serverId={}: {}", request.getServerId(), ex.getMessage());
            return ResponseEntity.status(403).body(ApiResponse.error("Invalid DS credentials", "AUTH_FAILED"));
        }

        matchPresenceService.recordServerPresence(request);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
