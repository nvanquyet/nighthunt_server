package com.nighthunt.auth.service;

import com.nighthunt.auth.dto.*;
import com.nighthunt.game.websocket.GameWebSocketHandler;
import com.nighthunt.messaging.service.MessageBrokerService;
import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.ban.service.BanService;
import com.nighthunt.security.port.TokenProvider;
import com.nighthunt.session.port.SessionStore;
import com.nighthunt.session.service.SessionService;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
    private final BanService banService;
    private final GameWebSocketHandler gameWebSocketHandler;
    private final MessageBrokerService messageBroker;

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

        // NOTE: Do NOT create session or token during registration
        // User must explicitly login after registration to create a session
        // This prevents "concurrent login" errors when user tries to login right after registration
        
        // Return response without token/session - client should prompt user to login
        return AuthResponse.builder()
                .accessToken(null) // No token - user must login
                .sessionId(null)   // No session - user must login
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .build();
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        // Get IP address and device fingerprint
        String ipAddress = getClientIpAddress();
        String deviceFingerprint = request.getDeviceFingerprint(); // Should be added to LoginRequest
        
        // Check bans before processing
        banService.checkIpBan(ipAddress);
        if (deviceFingerprint != null && !deviceFingerprint.isEmpty()) {
            banService.checkDeviceBan(deviceFingerprint);
        }
        
        // Record concurrent login attempt
        banService.recordConcurrentLoginAttempt(ipAddress, deviceFingerprint);
        
        // Find user by username or email
        User user = userRepository.findByUsername(request.getIdentifier())
                .or(() -> userRepository.findByEmail(request.getIdentifier()))
                .orElse(null);
        
        // Check password
        boolean passwordValid = user != null && passwordEncoder.matches(request.getPassword(), user.getPasswordHash());
        
        if (!passwordValid) {
            // Record failed login attempt
            banService.recordFailedLoginAttempt(request.getIdentifier(), ipAddress, deviceFingerprint);
            throw new BusinessException(ErrorCodes.AUTH_INVALID_CREDENTIALS,
                    "Invalid username/email or password");
        }
        
        // Check user ban after successful authentication
        banService.checkUserBan(user.getId());
        
        // Clear failed login attempts on successful login
        banService.clearFailedLoginAttempts(request.getIdentifier(), ipAddress);

        String userIdStr = String.valueOf(user.getId());

        // Check if there's an active session
        String existingSessionId = sessionStore.getSessionId(userIdStr);
        boolean hasForceLogout = sessionStore.isForceLogout(userIdStr);
        
        log.info("Login attempt for user {} - existingSessionId: {}, hasForceLogout: {}", 
                userIdStr, existingSessionId, hasForceLogout);
        
        if (existingSessionId != null && !hasForceLogout) {
            // Active session exists and no force logout flag - this means another device (A) is currently logged in
            // Set force logout flag for the active session (user A will receive force_logout event via WebSocket)
            sessionStore.setForceLogout(userIdStr, true);
            log.warn("BLOCKING login for user {} - Active session exists (sessionId: {}). Force logout set for active session.", 
                    userIdStr, existingSessionId);
            
            // Send force_logout event to user A via WebSocket
            try {
                gameWebSocketHandler.sendForceLogout(user.getId(), "Tài khoản đã đăng nhập ở nơi khác");
            } catch (Exception e) {
                log.error("Error sending force_logout event to user {}: {}", userIdStr, e.getMessage());
            }
            
            // Block user B from logging in - throw exception
            // NOTE: Do NOT delete session of A here - let A receive force_logout event and logout properly
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
        // Get IP address and device fingerprint
        String ipAddress = getClientIpAddress();
        String deviceFingerprint = request.getDeviceFingerprint(); // Should be added to AutoLoginRequest
        
        // Check bans before processing
        banService.checkIpBan(ipAddress);
        if (deviceFingerprint != null && !deviceFingerprint.isEmpty()) {
            banService.checkDeviceBan(deviceFingerprint);
        }
        
        // Validate token
        if (!tokenProvider.validateToken(request.getAccessToken())) {
            throw new BusinessException(ErrorCodes.AUTH_TOKEN_INVALID,
                    "Invalid or expired token");
        }

        Long userId = tokenProvider.getUserIdFromToken(request.getAccessToken());
        
        // Check user ban
        banService.checkUserBan(userId);
        
        String userIdStr = String.valueOf(userId);
        String currentSessionId = sessionStore.getSessionId(userIdStr);
        boolean hasForceLogout = sessionStore.isForceLogout(userIdStr);
        
        log.info("Auto-login attempt for user {} - currentSessionId: {}, requestedSessionId: {}, hasForceLogout: {}", 
                userIdStr, currentSessionId, request.getSessionId(), hasForceLogout);

        // IMPORTANT: Check if there's an active session FIRST
        // If there's an active session, check if it's the same session (user closed app and reopened)
        // or a different session (another device is logged in)
        if (currentSessionId != null && !hasForceLogout) {
            // Check if requested sessionId matches current sessionId
            if (!currentSessionId.equals(request.getSessionId())) {
                // Different sessionId - Another device (B) is using a different sessionId
                // This means user A is logged in on device A, and user B (or same user on device B) is trying to auto-login
                sessionStore.setForceLogout(userIdStr, true);
                log.warn("BLOCKING auto-login for user {} - Active session exists with different sessionId (currentSessionId: {}, requestedSessionId: {}). Force logout set for active session.", 
                        userIdStr, currentSessionId, request.getSessionId());
                
                // Send force_logout event to user A via WebSocket
                try {
                    gameWebSocketHandler.sendForceLogout(userId, "Tài khoản đã đăng nhập ở nơi khác");
                } catch (Exception e) {
                    log.error("Error sending force_logout event to user {}: {}", userIdStr, e.getMessage());
                }
                
                // Block user B from auto-logging in - throw exception
                throw new BusinessException(ErrorCodes.AUTH_FORCE_LOGOUT,
                        "Tài khoản đã đăng nhập ở nơi khác. Vui lòng thử lại.");
            } else {
                // Same sessionId - This is likely the same device/user reopening the app
                // Allow auto-login to proceed (user closed app and reopened, session is still valid)
                log.info("ALLOWING auto-login for user {} - Active session exists with same sessionId (sessionId: {}). Same device reopening app.", 
                        userIdStr, currentSessionId);
                // Continue with auto-login flow below
            }
        }

        // No active session - check if requested sessionId is valid
        // If currentSessionId is null, it means session expired or was cleared
        // But if user is trying to auto-login with a sessionId, it means they had a session before
        // This could be:
        // 1. Session expired naturally (TTL expired)
        // 2. Session was cleared (user logged out, server restart, etc.)
        // 3. User closed app and session expired while app was closed
        // In all cases, we should allow auto-login to fail gracefully (session expired)
        if (currentSessionId == null) {
            // Session expired or was cleared - this is normal if user closed app for a while
            log.info("Auto-login failed for user {} - session expired (currentSessionId is null, requestedSessionId: {})", 
                    userIdStr, request.getSessionId());
            throw new BusinessException(ErrorCodes.AUTH_SESSION_EXPIRED,
                    "Session expired or invalid. Please login again.");
        }
        
        // At this point, currentSessionId should equal request.getSessionId() and no force logout flag
        // But double-check to be safe
        if (!currentSessionId.equals(request.getSessionId())) {
            // This should not happen if we reached here (we already checked above)
            // But handle it anyway
            log.warn("Auto-login failed for user {} - sessionId mismatch (currentSessionId: {}, requestedSessionId: {})", 
                    userIdStr, currentSessionId, request.getSessionId());
            throw new BusinessException(ErrorCodes.AUTH_SESSION_EXPIRED,
                    "Session expired or invalid. Please login again.");
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
        
        // Publish user logout event via Message Broker
        messageBroker.publishUserLogout(userId);
        
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
    
    /**
     * Get client IP address from request
     */
    private String getClientIpAddress() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                String ip = request.getHeader("X-Forwarded-For");
                if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                    ip = request.getHeader("X-Real-IP");
                }
                if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                    ip = request.getRemoteAddr();
                }
                // Handle multiple IPs (X-Forwarded-For can contain multiple IPs)
                if (ip != null && ip.contains(",")) {
                    ip = ip.split(",")[0].trim();
                }
                return ip;
            }
        } catch (Exception e) {
            log.warn("Error getting client IP address: {}", e.getMessage());
        }
        return "unknown";
    }
}

