package com.nighthunt.dedicatedserver;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.dedicatedserver.dto.ServerAllocateResponse;
import com.nighthunt.dedicatedserver.entity.DedicatedServer;
import com.nighthunt.dedicatedserver.repository.DedicatedServerRepository;
import com.nighthunt.dedicatedserver.service.DedicatedServerService;
import com.nighthunt.dedicatedserver.service.DockerManagerService;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DedicatedServerService — covers allocation, game-ready
 * notification, session token lifecycle, and DS identity validation.
 *
 * All external dependencies (repository, Docker, Redis, WebSocket) are mocked.
 */
@ExtendWith(MockitoExtension.class)
class DedicatedServerServiceTest {

    @Mock private DedicatedServerRepository dsRepo;
    @Mock private DockerManagerService      dockerManager;
    @Mock private StringRedisTemplate       redis;
    @Mock private ConnectionManager         connectionManager;
    @Mock private RoomRepository            roomRepository;
    @Mock private RoomPlayerRepository      roomPlayerRepository;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks
    private DedicatedServerService service;

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(10);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "vpsPublicIp",       "20.2.235.140");
        ReflectionTestUtils.setField(service, "portStart",         7777);
        ReflectionTestUtils.setField(service, "portEnd",           7900);
        ReflectionTestUtils.setField(service, "defaultMaxPlayers", 16);
    }

    // ── allocateServer ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("allocateServer")
    class AllocateServerTests {

        @Test
        @DisplayName("Returns existing idle server when one is available")
        void allocate_returnsExistingIdleServer() {
            DedicatedServer idle = idleServer("srv-001", 7777);
            when(dsRepo.findAvailable("vn", "20.2.235.140", "map_01")).thenReturn(Optional.of(idle));
            when(redis.opsForValue()).thenReturn(valueOps);
            doNothing().when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

            ServerAllocateResponse resp = service.allocateServer("vn", "map_01", 4);

            assertThat(resp.getServerId()).isEqualTo("srv-001");
            assertThat(resp.getIp()).isEqualTo("20.2.235.140");
            assertThat(resp.getPort()).isEqualTo(7777);
            assertThat(resp.getSessionToken()).isNotBlank();
            // No Docker spawn should occur
            verify(dockerManager, never()).startContainer(anyString(), anyInt(), anyString(), anyInt(), nullable(String.class), anyInt(), nullable(String.class));
        }

        @Test
        @DisplayName("Spawns new server when no idle server is available")
        void allocate_spawnsNewWhenNoIdle() throws Exception {
            when(dsRepo.findAvailable("vn", "20.2.235.140", "map_01")).thenReturn(Optional.empty());
            when(dockerManager.getCurrentImageRef()).thenReturn("ghcr.io/nvanquyet/nighthunt-ds:latest");
            // findByPort not called in this path — just ensure port loop doesn't block
            when(dsRepo.existsByPortAndStatusNot(anyInt(), anyString())).thenReturn(false);
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
            DedicatedServer saved = idleServer("srv-new", 7777);
            when(dsRepo.save(any(DedicatedServer.class))).thenReturn(saved);
            when(dockerManager.startContainer(anyString(), anyInt(), anyString(), anyInt(), nullable(String.class), anyInt(), nullable(String.class)))
                    .thenReturn("local-dev-no-container");
            when(redis.opsForValue()).thenReturn(valueOps);
            doNothing().when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

            ServerAllocateResponse resp = service.allocateServer("vn", "map_01", 4);

            assertThat(resp.getServerId()).isNotBlank();
            verify(dockerManager).startContainer(anyString(), anyInt(), anyString(), anyInt(), nullable(String.class), anyInt(), nullable(String.class));
        }

        @Test
        @DisplayName("allocateServerForMatch stores matchId on idle server")
        void allocateForMatch_setsMatchIdOnIdleServer() {
            DedicatedServer idle = idleServer("srv-002", 7778);
            when(dsRepo.findAvailable("vn", "20.2.235.140", null)).thenReturn(Optional.of(idle));
            when(dsRepo.save(idle)).thenReturn(idle);
            when(redis.opsForValue()).thenReturn(valueOps);
            doNothing().when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

            service.allocateServerForMatch("vn", null, 2, "match-uuid-42");

            assertThat(idle.getMatchId()).isEqualTo("match-uuid-42");
            verify(dsRepo).save(idle);
        }
    }

    // ── notifyGameReady ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("notifyGameReady")
    class NotifyGameReadyTests {

        @Test
        @DisplayName("Returns false when serverId is unknown")
        void gameReady_unknownServerId() {
            when(dsRepo.findByServerId("ghost-srv")).thenReturn(Optional.empty());

            boolean result = service.notifyGameReady("ghost-srv", "any-secret");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Returns false when secret is wrong")
        void gameReady_wrongSecret() {
            String correct = bcrypt.encode("correct-secret");
            DedicatedServer server = readyServer("srv-003", correct);
            when(dsRepo.findByServerId("srv-003")).thenReturn(Optional.of(server));

            boolean result = service.notifyGameReady("srv-003", "wrong-secret");

            assertThat(result).isFalse();
            verify(dsRepo, never()).save(any());
        }

        @Test
        @DisplayName("Returns true and sets status=ready when credentials are valid (no matchId)")
        void gameReady_validCreds_noMatchId() {
            String secret = "correct-secret";
            DedicatedServer server = readyServer("srv-004", bcrypt.encode(secret));
            server.setMatchId(null);
            when(dsRepo.findByServerId("srv-004")).thenReturn(Optional.of(server));
            when(dsRepo.save(any())).thenReturn(server);

            boolean result = service.notifyGameReady("srv-004", secret);

            assertThat(result).isTrue();
            assertThat(server.getStatus()).isEqualTo("ready");
        }
    }

    // ── validateDsAndGetMatchId ───────────────────────────────────────────────

    @Nested
    @DisplayName("validateDsAndGetMatchId")
    class ValidateDsTests {

        @Test
        @DisplayName("Throws BusinessException for unknown serverId")
        void validate_unknownServerId() {
            when(dsRepo.findByServerId("bad-id")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.validateDsAndGetMatchId("bad-id", "secret"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("bad-id");
        }

        @Test
        @DisplayName("Throws BusinessException for wrong secret")
        void validate_wrongSecret() {
            DedicatedServer server = readyServer("srv-005", bcrypt.encode("real-secret"));
            when(dsRepo.findByServerId("srv-005")).thenReturn(Optional.of(server));

            assertThatThrownBy(() -> service.validateDsAndGetMatchId("srv-005", "wrong"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("srv-005");
        }

        @Test
        @DisplayName("Returns matchId for valid DS credentials")
        void validate_valid_returnsMatchId() {
            String secret = "valid-secret";
            DedicatedServer server = readyServer("srv-006", bcrypt.encode(secret));
            server.setMatchId("match-abc");
            when(dsRepo.findByServerId("srv-006")).thenReturn(Optional.of(server));

            String matchId = service.validateDsAndGetMatchId("srv-006", secret);

            assertThat(matchId).isEqualTo("match-abc");
        }
    }

    // ── Session Token ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Session Token")
    class SessionTokenTests {

        @Test
        @DisplayName("generateSessionToken stores token in Redis with 5-minute TTL")
        void generateToken_storesInRedis() {
            when(redis.opsForValue()).thenReturn(valueOps);
            doNothing().when(valueOps).set(anyString(), eq("srv-007"), eq(5L), eq(TimeUnit.MINUTES));

            String token = service.generateSessionToken("srv-007");

            assertThat(token).isNotBlank().hasSize(32);
            verify(valueOps).set(startsWith("ds:session:"), eq("srv-007"), eq(5L), eq(TimeUnit.MINUTES));
        }

        @Test
        @DisplayName("validateSessionToken returns true for correct serverId and deletes key")
        void validateToken_valid() {
            String token = "abc123token";
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("ds:session:" + token)).thenReturn("srv-007");
            when(redis.delete("ds:session:" + token)).thenReturn(true);

            boolean valid = service.validateSessionToken(token, "srv-007");

            assertThat(valid).isTrue();
            verify(redis).delete("ds:session:" + token);
        }

        @Test
        @DisplayName("validateSessionToken returns false when token not found")
        void validateToken_notFound() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null);

            boolean valid = service.validateSessionToken("ghost-token", "srv-007");

            assertThat(valid).isFalse();
        }

        @Test
        @DisplayName("validateSessionToken returns false when serverId mismatch")
        void validateToken_wrongServer() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("ds:session:tok")).thenReturn("other-server");

            boolean valid = service.validateSessionToken("tok", "srv-007");

            assertThat(valid).isFalse();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DedicatedServer idleServer(String serverId, int port) {
        return DedicatedServer.builder()
                .serverId(serverId)
                .ip("20.2.235.140")
                .port(port)
                .status("ready")
                .region("vn")
                .currentPlayers(0)
                .maxPlayers(16)
                .serverSecretHash(bcrypt.encode("test-secret"))
                .lastHeartbeatAt(LocalDateTime.now())
                .build();
    }

    private DedicatedServer readyServer(String serverId, String secretHash) {
        return DedicatedServer.builder()
                .serverId(serverId)
                .ip("20.2.235.140")
                .port(7777)
                .status("starting")
                .region("vn")
                .currentPlayers(0)
                .maxPlayers(16)
                .serverSecretHash(secretHash)
                .lastHeartbeatAt(LocalDateTime.now())
                .build();
    }
}
