package com.nighthunt.relay;

import com.nighthunt.relay.model.RelaySession;
import com.nighthunt.relay.service.RelaySessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for RelaySessionManager — covers all four relay modes and
 * session lifecycle (create, add/remove player, finish, expire, evict).
 *
 * No Spring context needed: uses ReflectionTestUtils to inject @Value fields.
 */
class RelaySessionManagerTest {

    private RelaySessionManager manager;

    @BeforeEach
    void setUp() {
        manager = new RelaySessionManager();
        // Default: no relay server URL, no static host → Mode C/D fallback
        ReflectionTestUtils.setField(manager, "ttlHours", 2);
        ReflectionTestUtils.setField(manager, "relayServerUrl", "");
        ReflectionTestUtils.setField(manager, "configuredRelayHost", "");
        ReflectionTestUtils.setField(manager, "defaultRelayPort", 7777);
    }

    // ── Mode C: Direct P2P (hostIp provided, no relay server, no static host) ─

    @Nested
    @DisplayName("Mode C — Direct P2P (host IP detected)")
    class ModeCTests {

        @Test
        @DisplayName("createSession uses detected host IP")
        void createSession_modeC_usesHostIp() {
            RelaySession session = manager.createSession(1L, "match-001", "203.0.113.42");

            assertThat(session).isNotNull();
            assertThat(session.getRelayHost()).isEqualTo("203.0.113.42");
            assertThat(session.getRelayPort()).isEqualTo(7777);
            assertThat(session.getRoomId()).isEqualTo(1L);
            assertThat(session.getMatchId()).isEqualTo("match-001");
            assertThat(session.getSessionToken()).isNotBlank();
            assertThat(session.isStarted()).isFalse();
            assertThat(session.isFinished()).isFalse();
        }

        @Test
        @DisplayName("getByRoomId returns created session")
        void getByRoomId_returnsSession() {
            manager.createSession(10L, "match-010", "1.2.3.4");

            Optional<RelaySession> found = manager.getByRoomId(10L);
            assertThat(found).isPresent();
            assertThat(found.get().getRelayHost()).isEqualTo("1.2.3.4");
        }

        @Test
        @DisplayName("getByToken returns created session")
        void getByToken_returnsSession() {
            RelaySession created = manager.createSession(20L, "match-020", "5.6.7.8");

            Optional<RelaySession> found = manager.getByToken(created.getSessionToken());
            assertThat(found).isPresent();
            assertThat(found.get().getRoomId()).isEqualTo(20L);
        }
    }

    // ── Mode D: Loopback fallback (null/blank hostIp, no relay server, no host) ─

    @Nested
    @DisplayName("Mode D — Loopback fallback")
    class ModeDTests {

        @Test
        @DisplayName("createSession falls back to 127.0.0.1 when hostIp is null")
        void createSession_modeD_nullHostIp() {
            RelaySession session = manager.createSession(2L, "match-002", null);

            assertThat(session.getRelayHost()).isEqualTo("127.0.0.1");
            assertThat(session.getRelayPort()).isEqualTo(7777);
        }

        @Test
        @DisplayName("createSession falls back to 127.0.0.1 when hostIp is blank")
        void createSession_modeD_blankHostIp() {
            RelaySession session = manager.createSession(3L, "match-003", "  ");

            assertThat(session.getRelayHost()).isEqualTo("127.0.0.1");
        }
    }

    // ── Mode B: Static relay host (configuredRelayHost set, no relay server) ──

    @Nested
    @DisplayName("Mode B — Static host (VPS IP configured)")
    class ModeBTests {

        @BeforeEach
        void setStaticHost() {
            ReflectionTestUtils.setField(manager, "configuredRelayHost", "20.2.235.140");
        }

        @Test
        @DisplayName("createSession uses configured static host")
        void createSession_modeB_usesStaticHost() {
            RelaySession session = manager.createSession(4L, "match-004", "192.168.1.1");

            assertThat(session.getRelayHost()).isEqualTo("20.2.235.140");
            assertThat(session.getRelayPort()).isEqualTo(7777);
        }
    }

    // ── Session Lifecycle ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Session Lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("Replacing old session for same room removes old entry")
        void createSession_replacesOldSession() {
            RelaySession first  = manager.createSession(5L, "match-005a", "1.1.1.1");
            RelaySession second = manager.createSession(5L, "match-005b", "2.2.2.2");

            assertThat(manager.getByToken(first.getSessionToken())).isEmpty();
            assertThat(manager.getByToken(second.getSessionToken())).isPresent();
            assertThat(manager.getByRoomId(5L).get().getMatchId()).isEqualTo("match-005b");
        }

        @Test
        @DisplayName("addPlayer returns true and stores userId")
        void addPlayer_success() {
            RelaySession s = manager.createSession(6L, "match-006", "3.3.3.3");

            boolean added = manager.addPlayer(s.getSessionToken(), 100L);

            assertThat(added).isTrue();
            assertThat(s.getConnectedPlayers()).contains(100L);
            assertThat(s.getPlayerCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("addPlayer returns false for unknown token")
        void addPlayer_unknownToken() {
            boolean added = manager.addPlayer("non-existent-token", 200L);
            assertThat(added).isFalse();
        }

        @Test
        @DisplayName("removePlayer removes userId from session")
        void removePlayer_success() {
            RelaySession s = manager.createSession(7L, "match-007", "4.4.4.4");
            manager.addPlayer(s.getSessionToken(), 300L);

            manager.removePlayer(s.getSessionToken(), 300L);

            assertThat(s.getConnectedPlayers()).doesNotContain(300L);
            assertThat(s.getPlayerCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("markStarted sets started flag")
        void markStarted_setsFlag() {
            RelaySession s = manager.createSession(8L, "match-008", "5.5.5.5");

            manager.markStarted(s.getSessionToken());

            assertThat(s.isStarted()).isTrue();
        }

        @Test
        @DisplayName("finishSession removes session from memory")
        void finishSession_removesSession() {
            RelaySession s = manager.createSession(9L, "match-009", "6.6.6.6");
            String token = s.getSessionToken();

            manager.finishSession(token);

            assertThat(manager.getByToken(token)).isEmpty();
            assertThat(manager.getByRoomId(9L)).isEmpty();
            assertThat(s.isFinished()).isTrue();
        }

        @Test
        @DisplayName("getAllActiveSessions excludes finished sessions")
        void getAllActiveSessions_excludesFinished() {
            RelaySession active   = manager.createSession(11L, "match-011", "7.7.7.7");
            RelaySession finished = manager.createSession(12L, "match-012", "8.8.8.8");
            manager.finishSession(finished.getSessionToken());

            var activeSessions = manager.getAllActiveSessions();

            assertThat(activeSessions).contains(active);
            assertThat(activeSessions).doesNotContain(finished);
        }

        @Test
        @DisplayName("getAllActiveSessions excludes expired sessions")
        void getAllActiveSessions_excludesExpired() {
            RelaySession s = manager.createSession(13L, "match-013", "9.9.9.9");
            // Force expiry by setting expiresAt in the past
            ReflectionTestUtils.setField(s, "expiresAt", Instant.now().minusSeconds(1));

            var activeSessions = manager.getAllActiveSessions();

            assertThat(activeSessions).doesNotContain(s);
        }

        @Test
        @DisplayName("evictExpiredSessions removes only expired entries")
        void evictExpiredSessions_removesExpiredOnly() {
            RelaySession valid   = manager.createSession(14L, "match-014", "10.0.0.1");
            RelaySession expired = manager.createSession(15L, "match-015", "10.0.0.2");
            ReflectionTestUtils.setField(expired, "expiresAt", Instant.now().minusSeconds(10));

            manager.evictExpiredSessions();

            assertThat(manager.getByToken(valid.getSessionToken())).isPresent();
            assertThat(manager.getByToken(expired.getSessionToken())).isEmpty();
            assertThat(manager.getByRoomId(15L)).isEmpty();
        }

        @Test
        @DisplayName("isExpired returns false for fresh session")
        void isExpired_freshSession() {
            RelaySession s = manager.createSession(16L, "match-016", "11.0.0.1");
            assertThat(s.isExpired()).isFalse();
        }
    }
}
