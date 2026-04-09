package com.nighthunt.profile.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.profile.dto.ProfileResponse;
import com.nighthunt.profile.dto.UpdateCharacterRequest;
import com.nighthunt.profile.service.ProfileService;
import com.nighthunt.security.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for player profile operations.
 *
 * <p>All endpoints require a valid JWT (Access Token) — enforced by
 * {@link com.nighthunt.security.filter.JwtAuthenticationFilter}.
 *
 * <p>Base path: {@code /profile} → actual: {@code /api/profile} (context-path /api)
 */
@RestController
@RequestMapping("/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    /**
     * GET /api/profile
     * Returns the authenticated player's profile including {@code selectedCharacterId}.
     * Called by the client after login / refresh to get the latest profile state.
     */
    @GetMapping
    public ApiResponse<ProfileResponse> getProfile() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        return ApiResponse.ok(profileService.getProfile(userId));
    }

    /**
     * PUT /api/profile/character
     * Persists the player's chosen character model.
     *
     * <p>Request body: {@code { "selectedCharacterId": "character_02" }}
     * <p>Response:      updated {@link ProfileResponse}
     */
    @PutMapping("/character")
    public ApiResponse<ProfileResponse> updateCharacter(
            @Valid @RequestBody UpdateCharacterRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        return ApiResponse.ok(profileService.updateCharacter(userId, request));
    }
}
