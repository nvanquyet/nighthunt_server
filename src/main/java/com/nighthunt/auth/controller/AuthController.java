package com.nighthunt.auth.controller;

import com.nighthunt.auth.dto.*;
import com.nighthunt.auth.service.AuthService;
import com.nighthunt.common.ApiResponse;
import com.nighthunt.security.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ApiResponse.ok(response);
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ApiResponse.ok(response);
    }

    @PostMapping("/auto-login")
    public ApiResponse<AuthResponse> autoLogin(@Valid @RequestBody AutoLoginRequest request) {
        AuthResponse response = authService.autoLogin(request);
        return ApiResponse.ok(response);
    }

    /**
     * Exchange a refresh token for a new access token (+ rotated refresh token).
     * This is the production auto-login mechanism: client stores refreshToken persistently
     * and calls this endpoint on every app startup instead of re-prompting the user.
     */
    @PostMapping("/refresh-token")
    public ApiResponse<AuthResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refreshToken(request);
        return ApiResponse.ok(response);
    }

    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        }
        authService.changePassword(userId, request);
        return ApiResponse.ok();
    }

    /**
     * Logout - Clear session and force logout flag
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        }
        authService.logout(userId);
        return ApiResponse.ok();
    }

    /**
     * Check session status - lightweight endpoint for session monitoring
     * Returns success if session is valid, otherwise throws exception (handled by filter)
     */
    @GetMapping("/check-session")
    public ApiResponse<Void> checkSession() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        }
        // If we get here, session is valid (JWT filter already validated)
        return ApiResponse.ok();
    }
}

