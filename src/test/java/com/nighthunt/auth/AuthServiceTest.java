package com.nighthunt.auth;

import com.nighthunt.admin.service.UserActivityService;
import com.nighthunt.auth.dto.*;
import com.nighthunt.auth.entity.RefreshToken;
import com.nighthunt.auth.repository.RefreshTokenRepository;
import com.nighthunt.auth.service.AuthService;
import com.nighthunt.ban.service.BanService;
import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.friend.service.PlayerStatusService;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.messaging.service.MessageBrokerService;
import com.nighthunt.security.port.TokenProvider;
import com.nighthunt.session.port.SessionStore;
import com.nighthunt.session.service.SessionService;
import com.nighthunt.user.entity.User;
import com.nighthunt.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthService — covers register, login, refresh token,
 * logout, and change-password flows.
 *
 * All external dependencies are mocked (no Spring context, no DB).
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository         userRepository;
    @Mock private PasswordEncoder        passwordEncoder;
    @Mock private TokenProvider          tokenProvider;
    @Mock private SessionStore           sessionStore;
    @Mock private SessionService         sessionService;
    @Mock private BanService             banService;
    @Mock private ConnectionManager      connectionManager;
    @Mock private MessageBrokerService   messageBroker;
    @Mock private UserActivityService    activityService;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PlayerStatusService    playerStatusService;

    @InjectMocks
    private AuthService authService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "refreshTokenExpiryDays", 30);
    }

    // ── register ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("register")
    class RegisterTests {

        @Test
        @DisplayName("Throws BusinessException when username already exists")
        void register_duplicateUsername() {
            RegisterRequest req = registerRequest("existing", "e@e.com", "pass", "pass");
            when(userRepository.existsByUsername("existing")).thenReturn(true);

            assertThatThrownBy(() -> authService.register(req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Username already exists");
        }

        @Test
        @DisplayName("Throws BusinessException when email already exists")
        void register_duplicateEmail() {
            RegisterRequest req = registerRequest("newuser", "taken@e.com", "pass", "pass");
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            when(userRepository.existsByEmail("taken@e.com")).thenReturn(true);

            assertThatThrownBy(() -> authService.register(req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Email already exists");
        }

        @Test
        @DisplayName("Throws BusinessException when passwords do not match")
        void register_passwordMismatch() {
            RegisterRequest req = registerRequest("newuser", "e@e.com", "pass1", "pass2");
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            when(userRepository.existsByEmail("e@e.com")).thenReturn(false);

            assertThatThrownBy(() -> authService.register(req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("do not match");
        }

        @Test
        @DisplayName("Returns AuthResponse with userId and username on success")
        void register_success() {
            RegisterRequest req = registerRequest("alice", "alice@e.com", "secret", "secret");
            when(userRepository.existsByUsername("alice")).thenReturn(false);
            when(userRepository.existsByEmail("alice@e.com")).thenReturn(false);
            when(passwordEncoder.encode("secret")).thenReturn("hashed");
            User saved = User.builder().id(1L).username("alice").email("alice@e.com")
                    .passwordHash("hashed").build();
            when(userRepository.save(any())).thenReturn(saved);
            doNothing().when(activityService).log(anyLong(), anyString(), anyString(), any());

            AuthResponse resp = authService.register(req);

            assertThat(resp.getUserId()).isEqualTo(1L);
            assertThat(resp.getUsername()).isEqualTo("alice");
            assertThat(resp.getAccessToken()).isNull();   // No token on register
            assertThat(resp.getRefreshToken()).isNull();
        }
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("login")
    class LoginTests {

        @Test
        @DisplayName("Throws BusinessException for unknown user")
        void login_unknownUser() {
            LoginRequest req = loginRequest("ghost", "pw");
            // Ban checks pass
            doNothing().when(banService).checkIpBan(any());
            doNothing().when(banService).recordConcurrentLoginAttempt(any(), any());
            when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
            when(userRepository.findByEmail("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Invalid");
        }

        @Test
        @DisplayName("Throws BusinessException for wrong password")
        void login_wrongPassword() {
            LoginRequest req = loginRequest("bob", "wrong");
            doNothing().when(banService).checkIpBan(any());
            doNothing().when(banService).recordConcurrentLoginAttempt(any(), any());
            User bob = User.builder().id(2L).username("bob").passwordHash("correct-hash").build();
            when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));
            when(passwordEncoder.matches("wrong", "correct-hash")).thenReturn(false);

            assertThatThrownBy(() -> authService.login(req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Invalid");

            verify(banService).recordFailedLoginAttempt(eq("bob"), any(), any());
        }

        @Test
        @DisplayName("Returns full AuthResponse with tokens on successful login")
        void login_success() {
            LoginRequest req = loginRequest("carol", "pw");
            doNothing().when(banService).checkIpBan(any());
            doNothing().when(banService).recordConcurrentLoginAttempt(any(), any());
            User carol = User.builder().id(3L).username("carol").email("carol@e.com")
                    .passwordHash("hashed").build();
            when(userRepository.findByUsername("carol")).thenReturn(Optional.of(carol));
            when(passwordEncoder.matches("pw", "hashed")).thenReturn(true);
            doNothing().when(banService).checkUserBan(3L);
            doNothing().when(banService).clearFailedLoginAttempts(any(), any());
            when(sessionStore.getSessionId("3")).thenReturn(null);
            doNothing().when(sessionStore).deleteForceLogout(any());
            doNothing().when(sessionStore).deleteSession(any());
            when(tokenProvider.generateToken(3L, "carol")).thenReturn("jwt-token");
            doNothing().when(sessionStore).saveSession(any(), any(), anyInt());
            when(refreshTokenRepository.revokeAllByUserId(3L)).thenReturn(0);
            RefreshToken rt = RefreshToken.builder().userId(3L).token("rt-token")
                    .expiryDate(LocalDateTime.now().plusDays(30)).revoked(false).build();
            when(refreshTokenRepository.save(any())).thenReturn(rt);
            doNothing().when(activityService).log(anyLong(), anyString(), anyString(), any());
            doNothing().when(playerStatusService).setOnline(3L);

            AuthResponse resp = authService.login(req);

            assertThat(resp.getAccessToken()).isEqualTo("jwt-token");
            assertThat(resp.getRefreshToken()).isNotBlank();
            assertThat(resp.getSessionId()).isNotBlank();
            assertThat(resp.getUserId()).isEqualTo(3L);
            assertThat(resp.getUsername()).isEqualTo("carol");
        }

        @Test
        @DisplayName("Login cleans up orphaned Redis session (no active WS)")
        void login_cleansOrphanedSession() {
            LoginRequest req = loginRequest("dave", "pw");
            doNothing().when(banService).checkIpBan(any());
            doNothing().when(banService).recordConcurrentLoginAttempt(any(), any());
            User dave = User.builder().id(4L).username("dave").email("dave@e.com")
                    .passwordHash("hashed").build();
            when(userRepository.findByUsername("dave")).thenReturn(Optional.of(dave));
            when(passwordEncoder.matches("pw", "hashed")).thenReturn(true);
            doNothing().when(banService).checkUserBan(4L);
            doNothing().when(banService).clearFailedLoginAttempts(any(), any());
            // Existing orphaned session
            when(sessionStore.getSessionId("4")).thenReturn("old-session-id");
            when(sessionStore.isForceLogout("4")).thenReturn(false);
            when(connectionManager.isUserConnected(4L)).thenReturn(false); // No live WS
            doNothing().when(sessionStore).deleteSession(any());
            doNothing().when(sessionStore).deleteForceLogout(any());
            when(tokenProvider.generateToken(4L, "dave")).thenReturn("new-jwt");
            doNothing().when(sessionStore).saveSession(any(), any(), anyInt());
            when(refreshTokenRepository.revokeAllByUserId(4L)).thenReturn(0);
            RefreshToken rt = RefreshToken.builder().userId(4L).token("rt")
                    .expiryDate(LocalDateTime.now().plusDays(30)).revoked(false).build();
            when(refreshTokenRepository.save(any())).thenReturn(rt);
            doNothing().when(activityService).log(anyLong(), anyString(), anyString(), any());
            doNothing().when(playerStatusService).setOnline(4L);

            AuthResponse resp = authService.login(req);

            assertThat(resp.getAccessToken()).isEqualTo("new-jwt");
            verify(sessionStore, atLeastOnce()).deleteSession("4");
        }
    }

    // ── refresh token ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("refreshToken")
    class RefreshTokenTests {

        @Test
        @DisplayName("Throws BusinessException for unknown refresh token")
        void refresh_unknownToken() {
            RefreshTokenRequest req = refreshTokenRequest("bad-token");
            when(refreshTokenRepository.findByToken("bad-token")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.refreshToken(req))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("Throws BusinessException for revoked refresh token")
        void refresh_revokedToken() {
            RefreshToken rt = RefreshToken.builder()
                    .token("revoked-token").userId(5L)
                    .expiryDate(LocalDateTime.now().plusDays(10))
                    .revoked(true).build();
            when(refreshTokenRepository.findByToken("revoked-token")).thenReturn(Optional.of(rt));

            assertThatThrownBy(() -> authService.refreshToken(refreshTokenRequest("revoked-token")))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("Throws BusinessException for expired refresh token")
        void refresh_expiredToken() {
            RefreshToken rt = RefreshToken.builder()
                    .token("expired-token").userId(6L)
                    .expiryDate(LocalDateTime.now().minusDays(1))
                    .revoked(false).build();
            when(refreshTokenRepository.findByToken("expired-token")).thenReturn(Optional.of(rt));

            assertThatThrownBy(() -> authService.refreshToken(refreshTokenRequest("expired-token")))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("Issues new access + refresh token and revokes old one")
        void refresh_success() {
            RefreshToken old = RefreshToken.builder()
                    .token("valid-rt").userId(7L)
                    .expiryDate(LocalDateTime.now().plusDays(20))
                    .revoked(false).build();
            when(refreshTokenRepository.findByToken("valid-rt")).thenReturn(Optional.of(old));
            User user = User.builder().id(7L).username("eve").email("eve@e.com").build();
            when(userRepository.findById(7L)).thenReturn(Optional.of(user));
            doNothing().when(banService).checkUserBan(7L);
            when(tokenProvider.generateToken(7L, "eve")).thenReturn("new-access-token");
            when(sessionStore.getSessionId("7")).thenReturn("existing-sid");
            doNothing().when(sessionStore).deleteForceLogout(any());
            doNothing().when(sessionStore).saveSession(any(), any(), anyInt());
            // save called twice: once to revoke old, once to persist new refresh token
            RefreshToken newRt = RefreshToken.builder().userId(7L).token("new-rt")
                    .expiryDate(LocalDateTime.now().plusDays(30)).revoked(false).build();
            when(refreshTokenRepository.save(any())).thenReturn(newRt);

            AuthResponse resp = authService.refreshToken(refreshTokenRequest("valid-rt"));

            assertThat(resp.getAccessToken()).isEqualTo("new-access-token");
            assertThat(old.isRevoked()).isTrue();  // Old token must be revoked
        }
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("logout")
    class LogoutTests {

        @Test
        @DisplayName("Clears session, revokes refresh tokens, sets player offline")
        void logout_clearsSessionAndRevokesTokens() {
            doNothing().when(sessionStore).deleteSession("8");
            doNothing().when(sessionStore).deleteForceLogout("8");
            when(refreshTokenRepository.revokeAllByUserId(8L)).thenReturn(2);
            doNothing().when(playerStatusService).setOffline(8L);
            doNothing().when(messageBroker).publishUserLogout(8L);
            doNothing().when(activityService).log(anyLong(), any(), anyString(), any());

            authService.logout(8L);

            verify(sessionStore).deleteSession("8");
            verify(sessionStore).deleteForceLogout("8");
            verify(refreshTokenRepository).revokeAllByUserId(8L);
            verify(playerStatusService).setOffline(8L);
        }
    }

    // ── changePassword ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("changePassword")
    class ChangePasswordTests {

        @Test
        @DisplayName("Throws when old password is incorrect")
        void changePassword_wrongOldPassword() {
            User user = User.builder().id(9L).username("frank").passwordHash("old-hash").build();
            when(userRepository.findById(9L)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrong-old", "old-hash")).thenReturn(false);

            ChangePasswordRequest req = changePasswordRequest("wrong-old", "new-pass", "new-pass");
            assertThatThrownBy(() -> authService.changePassword(9L, req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Old password is incorrect");
        }

        @Test
        @DisplayName("Throws when new passwords do not match")
        void changePassword_mismatch() {
            User user = User.builder().id(10L).username("gina").passwordHash("old-hash").build();
            when(userRepository.findById(10L)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("old-pass", "old-hash")).thenReturn(true);

            ChangePasswordRequest req = changePasswordRequest("old-pass", "new1", "new2");
            assertThatThrownBy(() -> authService.changePassword(10L, req))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("do not match");
        }

        @Test
        @DisplayName("Saves new password hash and invalidates all sessions")
        void changePassword_success() {
            User user = User.builder().id(11L).username("hank").email("h@e.com")
                    .passwordHash("old-hash").build();
            when(userRepository.findById(11L)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("old-pass", "old-hash")).thenReturn(true);
            when(passwordEncoder.encode("new-pass")).thenReturn("new-hash");
            when(userRepository.save(any())).thenReturn(user);
            doNothing().when(sessionService).invalidateAllUserSessions(11L);
            when(refreshTokenRepository.revokeAllByUserId(11L)).thenReturn(1);
            doNothing().when(activityService).log(anyLong(), anyString(), anyString(), any());

            authService.changePassword(11L, changePasswordRequest("old-pass", "new-pass", "new-pass"));

            assertThat(user.getPasswordHash()).isEqualTo("new-hash");
            verify(sessionService).invalidateAllUserSessions(11L);
            verify(refreshTokenRepository).revokeAllByUserId(11L);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static RegisterRequest registerRequest(String username, String email,
                                                   String password, String confirm) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(username);
        r.setEmail(email);
        r.setPassword(password);
        r.setConfirmPassword(confirm);
        return r;
    }

    private static LoginRequest loginRequest(String identifier, String password) {
        LoginRequest r = new LoginRequest();
        r.setIdentifier(identifier);
        r.setPassword(password);
        return r;
    }

    private static RefreshTokenRequest refreshTokenRequest(String token) {
        RefreshTokenRequest r = new RefreshTokenRequest();
        r.setRefreshToken(token);
        return r;
    }

    private static ChangePasswordRequest changePasswordRequest(String oldPw, String newPw,
                                                                String confirm) {
        ChangePasswordRequest r = new ChangePasswordRequest();
        r.setOldPassword(oldPw);
        r.setNewPassword(newPw);
        r.setConfirmNewPassword(confirm);
        return r;
    }
}