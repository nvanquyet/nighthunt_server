package com.nighthunt.dedicatedserver.service;

import com.nighthunt.dedicatedserver.dto.*;
import com.nighthunt.dedicatedserver.entity.DedicatedServer;
import com.nighthunt.dedicatedserver.repository.DedicatedServerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Business logic cho Dedicated Server lifecycle:
 *   allocate → container spin-up → register → heartbeat → cleanup
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DedicatedServerService {

    private final DedicatedServerRepository dsRepo;
    private final DockerManagerService      dockerManager;
    private final StringRedisTemplate       redis;

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(10);

    @Value("${ds.vps.public-ip:127.0.0.1}")
    private String vpsPublicIp;

    @Value("${ds.port.start:7777}")
    private int portStart;

    @Value("${ds.port.end:7900}")
    private int portEnd;

    @Value("${ds.max-players:16}")
    private int defaultMaxPlayers;

    // Redis key prefix cho session tokens
    private static final String SESSION_TOKEN_PREFIX = "ds:session:";
    private static final long   SESSION_TOKEN_TTL_MIN = 5;

    /** Internal record: server entity + plain secret (chỉ dùng trong dev mode) */
    private record SpawnResult(DedicatedServer server, String plainSecret) {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Được gọi bởi MatchmakingController khi client yêu cầu vào game.
     * Tìm server available hoặc spin up mới.
     *
     * @param region VPS region (e.g. "vn")
     * @param mapId  MapEntry.mapId (e.g. "map_01"), null = any
     */
    @Transactional
    public ServerAllocateResponse allocateServer(String region, String mapId, int expectedPlayers) {
        // 1. Tìm server đang chờ player và cùng map
        DedicatedServer server = dsRepo.findAvailable(region, mapId).orElse(null);
        String devSecret = null;

        // 2. Không có → tạo mới
        if (server == null) {
            SpawnResult result = spinUpNewServer(region, mapId, expectedPlayers);
            server    = result.server();
            devSecret = result.plainSecret(); // null trên production
        }

        // 3. Tạo one-time session token (client gửi khi connect UDP)
        String token = generateSessionToken(server.getServerId());

        return ServerAllocateResponse.builder()
                .serverId(server.getServerId())
                .ip(server.getIp())
                .port(server.getPort())
                .status(server.getStatus())
                .sessionToken(token)
                .devSecret(devSecret)   // null trên production, có giá trị khi dev
                .build();
    }

    /** Backward-compat overload for admin/manual allocation (uses defaultMaxPlayers as expected count). */
    @Transactional
    public ServerAllocateResponse allocateServer(String region, String mapId) {
        return allocateServer(region, mapId, defaultMaxPlayers);
    }

    /**
     * DS gọi về sau khi boot xong để báo "ready".
     * Header X-DS-Secret phải match server_secret_hash trong DB.
     */
    @Transactional
    public boolean registerServer(DsRegisterRequest req) {
        DedicatedServer server = dsRepo.findByServerId(req.getServerId())
                .orElse(null);

        if (server == null) {
            log.warn("[DSService] Register: unknown serverId={}", req.getServerId());
            return false;
        }

        // Verify secret
        if (!bcrypt.matches(req.getServerSecret(), server.getServerSecretHash())) {
            log.warn("[DSService] Register: invalid secret for serverId={}", req.getServerId());
            return false;
        }

        server.setStatus("ready");
        server.setMaxPlayers(req.getMaxPlayers() != null ? req.getMaxPlayers() : defaultMaxPlayers);
        server.setLastHeartbeatAt(LocalDateTime.now());
        dsRepo.save(server);

        log.info("[DSService] Server {} registered → READY on port {}", req.getServerId(), server.getPort());
        return true;
    }

    /**
     * DS gửi heartbeat mỗi 30s.
     */
    @Transactional
    public boolean heartbeat(DsHeartbeatRequest req) {
        DedicatedServer server = dsRepo.findByServerId(req.getServerId())
                .orElse(null);

        if (server == null || !bcrypt.matches(req.getServerSecret(), server.getServerSecretHash())) {
            return false;
        }

        server.setCurrentPlayers(req.getCurrentPlayers() != null ? req.getCurrentPlayers() : 0);
        server.setLastHeartbeatAt(LocalDateTime.now());

        // Cập nhật status dựa vào số player
        if (req.getCurrentPlayers() != null && req.getCurrentPlayers() > 0) {
            server.setStatus("in_game");
        } else if ("in_game".equals(server.getStatus())) {
            // Game kết thúc, quay về ready
            server.setStatus("ready");
        }

        dsRepo.save(server);
        return true;
    }

    /**
     * CI/CD notify image mới (chạy pull trước để latency thấp khi next allocate)
     */
    public void updateImage(String imageRef) {
        dockerManager.setCurrentImageRef(imageRef);
        log.info("[DSService] Pulling new image in background: {}", imageRef);
        // Pull async để không block HTTP response (compatible với Java 17)
        new Thread(() -> {
            try {
                dockerManager.pullImage(imageRef);
            } catch (Exception e) {
                log.error("[DSService] Image pull failed: {}", e.getMessage());
            }
        }, "ds-image-pull").start();
    }

    /** Lấy status của 1 server theo serverId */
    public String getServerStatus(String serverId) {
        return dsRepo.findByServerId(serverId)
                .map(DedicatedServer::getStatus)
                .orElse(null);
    }

    // ── Session Token ─────────────────────────────────────────────────────────

    /** Tạo token cho client, lưu vào Redis với TTL 5 phút */
    public String generateSessionToken(String serverId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String key   = SESSION_TOKEN_PREFIX + token;
        redis.opsForValue().set(key, serverId, SESSION_TOKEN_TTL_MIN, TimeUnit.MINUTES);
        return token;
    }

    /** DS validate token khi client connect */
    public boolean validateSessionToken(String token, String serverId) {
        String key            = SESSION_TOKEN_PREFIX + token;
        String storedServerId = redis.opsForValue().get(key);

        if (storedServerId == null || !storedServerId.equals(serverId)) {
            return false;
        }

        redis.delete(key); // One-time use
        return true;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private SpawnResult spinUpNewServer(String region, String mapId, int expectedPlayers) {
        String serverId     = UUID.randomUUID().toString();
        String serverSecret = UUID.randomUUID().toString().replace("-", "");
        int    port         = findAvailablePort();
        String secretHash   = bcrypt.encode(serverSecret);

        log.info("[DSService] Spinning up new DS: serverId={}, port={}, mapId={}, expectedPlayers={}", serverId, port, mapId, expectedPlayers);

        DedicatedServer server = dsRepo.save(DedicatedServer.builder()
                .serverId(serverId)
                .ip(vpsPublicIp)
                .port(port)
                .status("starting")
                .region(region)
                .mapId(mapId)
                .maxPlayers(defaultMaxPlayers)
                .imageTag(dockerManager.getCurrentImageRef())
                .serverSecretHash(secretHash)
                .build());

        String returnedSecret = null;

        try {
            String containerId = dockerManager.startContainer(serverId, port, serverSecret, defaultMaxPlayers, mapId, expectedPlayers);
            server.setDockerContainerId(containerId);
            dsRepo.save(server);

            if ("local-dev-no-container".equals(containerId)) {
                returnedSecret = serverSecret;
            }
            log.info("[DSService] Container record saved: {}", containerId);
        } catch (Exception e) {
            log.error("[DSService] Failed to start container: {}", e.getMessage());
            server.setStatus("stopped");
            server.setStoppedAt(LocalDateTime.now());
            dsRepo.save(server);
            throw new RuntimeException("Failed to start dedicated server: " + e.getMessage(), e);
        }

        return new SpawnResult(server, returnedSecret);
    }

    private int findAvailablePort() {
        for (int port = portStart; port <= portEnd; port++) {
            if (!dsRepo.existsByPortAndStatusNot(port, "stopped")) {
                return port;
            }
        }
        throw new RuntimeException("No available ports in range " + portStart + "-" + portEnd);
    }

    // ── Scheduled Cleanup ─────────────────────────────────────────────────────

    /** Mỗi 2 phút: cleanup servers bị treo khi starting > 3 phút */
    @Scheduled(fixedDelay = 120_000)
    @Transactional
    public void cleanupStaleStartingServers() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(3);
        var staleServers = dsRepo.findStaleStarting(cutoff);

        for (DedicatedServer server : staleServers) {
            log.warn("[DSService] Cleaning stale starting server: {}", server.getServerId());
            dockerManager.stopContainer(server.getDockerContainerId());
            server.setStatus("stopped");
            server.setStoppedAt(LocalDateTime.now());
            dsRepo.save(server);
        }
    }

    /** Mỗi 1 phút: cleanup servers không heartbeat > 90s (3 lần miss) → crash */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void cleanupDeadServers() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(90);
        var deadServers = dsRepo.findDeadServers(cutoff);

        for (DedicatedServer server : deadServers) {
            log.warn("[DSService] Cleaning dead server (no heartbeat): {}", server.getServerId());
            dockerManager.stopContainer(server.getDockerContainerId());
            server.setStatus("stopped");
            server.setStoppedAt(LocalDateTime.now());
            dsRepo.save(server);
        }
    }
}
