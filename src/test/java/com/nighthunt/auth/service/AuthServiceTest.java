package com.nighthunt.auth.service;

import com.nighthunt.admin.service.UserActivityService;
import com.nighthunt.auth.dto.*;
import com.nighthunt.auth.repository.RefreshTokenRepository;
import com.nighthunt.ban.service.BanService;
import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.messaging.service.MessageBrokerService;
import com.nighthunt.security.port.TokenProvider;
import com.nighthunt.session.port.SessionStore;
import com.nighthunt.session.service.SessionService;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuthService}.
 *
 * All dependencies are mocked — no Spring context or DB needed.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository            userRepository;
    @Mock PasswordEncoder           passwordEncoder;
    @Mock TokenProvider             tokenProvider;
    @Mock SessionStore              sessionStore;
    @Mock SessionService            sessionService;
    @Mock BanService                banService;
    @Mock ConnectionManager         connectionManager;
    @Mock MessageBrokerService      messageBroker;
    @Mock UserActivityService       activityService;
    @Mock RefreshTokenRepository    refreshTokenRepository;

    @InjectMocks AuthService authService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RegisterRequest registerRequest(String username, String email,
                                            String password, String confirm) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(username);
        r.setEmail(email);
        r.setPassword(password);
        r.setConfirmPassword(confirm);
        return r;
    }

    private LoginRequest loginRequest(String identifier, String password) {
        LoginRequest r = new LoginRequest();
        r.setIdentifier(identifier);
        r.setPassword(password);
        return r;
    }

    private User fakeUser(Long id, String username, String email, String hash) {
        return User.builder()
                .id(id)
                .username(username)
                .email(email)
                .passwordHash(hash)
                .elo(1000)
                .tier("BRONZE")
                .totalWins(0).totalLosses(0).totalDraws(0)
                .build();
    }

    // ── register ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("register: success → returns AuthResponse without token/session")
    void register_success_returnsResponseWithoutToken() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Pass123!")).thenReturn("hashed");
        User saved = fakeUser(1L, "alice", "alice@example.com", "hashed");
        when(userRepository.save(any(User.class))).thenReturn(saved);

        AuthResponse resp = authService.register(
                registerRequest("alice", "alice@example.com", "Pass123!", "Pass123!"));

        assertThat(resp.getAccessToken()).isNull();
        assertThat(resp.getSessionId()).isNull();
        assertThat(resp.getUsername()).isEqualTo("alice");
        assertThat(resp.getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    @DisplayName("register: duplicate username → BusinessException AUTH_001")
    void register_duplicateUsername_throws() {
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                registerRequest("alice", "alice@example.com", "Pass123!", "Pass123!")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCodes.AUTH_USERNAME_EXISTS);
    }

    @Test
    @DisplayName("register: duplicate email → BusinessException AUTH_002")
    void register_duplicateEmail_throws() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                registerRequest("alice", "alice@example.com", "Pass123!", "Pass123!")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCodes.AUTH_EMAIL_EXISTS);
    }

    @Test
    @DisplayName("register: password mismatch → BusinessException AUTH_003")
    void register_passwordMismatch_throws() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);

        assertThatThrownBy(() -> authService.register(
                registerRequest("alice", "alice@example.com", "Pass123!", "Wrong!")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCodes.AUTH_PASSWORD_MISMATCH);
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("login: success → returns token + sessionId")
    void login_success_returnsTokenAndSession() {
        User user = fakeUser(1L, "alice", "alice@example.com", "hashed");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Pass123!", "hashed")).thenReturn(true);
        when(sessionStore.getSessionId("1")).thenReturn(null);
        when(sessionStore.isForceLogout("1")).thenReturn(false);
        when(tokenProvider.generateToken(1L, "alice")).thenReturn("jwt-token");

        AuthResponse resp = authService.login(loginRequest("alice", "Pass123!"));

        assertThat(resp.getAccessToken()).isEqualTo("jwt-token");
        assertThat(resp.getSessionId()).isNotBlank();
        verify(sessionStore).saveSession(eq("1"), anyString(), anyInt());
    }

    @Test
    @DisplayName("login: wrong password → BusinessException AUTH_004")
    void login_wrongPassword_throws() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("alice")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(loginRequest("alice", "wrong")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCodes.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("login: wrong password with existing user → records failed attempt")
    void login_wrongPassword_recordsFailedAttempt() {
        User user = fakeUser(1L, "alice", "alice@example.com", "hashed");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginRequest("alice", "wrong")))
                .isInstanceOf(BusinessException.class);

        verify(banService).recordFailedLoginAttempt(eq("alice"), anyString(), any());
    }

    @Test
    @DisplayName("login: active session exists (no force-logout) → blocks with AUTH_008")
    void login_activeSessionExists_blocksAndThrows() {
        User user = fakeUser(1L, "alice", "alice@example.com", "hashed");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Pass123!", "hashed")).thenReturn(true);
        when(sessionStore.getSessionId("1")).thenReturn("existing-session-id");
        when(sessionStore.isForceLogout("1")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginRequest("alice", "Pass123!")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCodes.AUTH_FORCE_LOGOUT);

        // Force logout flag should have been set for the active session
        verify(sessionStore).setForceLogout("1", true);
    }

    @Test
    @DisplayName("login: force-logout flag set → allows new login and clears stale session")
    void login_withForceLogoutFlag_allowsNewLogin() {
        User user = fakeUser(1L, "alice", "alice@example.com", "hashed");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Pass123!", "hashed")).thenReturn(true);
        when(sessionStore.getSessionId("1")).thenReturn("stale-session");
        when(sessionStore.isForceLogout("1")).thenReturn(true); // force-logout was set
        when(tokenProvider.generateToken(1L, "alice")).thenReturn("new-token");

        AuthResponse resp = authService.login(loginRequest("alice", "Pass123!"));

        assertThat(resp.getAccessToken()).isEqualTo("new-token");
        verify(sessionStore).deleteForceLogout("1");
        verify(sessionStore).deleteSession("1");
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("logout: clears session + force-logout flag and publishes event")
    void logout_clearsSessionAndPublishesEvent() {
        authService.logout(1L);

        verify(sessionStore).deleteSession("1");
        verify(sessionStore).deleteForceLogout("1");
        verify(messageBroker).publishUserLogout(1L);
    }

    // ── changePassword ────────────────────────────────────────────────────────

    @Test
    @DisplayName("changePassword: success → encodes new password and invalidates sessions")
    void changePassword_success() {
        User user = fakeUser(1L, "alice", "alice@example.com", "old-hash");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass!", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("NewPass!")).thenReturn("new-hash");

        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("OldPass!");
        req.setNewPassword("NewPass!");
        req.setConfirmNewPassword("NewPass!");

        authService.changePassword(1L, req);

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(userRepository).save(user);
        verify(sessionService).invalidateAllUserSessions(1L);
    }

    @Test
    @DisplayName("changePassword: wrong old password → BusinessException AUTH_009")
    void changePassword_wrongOldPassword_throws() {
        User user = fakeUser(1L, "alice", "alice@example.com", "old-hash");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Wrong!", "old-hash")).thenReturn(false);

        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("Wrong!");
        req.setNewPassword("NewPass!");
        req.setConfirmNewPassword("NewPass!");

        assertThatThrownBy(() -> authService.changePassword(1L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCodes.AUTH_OLD_PASSWORD_INCORRECT);
    }

    @Test
    @DisplayName("changePassword: new password mismatch → BusinessException AUTH_003")
    void changePassword_newPasswordMismatch_throws() {
        User user = fakeUser(1L, "alice", "alice@example.com", "old-hash");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass!", "old-hash")).thenReturn(true);

        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("OldPass!");
        req.setNewPassword("NewPass!");
        req.setConfirmNewPassword("Different!");

        assertThatThrownBy(() -> authService.changePassword(1L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCodes.AUTH_PASSWORD_MISMATCH);
    }
}
