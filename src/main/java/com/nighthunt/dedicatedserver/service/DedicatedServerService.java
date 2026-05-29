package com.nighthunt.dedicatedserver.service;

import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.dedicatedserver.dto.*;
import com.nighthunt.dedicatedserver.entity.DedicatedServer;
import com.nighthunt.dedicatedserver.repository.DedicatedServerRepository;
import com.nighthunt.game.websocket.port.ConnectionManager;
import com.nighthunt.room.entity.RoomPlayer;
import com.nighthunt.room.repository.RoomPlayerRepository;
import com.nighthunt.room.repository.RoomRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final ConnectionManager         connectionManager;
    private final RoomRepository            roomRepository;
    private final RoomPlayerRepository      roomPlayerRepository;

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(10);

    @Value("${ds.vps.public-ip:127.0.0.1}")
    private String vpsPublicIp;

    @Value("${ds.port.start:7777}")
    private int portStart;

    @Value("${ds.port.end:7900}")
    private int portEnd;

    @Value("${ds.max-players:16}")
    private int defaultMaxPlayers;

    /**
     * Nếu true: khi 1 DS chết (no heartbeat) → tự động thu hồi TOÀN BỘ fleet.
     * Dùng trong môi trường test để validate fleet-reclaim-on-failure scenario.
     * Production: false (1 DS chết không nên kill tất cả game đang chạy).
     */
    @Value("${ds.fleet.auto-reclaim-on-failure:false}")
    private boolean fleetAutoReclaimOnFailure;

    // Redis key prefix cho session tokens
    private static final String SESSION_TOKEN_PREFIX = "ds:session:";
    private static final long   SESSION_TOKEN_TTL_MIN = 5;

    /** Internal record: server entity + plain secret (chỉ dùng trong dev mode) */
    private record SpawnResult(DedicatedServer server, String plainSecret) {}

    // ── Startup Validation ────────────────────────────────────────────────────

    @PostConstruct
    public void validateConfig() {
        // Read env var DIRECTLY from OS (bypasses Spring property resolution) for comparison.
        String envVarDirect = System.getenv("VPS_PUBLIC_IP");

        // Spring-injected value (via application.yml placeholder ${VPS_PUBLIC_IP:127.0.0.1})
        boolean isLocalIp = "127.0.0.1".equals(vpsPublicIp) || "localhost".equals(vpsPublicIp);
        boolean dockerEnabled = dockerManager.isDockerEnabled();

        log.info("╔══════════════════════════════════════════════════════════╗");
        log.info("║  Dedicated Server Config                                  ║");
        log.info("╠══════════════════════════════════════════════════════════╣");
        log.info("║  OS env VPS_PUBLIC_IP  = {} (raw System.getenv)   ║", envVarDirect);
        log.info("║  Spring vpsPublicIp    = {} {}                    ║", vpsPublicIp,
                isLocalIp ? "⚠ LOCAL" : "✓ REAL");
        log.info("║  DS_DOCKER             = {}                               ║", dockerEnabled ? "ENABLED " : "DISABLED");
        log.info("║  Port range            = {} - {}                         ║", portStart, portEnd);
        log.info("╚══════════════════════════════════════════════════════════╝");

        // Auto-fix: if Spring resolved the default but OS env has a real IP, use it directly.
        if (isLocalIp && envVarDirect != null && !envVarDirect.isBlank()
                && !"127.0.0.1".equals(envVarDirect) && !"localhost".equals(envVarDirect)) {
            log.warn("[DS-Config] ⚠ Spring did not pick up VPS_PUBLIC_IP from env! " +
                     "Overriding vpsPublicIp: '127.0.0.1' → '{}'", envVarDirect);
            vpsPublicIp = envVarDirect;
            isLocalIp = false;
        }

        if (isLocalIp && dockerEnabled) {
            log.warn("[DS-Config] ⚠ vpsPublicIp=127.0.0.1 với DS_DOCKER_ENABLED=true!");
            log.warn("[DS-Config]   Clients sẽ nhận dsIp=127.0.0.1 → chỉ kết nối được nếu cùng máy.");
            log.warn("[DS-Config]   Production: set VPS_PUBLIC_IP=<your-domain-or-ip> trong .env.production");
        }
        if (isLocalIp && !dockerEnabled) {
            log.info("[DS-Config] DEV MODE: docker disabled, vpsPublicIp=127.0.0.1");
        }
    }

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
        return doAllocate(region, mapId, expectedPlayers, null);
    }

    /** Backward-compat overload for admin/manual allocation (uses defaultMaxPlayers as expected count). */
    @Transactional
    public ServerAllocateResponse allocateServer(String region, String mapId) {
        return doAllocate(region, mapId, defaultMaxPlayers, null);
    }

    /**
     * CI/CD smoke-test: creates a DS record with a known plain-text secret WITHOUT spinning
     * up a real Docker container. Used by POST /api/admin/ds/test-alloc so the pipeline
     * can call /ds/register, /ds/heartbeat, and /ds/game-ready with valid credentials.
     *
     * The record is created with status="test" and will be removed by cleanupDeadServers
     * after 90 s of no heartbeat (or immediately via /api/admin/ds/cleanup).
     *
     * @return map with { serverId, devSecret, port, status }
     */
    @Transactional
    public Map<String, Object> allocateTestServer() {
        String serverId     = UUID.randomUUID().toString();
        String serverSecret = UUID.randomUUID().toString().replace("-", "");
        int    port         = findAvailablePort();
        String secretHash   = bcrypt.encode(serverSecret);

        DedicatedServer server = dsRepo.save(DedicatedServer.builder()
                .serverId(serverId)
                .ip(vpsPublicIp)
                .port(port)
                .status("test")
                .region("vn")
                .mapId("map_01")
                .maxPlayers(defaultMaxPlayers)
                .imageTag("test")
                .serverSecretHash(secretHash)
                .dockerContainerId("test-no-container")
                .build());

        log.info("[DS-Svc] allocateTestServer: created test DS record serverId={} port={}", serverId, port);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("serverId",  server.getServerId());
        result.put("devSecret", serverSecret);
        result.put("port",      server.getPort());
        result.put("status",    server.getStatus());
        return result;
    }

    /**
     * Allocate a DS for a specific ranked match.
     * matchId được truyền xuống spinUpNewServer và gán ngay khi tạo DS record,
     * sau đó được pass vào container ENV (MATCH_ID) — DS biết matchId từ lúc boot.
     */
    @Transactional
    public ServerAllocateResponse allocateServerForMatch(String region, String mapId,
                                                         int expectedPlayers, String matchId) {
        return doAllocate(region, mapId, expectedPlayers, matchId);
    }

    /**
     * Core allocation logic. Tìm server available hoặc spin up mới với matchId đúng từ đầu.
     */
    @Transactional
    private ServerAllocateResponse doAllocate(String region, String mapId, int expectedPlayers, String matchId) {
        DedicatedServer server = dsRepo.findAvailable(region, vpsPublicIp, mapId).orElse(null);
        String devSecret = null;
        boolean reusingReadyServer = false;

        if (server == null) {
            SpawnResult result = spinUpNewServer(region, mapId, expectedPlayers, matchId);
            server    = result.server();
            devSecret = result.plainSecret(); // null trên production
        } else if (matchId != null) {
            // DS đã READY từ match trước — assign matchId mới và broadcast ds_ready ngay
            // vì DS sẽ không bao giờ gọi /game-ready lần 2 (đã hoàn thành boot sequence rồi)
            server.setMatchId(matchId);
            dsRepo.save(server);
            reusingReadyServer = true;
        }

        String token = generateSessionToken(server.getServerId());
        ServerAllocateResponse resp = ServerAllocateResponse.builder()
                .serverId(server.getServerId())
                .ip(server.getIp())
                .port(server.getPort())
                .status(server.getStatus())
                .sessionToken(token)
                .devSecret(devSecret)
                .build();

        if (reusingReadyServer) {
            log.info("[DS-Alloc] Reusing existing READY server {} for matchId={} — broadcasting ds_ready immediately",
                    server.getServerId(), matchId);
            broadcastDsReady(server, matchId);
        }

        return resp;
    }

    /**
     * Called by DS via POST /ds/game-ready when it has fully booted and is accepting players.
     * Validates the DS secret, updates status to "ready", then broadcasts "ds_ready" to all
     * players in the associated match via WebSocket.
     *
     * @return true if broadcast was sent, false if credentials were invalid
     */
    /** Broadcast ds_ready WS event to all players in the given match. */
    private void broadcastDsReady(DedicatedServer server, String matchId) {
        var roomOpt = roomRepository.findByMatchId(matchId);
        if (roomOpt.isEmpty()) {
            log.warn("[DS-Svc] broadcastDsReady: room not found for matchId={}", matchId);
            return;
        }
        var room = roomOpt.get();
        List<RoomPlayer> players = roomPlayerRepository.findByRoomId(room.getId());
        List<Long> playerIds = players.stream().map(RoomPlayer::getUserId).toList();
        log.info("[DS-Svc] ds_ready: sending to {} players={} dsIp={}:{} matchId={} mapId={}",
                players.size(), playerIds, server.getIp(), server.getPort(), matchId, server.getMapId());

        Map<String, Object> payload = new HashMap<>();
        payload.put("matchId",  matchId);
        payload.put("dsIp",     server.getIp());
        payload.put("dsPort",   server.getPort());
        payload.put("mapId",    server.getMapId());
        payload.put("serverId", server.getServerId());

        int sent = 0;
        for (RoomPlayer rp : players) {
            connectionManager.sendToUser(rp.getUserId(), "ds_ready", payload);
            sent++;
        }
        log.info("[DS-Svc] ds_ready broadcast complete — sent={}/{} matchId={} ds={}:{}",
                sent, players.size(), matchId, server.getIp(), server.getPort());
    }

    @Transactional
    public boolean notifyGameReady(String serverId, String serverSecret) {
        log.info("[DS-Svc] game-ready ▶ serverId={}", serverId);
        DedicatedServer server = dsRepo.findByServerId(serverId).orElse(null);
        if (server == null) {
            log.warn("[DS-Svc] game-ready: unknown serverId={}", serverId);
            return false;
        }
        if (!bcrypt.matches(serverSecret, server.getServerSecretHash())) {
            log.warn("[DS-Svc] game-ready: invalid secret for serverId={}", serverId);
            return false;
        }

        server.setStatus("ready");
        server.setLastHeartbeatAt(LocalDateTime.now());
        dsRepo.save(server);

        String matchId = server.getMatchId();
        if (matchId == null) {
            log.warn("[DS-Svc] game-ready: serverId={} has no matchId — cannot broadcast ds_ready", serverId);
            return true; // DS is marked ready but no players to notify
        }

        broadcastDsReady(server, matchId);
        return true;
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
            log.warn("[DS-Svc] Register: unknown serverId={}", req.getServerId());
            return false;
        }

        // Verify secret
        if (!bcrypt.matches(req.getServerSecret(), server.getServerSecretHash())) {
            log.warn("[DS-Svc] Register: invalid secret for serverId={}", req.getServerId());
            return false;
        }

        server.setStatus("ready");
        server.setMaxPlayers(req.getMaxPlayers() != null ? req.getMaxPlayers() : defaultMaxPlayers);
        server.setLastHeartbeatAt(LocalDateTime.now());

        // DS có thể báo IP thực (multi-VPS hoặc container IP khác VPS_PUBLIC_IP).
        // Nếu không → giữ nguyên IP từ lúc allocate.
        if (req.getReportedIp() != null && !req.getReportedIp().isBlank()) {
            log.info("[DS-Svc] Register: DS reports IP override {}→{} for serverId={}",
                    server.getIp(), req.getReportedIp(), req.getServerId());
            server.setIp(req.getReportedIp());
        }

        dsRepo.save(server);

        log.info("[DS-Svc] Server {} registered → READY on ip={}:{}", req.getServerId(), server.getIp(), server.getPort());
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

        // Cập nhật status: chỉ chuyển sang in_game khi có player
        // KHÔNG tự chuyển in_game → ready vì backend không biết game đã kết thúc hay chưa.
        // DS sẽ gọi POST /api/match/end/ranked rồi tự Application.Quit() khi game end.
        if (req.getCurrentPlayers() != null && req.getCurrentPlayers() > 0) {
            server.setStatus("in_game");
        }

        dsRepo.save(server);
        return true;
    }

    /**
     * CI/CD notify image mới.
     * - Nếu image đã có locally (e.g. patched image build trên VPS): switch ngay.
     * - Nếu chưa có locally: pull trước từ registry, switch sau khi pull thành công.
     *   Tránh spawn DS với tag chưa tồn tại nếu pull thất bại.
     */
    public void updateImage(String imageRef) {
        if (dockerManager.isImageLocal(imageRef)) {
            // Image có sẵn locally → switch ngay, không cần pull
            dockerManager.setCurrentImageRef(imageRef);
            log.info("[DSService] Image switched (local) → {}", imageRef);
            // Vẫn chạy pull ngầm để đồng bộ nếu có version mới hơn trên registry
            new Thread(() -> {
                try { dockerManager.pullImage(imageRef); }
                catch (Exception e) { log.debug("[DSService] Background pull skipped (local-only image): {}", e.getMessage()); }
            }, "ds-image-pull").start();
            return;
        }
        log.info("[DSService] Pulling new image from registry: {}", imageRef);
        new Thread(() -> {
            try {
                dockerManager.pullImage(imageRef);
                dockerManager.setCurrentImageRef(imageRef);
                log.info("[DSService] Image switched (registry) → {}", imageRef);
            } catch (Exception e) {
                log.error("[DSService] Image pull failed, keeping old ref: {}", e.getMessage());
            }
        }, "ds-image-pull").start();
    }

    /** Lấy status của 1 server theo serverId */
    public String getServerStatus(String serverId) {
        return dsRepo.findByServerId(serverId)
                .map(DedicatedServer::getStatus)
                .orElse(null);
    }

    /**
     * Validates DS identity via BCrypt secret check and returns the matchId stored on the DS entity.
     * Used by {@code POST /match/end/ranked} to authenticate DS and resolve matchId when DS doesn't
     * know its own matchId (matchId is assigned AFTER container start, not passed as env var).
     *
     * @param serverId    the DS serverId from the request body
     * @param plainSecret the plain-text serverSecret from the request body or X-DS-Secret header
     * @return the matchId stored on the DS entity (may be null if no match was ever allocated)
     * @throws IllegalArgumentException if serverId is unknown or secret is wrong
     */
    public String validateDsAndGetMatchId(String serverId, String plainSecret) {
        DedicatedServer server = dsRepo.findByServerId(serverId).orElse(null);
        if (server == null) {
            log.warn("[DSService] validateDsAndGetMatchId: unknown serverId={}", serverId);
            throw new IllegalArgumentException("Unknown DS: " + serverId);
        }
        if (plainSecret == null || !bcrypt.matches(plainSecret, server.getServerSecretHash())) {
            log.warn("[DSService] validateDsAndGetMatchId: invalid secret for serverId={}", serverId);
            throw new SecurityException("Invalid DS secret for serverId=" + serverId);
        }
        return server.getMatchId();
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

    private SpawnResult spinUpNewServer(String region, String mapId, int expectedPlayers, String matchId) {
        String serverId     = UUID.randomUUID().toString();
        String serverSecret = UUID.randomUUID().toString().replace("-", "");
        int    port         = findAvailablePort();
        String secretHash   = bcrypt.encode(serverSecret);

        log.info("[DS-Alloc] spinUpNewServer \u25ba serverId={} ip={}:{} mapId={} expectedPlayers={} region={}",
                serverId, vpsPublicIp, port, mapId, expectedPlayers, region);

        DedicatedServer server = dsRepo.save(DedicatedServer.builder()
                .serverId(serverId)
                .ip(vpsPublicIp)
                .port(port)
                .status("starting")
                .region(region)
                .mapId(mapId)
                .matchId(matchId)           // Đặt ngay để DS biết matchId từ lúc boot
                .maxPlayers(defaultMaxPlayers)
                .imageTag(dockerManager.getCurrentImageRef())
                .serverSecretHash(secretHash)
                .build());

        log.info("[DS-Alloc] DS entity saved (status=starting) — starting Docker container...");
        String returnedSecret = null;

        try {
            String containerId = dockerManager.startContainer(serverId, port, serverSecret, defaultMaxPlayers, mapId, expectedPlayers, matchId);
            server.setDockerContainerId(containerId);
            dsRepo.save(server);

            if ("local-dev-no-container".equals(containerId)) {
                returnedSecret = serverSecret;
                log.info("[DS-Alloc] DEV MODE — no Docker container; devSecret returned for manual /ds/register call.");
            } else {
                log.info("[DS-Alloc] Docker container started: containerId={}", containerId);
            }
        } catch (Exception e) {
            log.error("[DS-Alloc] FAILED to start Docker container: {}", e.getMessage(), e);
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

    /**
     * Reclaim (stop + mark stopped) the DS container that was serving {@code matchId}.
     *
     * <p>Called by {@code MatchResultService} immediately after the match-end payload is
     * persisted and the {@code match_ended} WS event is broadcast.  This ensures the
     * container is released as soon as the game ends rather than waiting for the
     * heartbeat watchdog (which runs every 60–120 s).</p>
     *
     * <p>Safe to call even if no DS is associated with the match (relay / custom games).</p>
     */
    /**
     * Admin-initiated force-terminate of a specific DS instance.
     * Stops the container, notifies connected players via WS, marks DB stopped.
     *
     * @param serverId the serverId (UUID) of the DS to terminate
     * @param reason   human-readable reason shown to players (nullable → default message)
     * @throws IllegalArgumentException if serverId not found
     */
    @Transactional
    public void terminateServerByAdmin(String serverId, String reason) {
        DedicatedServer server = dsRepo.findByServerId(serverId)
                .orElseThrow(() -> new IllegalArgumentException("DS not found: " + serverId));

        String effectiveReason = (reason != null && !reason.isBlank())
                ? reason : "Server was terminated by administrator";

        log.warn("[AdminDS] Force-terminating serverId={} matchId={} reason={}",
                serverId, server.getMatchId(), effectiveReason);

        // 1. Notify all players currently in this DS via WS
        String matchId = server.getMatchId();
        if (matchId != null && !matchId.isBlank()) {
            roomRepository.findByMatchId(matchId).ifPresent(room -> {
                List<RoomPlayer> players = roomPlayerRepository.findByRoomId(room.getId());
                Map<String, Object> payload = new HashMap<>();
                payload.put("reason",  effectiveReason);
                payload.put("matchId", matchId);
                payload.put("serverId", serverId);
                for (RoomPlayer rp : players) {
                    connectionManager.sendToUser(rp.getUserId(), "server_terminated", payload);
                }
                log.info("[AdminDS] Sent server_terminated to {} players in match {}",
                        players.size(), matchId);

                room.setStatus(GameConstants.ROOM_STATUS_CLOSED);
                roomRepository.save(room);
            });
        }

        // 2. Stop Docker container
        try {
            dockerManager.stopContainer(server.getDockerContainerId());
        } catch (Exception e) {
            log.error("[AdminDS] Docker stop failed for serverId={}: {}", serverId, e.getMessage());
            // Continue — still mark DB as stopped
        }

        // 3. Mark stopped in DB
        server.setStatus("stopped");
        server.setStoppedAt(LocalDateTime.now());
        dsRepo.save(server);

        log.info("[AdminDS] DS terminated: serverId={}", serverId);
    }

    @Transactional
    public void reclaimServerForMatch(String matchId) {
        if (matchId == null || matchId.isBlank()) return;

        dsRepo.findActiveByMatchId(matchId).ifPresentOrElse(server -> {
            log.info("[DS-Reclaim] Stopping DS container for matchId={} serverId={} containerId={}",
                    matchId, server.getServerId(), server.getDockerContainerId());

            // Stop Docker container (--rm flag auto-removes it afterwards)
            dockerManager.stopContainer(server.getDockerContainerId());

            server.setStatus("stopped");
            server.setStoppedAt(LocalDateTime.now());
            dsRepo.save(server);

            log.info("[DS-Reclaim] DS container stopped and DB marked STOPPED — serverId={} matchId={}",
                    server.getServerId(), matchId);
        }, () ->
            log.debug("[DS-Reclaim] No active DS found for matchId={} (relay/custom mode or already stopped)", matchId)
        );
    }

    /** Mỗi 2 phút: cleanup servers bị treo khi starting > 3 phút */
    @Scheduled(fixedDelay = 120_000)
    @Transactional
    public void cleanupStaleStartingServers() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(3);
        var staleServers = dsRepo.findStaleStarting(cutoff);

        for (DedicatedServer server : staleServers) {
            log.warn("[DS-Svc] Cleaning stale starting server: {}", server.getServerId());
            // Thông báo cho player trước khi dừng container
            cancelMatchForDeadServer(server, "Server boot timeout — match could not start");
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
        if (deadServers.isEmpty()) return;

        if (fleetAutoReclaimOnFailure) {
            // Fleet-incident mode: 1 DS chết → thu hồi TOÀN BỘ fleet
            String triggerServerId = deadServers.get(0).getServerId();
            log.warn("[DS-Fleet] Auto-reclaim triggered by dead DS: {} (fleet.auto-reclaim-on-failure=true)",
                    triggerServerId);
            fleetReclaimAll("DS fleet incident — server died: " + triggerServerId);
            return;
        }

        for (DedicatedServer server : deadServers) {
            log.warn("[DS-Svc] Cleaning dead server (no heartbeat): {}", server.getServerId());
            cancelMatchForDeadServer(server, "Server crashed — match was cancelled");
            dockerManager.stopContainer(server.getDockerContainerId());
            server.setStatus("stopped");
            server.setStoppedAt(LocalDateTime.now());
            dsRepo.save(server);
        }
    }

    // ── Fleet Management ──────────────────────────────────────────────────────

    /**
     * Trả về trạng thái sức khoẻ toàn bộ DS fleet hiện tại.
     * fleetHealth: OK | WARNING | CRITICAL
     */
    public Map<String, Object> getFleetStatus() {
        List<DedicatedServer> allActive = dsRepo.findAll().stream()
                .filter(s -> !"stopped".equals(s.getStatus()))
                .toList();

        LocalDateTime now           = LocalDateTime.now();
        LocalDateTime hbCutoff      = now.minusSeconds(90);

        List<Map<String, Object>> dsInfoList = allActive.stream().map(ds -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("serverId",       ds.getServerId());
            info.put("status",         ds.getStatus());
            info.put("port",           ds.getPort());
            info.put("matchId",        ds.getMatchId());
            info.put("currentPlayers", ds.getCurrentPlayers());
            info.put("containerId",    ds.getDockerContainerId());
            info.put("startedAt",      ds.getStartedAt());

            boolean heartbeatOk = false;
            long    heartbeatAgeMs = -1;
            if (ds.getLastHeartbeatAt() != null) {
                heartbeatAgeMs = Duration.between(ds.getLastHeartbeatAt(), now).toMillis();
                heartbeatOk    = ds.getLastHeartbeatAt().isAfter(hbCutoff);
            }
            info.put("heartbeatAgeMs", heartbeatAgeMs);
            info.put("heartbeatOk",    heartbeatOk);
            return info;
        }).toList();

        long deadCount = dsInfoList.stream()
                .filter(d -> !Boolean.TRUE.equals(d.get("heartbeatOk")))
                .count();

        String health = "OK";
        if (!allActive.isEmpty() && deadCount > 0 && deadCount < allActive.size()) health = "WARNING";
        else if (!allActive.isEmpty() && deadCount == allActive.size())             health = "CRITICAL";
        else if (allActive.isEmpty())                                               health = "EMPTY";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fleetHealth",            health);
        result.put("totalActive",            allActive.size());
        result.put("deadCount",              deadCount);
        result.put("autoReclaimOnFailure",   fleetAutoReclaimOnFailure);
        result.put("timestamp",              now.toString());
        result.put("servers",                dsInfoList);
        return result;
    }

    /**
     * Thu hồi TOÀN BỘ DS fleet đang active.
     * Notify players qua WS, stop containers, mark DB stopped.
     * Trả về báo cáo chi tiết.
     */
    @Transactional
    public Map<String, Object> fleetReclaimAll(String triggerReason) {
        List<DedicatedServer> active = dsRepo.findAll().stream()
                .filter(s -> !"stopped".equals(s.getStatus()))
                .toList();

        List<Map<String, Object>> reclaimed = new ArrayList<>();
        List<Map<String, Object>> failed    = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        log.warn("[DS-Fleet] ===== FLEET RECLAIM START ===== reason={} total={}",
                triggerReason, active.size());

        for (DedicatedServer server : active) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("serverId",    server.getServerId());
            entry.put("status",      server.getStatus());
            entry.put("matchId",     server.getMatchId());
            entry.put("containerId", server.getDockerContainerId());
            entry.put("port",        server.getPort());
            try {
                cancelMatchForDeadServer(server, "Fleet incident: " + triggerReason);
                dockerManager.stopContainer(server.getDockerContainerId());
                server.setStatus("stopped");
                server.setStoppedAt(now);
                dsRepo.save(server);
                entry.put("reclaimStatus", "SUCCESS");
                reclaimed.add(entry);
                log.warn("[DS-Fleet] Reclaimed serverId={}", server.getServerId());
            } catch (Exception e) {
                entry.put("reclaimStatus", "FAILED");
                entry.put("error", e.getMessage());
                failed.add(entry);
                log.error("[DS-Fleet] Failed to reclaim serverId={}: {}", server.getServerId(), e.getMessage());
            }
        }

        log.warn("[DS-Fleet] ===== FLEET RECLAIM DONE ===== reclaimed={}/{} failed={}",
                reclaimed.size(), active.size(), failed.size());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("incidentTime",           now.toString());
        report.put("triggerReason",          triggerReason);
        report.put("totalReclaimAttempted",  active.size());
        report.put("reclaimedCount",         reclaimed.size());
        report.put("failedCount",            failed.size());
        report.put("reclaimed",              reclaimed);
        report.put("failed",                 failed);
        return report;
    }

    /**
     * Tìm room gắn với matchId của DS vừa chết/treo, đóng room và broadcast match_cancelled
     * tới tất cả player. Phải gọi TRƯỚC khi đổi status DS sang "stopped".
     */
    private void cancelMatchForDeadServer(DedicatedServer server, String reason) {
        String matchId = server.getMatchId();
        if (matchId == null || matchId.isBlank()) return;

        roomRepository.findByMatchId(matchId).ifPresent(room -> {
            String roomStatus = room.getStatus();
            if (!GameConstants.ROOM_STATUS_IN_GAME.equals(roomStatus)
                    && !GameConstants.ROOM_STATUS_WAITING.equals(roomStatus)) {
                return; // Room đã closed/finished — không cần làm gì
            }

            room.setStatus(GameConstants.ROOM_STATUS_CLOSED);
            roomRepository.save(room);

            List<RoomPlayer> players = roomPlayerRepository.findByRoomId(room.getId());
            Map<String, Object> payload = new HashMap<>();
            payload.put("reason",  reason);
            payload.put("matchId", matchId);

            for (RoomPlayer rp : players) {
                connectionManager.sendToUser(rp.getUserId(), "match_cancelled", payload);
            }

            log.warn("[DS-Svc] Cancelled match {} (room={} had {} players) — reason: {}",
                    matchId, room.getId(), players.size(), reason);
        });
    }

    /** Mỗi 3 phút: thu hồi DS đã ready nhưng không có player nào trong > 5 phút (bị bỏ hoang). */
    @Scheduled(fixedDelay = 180_000)
    @Transactional
    public void cleanupIdleServers() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        List<DedicatedServer> idleServers = dsRepo.findIdleServers(cutoff);

        for (DedicatedServer server : idleServers) {
            log.info("[DSService] Reclaiming idle server (0 players, {}min+): {}",
                    5, server.getServerId());
            dockerManager.stopContainer(server.getDockerContainerId());
            server.setStatus("stopped");
            server.setStoppedAt(LocalDateTime.now());
            dsRepo.save(server);
        }
    }
}
