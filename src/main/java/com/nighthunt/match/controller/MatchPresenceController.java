package com.nighthunt.match.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.match.dto.MatchPresenceRequest;
import com.nighthunt.match.service.MatchPresenceService;
import com.nighthunt.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Match presence notices from the game server / host.
 */
@RestController
@RequestMapping("/match/presence")
@RequiredArgsConstructor
public class MatchPresenceController {
    private final MatchPresenceService matchPresenceService;

    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> reportUserPresence(@RequestBody MatchPresenceRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCodes.AUTH_REQUIRED, "User not authenticated");
        }
        matchPresenceService.recordUserPresence(userId, request);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
