package com.nighthunt.auth.service;

import com.nighthunt.auth.dto.*;
import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.security.port.TokenProvider;
import com.nighthunt.session.port.SessionStore;
import com.nighthunt.session.service.SessionService;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenProvider tokenProvider;
    private final SessionStore sessionStore;
    private final SessionService sessionService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Check username exists
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException(ErrorCodes.AUTH_USERNAME_EXISTS,
                    "Username already exists");
        }

        // Check email exists
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException(ErrorCodes.AUTH_EMAIL_EXISTS,
                    "Email already exists");
        }

        // Check password match
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException(ErrorCodes.AUTH_PASSWORD_MISMATCH,
                    "Password and confirm password do not match");
        }

        // Create user
        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .build();
        user = userRepository.save(user);

        // Generate token and session
        String accessToken = tokenProvider.generateToken(user.getId(), user.getUsername());
        String sessionId = UUID.randomUUID().toString();

        // Save session
        String userIdStr = String.valueOf(user.getId());
        sessionStore.saveSession(userIdStr, sessionId, GameConstants.SESSION_TIMEOUT_SECONDS);
        // Ensure no stale force-logout flag remains for this fresh session
        sessionStore.deleteForceLogout(userIdStr);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .sessionId(sessionId)
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .build();
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        // Find user by username or email
        User user = userRepository.findByUsername(request.getIdentifier())
                .or(() -> userRepository.findByEmail(request.getIdentifier()))
                .orElseThrow(() -> new BusinessException(ErrorCodes.AUTH_INVALID_CREDENTIALS,
                        "Invalid username/email or password"));

        // Check password
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCodes.AUTH_INVALID_CREDENTIALS,
                    "Invalid username/email or password");
        }

        String userIdStr = String.valueOf(user.getId());

        // Check if there's an active session
        String existingSessionId = sessionStore.getSessionId(userIdStr);
        boolean hasForceLogout = sessionStore.isForceLogout(userIdStr);
        
        log.info("Login attempt for user {} - existingSessionId: {}, hasForceLogout: {}", 
                userIdStr, existingSessionId, hasForceLogout);
        
        if (existingSessionId != null && !hasForceLogout) {
            // Active session exists and no force logout flag - this means another device (A) is currently logged in
            // Set force logout flag for the active session (user A will detect this via polling)
            sessionStore.setForceLogout(userIdStr, true);
            log.warn("BLOCKING login for user {} - Active session exists (sessionId: {}). Force logout set for active session.", 
                    userIdStr, existingSessionId);
            
            // Block user B from logging in - throw exception
            // NOTE: Do NOT delete session of A here - let A detect force logout and logout properly
            throw new BusinessException(ErrorCodes.AUTH_FORCE_LOGOUT,
                    "Tài khoản đã đăng nhập ở nơi khác. Vui lòng thử lại.");
        }

        // No active session OR force logout flag is set (user was force logged out)
        log.info("ALLOWING login for user {} - No active session or force logout flag is set. Proceeding with login.", userIdStr);
        
        // Clear any stale force-logout flag and old session before creating new one
        sessionStore.deleteForceLogout(userIdStr);
        if (existingSessionId != null) {
            // Only delete old session if it exists (user was force logged out)
            log.info("Deleting old session for user {} (sessionId: {})", userIdStr, existingSessionId);
            sessionStore.deleteSession(userIdStr);
        }
        
        // Generate new token and session
        String accessToken = tokenProvider.generateToken(user.getId(), user.getUsername());
        String sessionId = UUID.randomUUID().toString();

        // Save new session
        sessionStore.saveSession(userIdStr, sessionId, GameConstants.SESSION_TIMEOUT_SECONDS);
        log.info("New session created for user {} - sessionId: {}", userIdStr, sessionId);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .sessionId(sessionId)
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .build();
    }

    public AuthResponse autoLogin(AutoLoginRequest request) {
        // Validate token
        if (!tokenProvider.validateToken(request.getAccessToken())) {
            throw new BusinessException(ErrorCodes.AUTH_TOKEN_INVALID,
                    "Invalid or expired token");
        }

        Long userId = tokenProvider.getUserIdFromToken(request.getAccessToken());
        String userIdStr = String.valueOf(userId);
        String currentSessionId = sessionStore.getSessionId(userIdStr);
        boolean hasForceLogout = sessionStore.isForceLogout(userIdStr);
        
        log.info("Auto-login attempt for user {} - currentSessionId: {}, requestedSessionId: {}, hasForceLogout: {}", 
                userIdStr, currentSessionId, request.getSessionId(), hasForceLogout);

        // IMPORTANT: Check if there's an active session FIRST
        // If there's an active session, it means another device (A) is currently logged in
        // We need to block B even if sessionId matches (B might be using old sessionId from PlayerPrefs)
        if (currentSessionId != null && !hasForceLogout) {
            // Check if requested sessionId matches current sessionId
            // If they match, it means B is trying to use the same sessionId as A (should not happen, but handle it)
            // If they don't match, it means B is trying to use an old sessionId
            // In both cases, we should block B and set force logout for A
            if (!currentSessionId.equals(request.getSessionId())) {
                // Different sessionId - B is using old sessionId, A has new sessionId
                sessionStore.setForceLogout(userIdStr, true);
                log.warn("BLOCKING auto-login for user {} - Active session exists with different sessionId (currentSessionId: {}, requestedSessionId: {}). Force logout set for active session.", 
                        userIdStr, currentSessionId, request.getSessionId());
                
                // Block user B from auto-logging in - throw exception
                throw new BusinessException(ErrorCodes.AUTH_FORCE_LOGOUT,
                        "Tài khoản đã đăng nhập ở nơi khác. Vui lòng thử lại.");
            } else {
                // Same sessionId - This should not happen in normal flow, but handle it
                // It means B is trying to use the same sessionId as A (possible if they share PlayerPrefs or B has stale data)
                // We should still block B to prevent multiple devices using the same session
                sessionStore.setForceLogout(userIdStr, true);
                log.warn("BLOCKING auto-login for user {} - Active session exists with same sessionId (sessionId: {}). This should not happen. Force logout set for active session.", 
                        userIdStr, currentSessionId);
                
                // Block user B from auto-logging in - throw exception
                throw new BusinessException(ErrorCodes.AUTH_FORCE_LOGOUT,
                        "Tài khoản đã đăng nhập ở nơi khác. Vui lòng thử lại.");
            }
        }

        // No active session - check if requested sessionId is valid
        // If currentSessionId is null, it means session expired or was cleared
        if (currentSessionId == null) {
            throw new BusinessException(ErrorCodes.AUTH_SESSION_EXPIRED,
                    "Session expired or invalid");
        }
        
        // At this point, currentSessionId should equal request.getSessionId() and no force logout flag
        // But double-check to be safe
        if (!currentSessionId.equals(request.getSessionId())) {
            throw new BusinessException(ErrorCodes.AUTH_SESSION_EXPIRED,
                    "Session expired or invalid");
        }

        // Check force logout flag
        if (sessionStore.isForceLogout(userIdStr)) {
            throw new BusinessException(ErrorCodes.AUTH_FORCE_LOGOUT,
                    "Tài khoản đã đăng nhập ở nơi khác. Vui lòng đăng nhập lại.");
        }
        
        // Refresh session TTL to keep it alive
        sessionStore.saveSession(userIdStr, currentSessionId, GameConstants.SESSION_TIMEOUT_SECONDS);
        log.info("Auto-login successful for user {} - session TTL refreshed (sessionId: {})", userIdStr, currentSessionId);
        
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.AUTH_INVALID_CREDENTIALS,
                        "User not found"));

        return AuthResponse.builder()
                .accessToken(request.getAccessToken())
                .sessionId(request.getSessionId())
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .build();
    }

    /**
     * Logout - Clear session and force logout flag
     */
    @Transactional
    public void logout(Long userId) {
        String userIdStr = String.valueOf(userId);
        // Clear session and force logout flag
        sessionStore.deleteSession(userIdStr);
        sessionStore.deleteForceLogout(userIdStr);
        log.info("User {} logged out - session and force logout flag cleared", userIdStr);
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.AUTH_INVALID_CREDENTIALS,
                        "User not found"));

        // Check old password
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCodes.AUTH_OLD_PASSWORD_INCORRECT,
                    "Old password is incorrect");
        }

        // Check new password match
        if (!request.getNewPassword().equals(request.getConfirmNewPassword())) {
            throw new BusinessException(ErrorCodes.AUTH_PASSWORD_MISMATCH,
                    "New password and confirm password do not match");
        }

        // Update password
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        // Invalidate all sessions
        sessionService.invalidateAllUserSessions(userId);
    }
}

