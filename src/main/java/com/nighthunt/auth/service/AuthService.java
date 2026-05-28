package com.nighthunt.auth.service;

import com.nighthunt.auth.dto.*;
import com.nighthunt.auth.entity.RefreshToken;
import com.nighthunt.auth.repository.RefreshTokenRepository;
import com.nighthunt.admin.entity.UserActivityLog;
import com.nighthunt.admin.service.UserActivityService;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.messaging.service.MessageBrokerService;
import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.ban.service.BanService;
import com.nighthunt.friend.service.PlayerStatusService;
import com.nighthunt.match.service.MatchPresenceService;
import com.nighthunt.security.port.TokenProvider;
import com.nighthunt.session.port.SessionStore;
import com.nighthunt.session.service.SessionService;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    // ── Config ─────────────────────────────────────────────────────────────────
    /** Days before a refresh token expires. Override via application.properties. */
    @Value("${auth.refresh-token.expiry-days:30}")
    private int refreshTokenExpiryDays;

    // ── Dependencies ────────────────────────────────────────────────────────────
    private final UserRepository            userRepository;
    private final PasswordEncoder           passwordEncoder;
    private final TokenProvider             tokenProvider;
    private final SessionStore              sessionStore;
    private final SessionService            sessionService;
    private final BanService                banService;
    private final ConnectionManager         connectionManager;
    private final MessageBrokerService      messageBroker;
    private final UserActivityService       activityService;
    private final RefreshTokenRepository    refreshTokenRepository;
    private final PlayerStatusService       playerStatusService;
    private final MatchPresenceService      matchPresenceService;

    // ═══════════════════════════════════════════════════════════════════════════
    // REGISTER
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException(ErrorCodes.AUTH_USERNAME_EXISTS, "Username already exists");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException(ErrorCodes.AUTH_EMAIL_EXISTS, "Email already exists");
        }
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException(ErrorCodes.AUTH_PASSWORD_MISMATCH, "Password and confirm password do not match");
        }

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .build();
        user = userRepository.save(user);

        activityService.log(user.getId(), user.getUsername(), UserActivityLog.REGISTER, null);

        // NOTE: No token/session issued on registration — user must login explicitly.
        return AuthResponse.builder()
                .accessToken(null)
                .refreshToken(null)
                .sessionId(null)
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .selectedCharacterId(null)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOGIN
    // ═══════════════════════════════════════════════════════════════════════════

    @Retryable(
            retryFor = {CannotAcquireLockException.class, DeadlockLoserDataAccessException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2))
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public AuthResponse login(LoginRequest request) {
        String ipAddress        = getClientIpAddress();
        String deviceFingerprint = request.getDeviceFingerprint();

        banService.checkIpBan(ipAddress);
        if (deviceFingerprint != null && !deviceFingerprint.isEmpty()) {
            banService.checkDeviceBan(deviceFingerprint);
        }
        banService.recordConcurrentLoginAttempt(ipAddress, deviceFingerprint);

        User user = userRepository.findByUsername(request.getIdentifier())
                .or(() -> userRepository.findByEmail(request.getIdentifier()))
                .orElse(null);

        boolean passwordValid = user != null && passwordEncoder.matches(request.getPassword(), user.getPasswordHash());

        if (!passwordValid) {
            banService.recordFailedLoginAttempt(request.getIdentifier(), ipAddress, deviceFingerprint);
            throw new BusinessException(ErrorCodes.AUTH_INVALID_CREDENTIALS, "Invalid username/email or password");
        }

        banService.checkUserBan(user.getId());
        banService.clearFailedLoginAttempts(request.getIdentifier(), ipAddress);

        String userIdStr = String.valueOf(user.getId());

        // ── Single-session enforcement ─────────────────────────────────────────
        String existingSessionId  = sessionStore.getSessionId(userIdStr);
        boolean hasForceLogout    = sessionStore.isForceLogout(userIdStr);

        if (existingSessionId != null && !hasForceLogout) {
            boolean wsActive = connectionManager.isUserConnected(user.getId());
            if (!wsActive) {
                // Orphaned session: client closed/crashed without logout.
                // No live WS connection — safe to clean up and proceed immediately.
                sessionStore.deleteSession(userIdStr);
                sessionStore.deleteForceLogout(userIdStr);
                log.info("Cleaned up orphaned session for userId={} (no active WS). Proceeding with login.", userIdStr);
            } else {
                // Live session detected. Kick the old client immediately and proceed
                // with this login in a single step (no second attempt required).
                // This avoids the disruptive double-login UX during development rebuilds
                // while still enforcing single-device policy.
                try {
                    recordSessionTerminated(user.getId(), "FORCE_LOGOUT");
                    connectionManager.sendToUser(user.getId(), "force_logout", java.util.Map.of(
                            "reason", "Account logged in from another location",
                            "message", "Your session has been taken over by a new login."));
                } catch (Exception e) {
                    log.warn("Could not notify old WS for userId={}: {}", userIdStr, e.getMessage());
                }
                sessionStore.deleteSession(userIdStr);
                sessionStore.deleteForceLogout(userIdStr);
                log.info("Kicked live session for userId={} — proceeding with new login (single-device policy)", userIdStr);
                // Fall through to token generation below
            }
        }

        // Always clean up any leftover force-logout flag and stale session before creating new one
        sessionStore.deleteForceLogout(userIdStr);
        sessionStore.deleteSession(userIdStr);

        // ── Tokens + session ───────────────────────────────────────────────────
        String accessToken  = tokenProvider.generateToken(user.getId(), user.getUsername());
        String sessionId    = UUID.randomUUID().toString();
        sessionStore.saveSession(userIdStr, sessionId, GameConstants.SESSION_TIMEOUT_SECONDS);

        // Revoke old refresh tokens, issue a fresh one
        refreshTokenRepository.revokeAllByUserId(user.getId());
        String rawRefreshToken = createRefreshToken(user.getId());

        activityService.log(user.getId(), user.getUsername(), UserActivityLog.LOGIN, "sessionId=" + sessionId);
        log.info("Login OK  userId={} sessionId={}", userIdStr, sessionId);

        // ── Set player status to ONLINE and broadcast to friends ───────────────
        try {
            playerStatusService.setOnline(user.getId());
        } catch (Exception e) {
            log.error("Error setting player online status for userId={}: {}", user.getId(), e.getMessage());
        }

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken)
                .sessionId(sessionId)
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .selectedCharacterId(user.getSelectedCharacterId())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REFRESH TOKEN
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Exchanges a valid refresh token for a new access token + rotated refresh token.
     *
     * <p>Strategy: <b>Rotate on use</b>.
     * The incoming token is revoked and a new one is issued. This limits the
     * impact of a stolen refresh token to a single use window.
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        RefreshToken rt = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new BusinessException(
                        ErrorCodes.AUTH_REFRESH_TOKEN_INVALID, "Refresh token not found"));

        if (!rt.isValid()) {
            throw new BusinessException(ErrorCodes.AUTH_REFRESH_TOKEN_INVALID,
                    "Refresh token has expired or been revoked");
        }

        User user = userRepository.findById(rt.getUserId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCodes.AUTH_INVALID_CREDENTIALS, "User not found"));

        // Ban check still applies on token refresh
        banService.checkUserBan(user.getId());

        // ── Rotate: revoke old, issue new ─────────────────────────────────────
        rt.setRevoked(true);
        refreshTokenRepository.save(rt);

        String newAccessToken   = tokenProvider.generateToken(user.getId(), user.getUsername());
        String newRawRefresh    = createRefreshToken(user.getId());

        // Re-create / extend session in Redis so filter doesn't reject next call.
        // Always clear any stale force_logout flag — a leftover flag from a previous
        // session kick would block the new WS connection after token refresh.
        String userIdStr   = String.valueOf(user.getId());
        sessionStore.deleteForceLogout(userIdStr);
        String sessionId   = sessionStore.getSessionId(userIdStr);
        if (sessionId == null) {
            // Session expired between calls – mint a new one
            sessionId = UUID.randomUUID().toString();
        }
        sessionStore.saveSession(userIdStr, sessionId, GameConstants.SESSION_TIMEOUT_SECONDS);

        log.info("Token refreshed for userId={}", user.getId());

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRawRefresh)
                .sessionId(sessionId)
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .selectedCharacterId(user.getSelectedCharacterId())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AUTO-LOGIN  (legacy: still used by old clients that persist accessToken)
    //   New clients should use /auth/refresh-token instead.
    // ═══════════════════════════════════════════════════════════════════════════

    public AuthResponse autoLogin(AutoLoginRequest request) {
        String ipAddress = getClientIpAddress();
        String deviceFingerprint = request.getDeviceFingerprint();

        banService.checkIpBan(ipAddress);
        if (deviceFingerprint != null && !deviceFingerprint.isEmpty()) {
            banService.checkDeviceBan(deviceFingerprint);
        }

        if (!tokenProvider.validateToken(request.getAccessToken())) {
            throw new BusinessException(ErrorCodes.AUTH_TOKEN_INVALID, "Invalid or expired token");
        }

        Long userId = tokenProvider.getUserIdFromToken(request.getAccessToken());
        banService.checkUserBan(userId);

        String userIdStr        = String.valueOf(userId);
        String currentSessionId = sessionStore.getSessionId(userIdStr);
        boolean hasForceLogout  = sessionStore.isForceLogout(userIdStr);

        log.info("Auto-login userId={} currentSession={} requestedSession={} forceLogout={}",
                userIdStr, currentSessionId, request.getSessionId(), hasForceLogout);

        if (currentSessionId != null && !hasForceLogout) {
            if (!currentSessionId.equals(request.getSessionId())) {
                sessionStore.setForceLogout(userIdStr, true);
                try {
                    recordSessionTerminated(userId, "FORCE_LOGOUT");
                    connectionManager.sendToUser(userId, "force_logout", java.util.Map.of(
                            "reason", "Account logged in from another location",
                            "message", "You have been logged out. Please log in again."));
                } catch (Exception e) {
                    log.error("Error sending force_logout to user {}: {}", userIdStr, e.getMessage());
                }
                throw new BusinessException(ErrorCodes.AUTH_FORCE_LOGOUT,
                        "Account is already logged in elsewhere. Please try again.");
            }
        }

        if (currentSessionId == null) {
            recordSessionTerminated(userId, "SESSION_EXPIRED");
            throw new BusinessException(ErrorCodes.AUTH_SESSION_EXPIRED,
                    "Session expired or invalid. Please login again.");
        }
        if (!currentSessionId.equals(request.getSessionId())) {
            recordSessionTerminated(userId, "SESSION_EXPIRED");
            throw new BusinessException(ErrorCodes.AUTH_SESSION_EXPIRED,
                    "Session is invalid or has expired.");
        }
        if (sessionStore.isForceLogout(userIdStr)) {
            recordSessionTerminated(userId, "FORCE_LOGOUT");
            throw new BusinessException(ErrorCodes.AUTH_FORCE_LOGOUT,
                    "Account is already logged in elsewhere. Please log in again.");
        }

        sessionStore.saveSession(userIdStr, currentSessionId, GameConstants.SESSION_TIMEOUT_SECONDS);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCodes.AUTH_INVALID_CREDENTIALS, "User not found"));

        return AuthResponse.builder()
                .accessToken(request.getAccessToken())
                .refreshToken(null) // legacy endpoint – no new refresh token issued
                .sessionId(request.getSessionId())
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .selectedCharacterId(user.getSelectedCharacterId())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOGOUT
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void logout(Long userId) {
        String userIdStr = String.valueOf(userId);
        recordSessionTerminated(userId, "LOGOUT");
        sessionStore.deleteSession(userIdStr);
        sessionStore.deleteForceLogout(userIdStr);

        // Revoke all active refresh tokens for the user
        int revoked = refreshTokenRepository.revokeAllByUserId(userId);
        log.info("Logout userId={} — {} refresh token(s) revoked", userId, revoked);

        // ── Set player status to OFFLINE and broadcast to friends ──────────────
        try {
            playerStatusService.setOffline(userId);
        } catch (Exception e) {
            log.error("Error setting player offline status for userId={}: {}", userId, e.getMessage());
        }

        messageBroker.publishUserLogout(userId);
        activityService.log(userId, null, UserActivityLog.LOGOUT, null);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CHANGE PASSWORD
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCodes.AUTH_INVALID_CREDENTIALS, "User not found"));

        if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCodes.AUTH_OLD_PASSWORD_INCORRECT, "Old password is incorrect");
        }
        if (!request.getNewPassword().equals(request.getConfirmNewPassword())) {
            throw new BusinessException(ErrorCodes.AUTH_PASSWORD_MISMATCH,
                    "New password and confirm password do not match");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        // Invalidate all sessions and all refresh tokens
        recordSessionTerminated(userId, "FORCE_LOGOUT");
        sessionService.invalidateAllUserSessions(userId);
        refreshTokenRepository.revokeAllByUserId(userId);

        activityService.log(userId, user.getUsername(), UserActivityLog.PASSWORD_CHANGE, null);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /** Creates and persists a new refresh token for the given user. Returns the raw token string. */
    private String createRefreshToken(Long userId) {
        String raw = UUID.randomUUID().toString();
        RefreshToken rt = RefreshToken.builder()
                .userId(userId)
                .token(raw)
                .expiryDate(LocalDateTime.now().plusDays(refreshTokenExpiryDays))
                .revoked(false)
                .build();
        refreshTokenRepository.save(rt);
        return raw;
    }

    private String getClientIpAddress() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                String ip = request.getHeader("X-Forwarded-For");
                if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip))
                    ip = request.getHeader("X-Real-IP");
                if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip))
                    ip = request.getRemoteAddr();
                if (ip != null && ip.contains(","))
                    ip = ip.split(",")[0].trim();
                return ip;
            }
        } catch (Exception e) {
            log.warn("Error getting client IP: {}", e.getMessage());
        }
        return "unknown";
    }

    private void recordSessionTerminated(Long userId, String reason) {
        if (userId == null) {
            return;
        }
        try {
            matchPresenceService.recordSessionTerminated(userId, reason);
        } catch (Exception e) {
            log.warn("[Auth] Failed to record match presence termination for userId={} reason={}: {}",
                    userId, reason, e.getMessage());
        }
    }
}
