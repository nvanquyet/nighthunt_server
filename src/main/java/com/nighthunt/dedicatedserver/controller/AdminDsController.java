package com.nighthunt.dedicatedserver.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.dedicatedserver.entity.DedicatedServer;
import com.nighthunt.dedicatedserver.repository.DedicatedServerRepository;
import com.nighthunt.dedicatedserver.service.DedicatedServerService;
import com.nighthunt.dedicatedserver.service.DockerManagerService;
import com.nighthunt.match.entity.Match;
import com.nighthunt.match.entity.MatchPlayerResult;
import com.nighthunt.match.repository.MatchPlayerResultRepository;
import com.nighthunt.match.repository.MatchRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Admin-only endpoints cho CI/CD pipeline và monitoring.
 * Bảo mật bằng: X-Admin-Secret header.
 */
@RestController
@RequestMapping("/admin/ds")
@RequiredArgsConstructor
@Slf4j
public class AdminDsController {

    private final DedicatedServerService dsService;
    private final DedicatedServerRepository dsRepo;
    private final DockerManagerService dockerManager;
    private final MatchRepository matchRepo;
    private final MatchPlayerResultRepository matchPlayerResultRepo;
    private final MeterRegistry meterRegistry;

    @Value("${ADMIN_SECRET:change-me-in-production}")
    private String adminSecret;

    /**
     * GitHub Actions gọi sau khi push Docker image mới.
     * Body: { "version": "20260228-abcd1234", "imageRef": "ghcr.io/.../nighthunt-ds:..." }
     */
    @PostMapping("/update-image")
    public ApiResponse<String> updateImage(
            @RequestBody Map<String, String> body,
            @RequestHeader("X-Admin-Secret") String secret) {

        if (!adminSecret.equals(secret)) {
            return ApiResponse.error("Unauthorized", "FORBIDDEN");
        }

        String imageRef = body.get("imageRef");
        String version  = body.get("version");

        if (imageRef == null || imageRef.isBlank()) {
            return ApiResponse.error("imageRef is required", "BAD_REQUEST");
        }

        log.info("[AdminDS] New image available: {} (version={})", imageRef, version);
        dsService.updateImage(imageRef);

        return ApiResponse.ok("Image update scheduled: " + imageRef);
    }

    /**
     * Developer endpoint: tạo server record để test DS từ Unity Editor.
     *
     * Cách dùng:
     *   POST /api/admin/ds/dev-allocate
     *   Header: X-Admin-Secret: <ds.admin.secret>
     *   Body: { "region": "vn", "mapId": "map_01" }  (cả 2 optional)
     *
     * Response trả về serverId + devSecret (plain).
     * Paste 2 giá trị này vào Inspector của ServerBootstrap trong 00_DS_Boot.unity:
     *   fallbackServerId     = <serverId>
     *   fallbackServerSecret = <devSecret>
     * Sau đó Play → DS sẽ register thành công.
     *
     * ds.docker.enabled phải = false để không thực sự spin container.
     */
    @PostMapping("/dev-allocate")
    public ApiResponse<Map<String, Object>> devAllocate(
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader("X-Admin-Secret") String secret) {

        if (!adminSecret.equals(secret)) {
            return ApiResponse.error("Unauthorized", "FORBIDDEN");
        }

        String region = body != null ? body.getOrDefault("region", "vn") : "vn";
        String mapId  = body != null ? body.getOrDefault("mapId",  "map_01") : "map_01";

        log.info("[AdminDS] Dev allocate: region={} mapId={}", region, mapId);

        var allocation = dsService.allocateServer(region, mapId);

        // devSecret is only populated in local dev mode (ds.docker.enabled=false).
        // In production the container self-registers — devSecret is null but allocation still succeeded.
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("serverId",  allocation.getServerId());
        result.put("port",      allocation.getPort());
        result.put("status",    allocation.getStatus());
        result.put("mapId",     mapId);
        if (allocation.getDevSecret() != null) {
            result.put("devSecret", allocation.getDevSecret());
            result.put("hint", "Paste serverId+devSecret into ServerBootstrap Inspector, then press Play in Unity Editor");
        } else {
            result.put("devSecret", null);
            result.put("hint", "Production mode: container is starting and will self-register via /ds/register");
        }
        return ApiResponse.ok(result);
    }

    /**
     * List tất cả DS containers đang hoạt động (dành cho Dashboard).
     * GET /api/admin/ds/servers
     * GET /api/admin/ds/servers?status=in_game   (filter theo status)
     */
    @GetMapping("/servers")
    public ApiResponse<List<DedicatedServer>> listServers(
            @RequestHeader("X-Admin-Secret") String secret,
            @RequestParam(required = false) String status) {

        if (!adminSecret.equals(secret)) {
            return ApiResponse.error("Unauthorized", "FORBIDDEN");
        }

        List<DedicatedServer> servers;
        if (status != null && !status.isBlank()) {
            servers = dsRepo.findAll().stream()
                    .filter(s -> status.equalsIgnoreCase(s.getStatus()))
                    .toList();
        } else {
            // Mặc định: trả tất cả trừ stopped
            servers = dsRepo.findAll().stream()
                    .filter(s -> !"stopped".equals(s.getStatus()))
                    .toList();
        }

        // Xóa serverSecretHash trước khi trả về
        servers.forEach(s -> s.setServerSecretHash("[redacted]"));
        return ApiResponse.ok(servers);
    }

    /**
     * Smoke-test endpoint: tạo DS record với secret đã biết, KHÔNG spin Docker container.
     * Dùng để CI/CD smoke test có thể gọi /ds/register, /ds/heartbeat, /ds/game-ready
     * với secret hợp lệ mà không cần container thật.
     * DS record được tạo với status="test" và bị xóa sau khi smoke test xong
     * (hoặc bị cleanup scheduler dọn sau 10 phút không heartbeat).
     *
     * POST /api/admin/ds/test-alloc
     * Header: X-Admin-Secret: <adminSecret>
     * Response: { serverId, devSecret, port, status }
     */
    @PostMapping("/test-alloc")
    public ApiResponse<Map<String, Object>> testAlloc(
            @RequestHeader("X-Admin-Secret") String secret) {

        if (!adminSecret.equals(secret)) {
            return ApiResponse.error("Unauthorized", "FORBIDDEN");
        }

        Map<String, Object> result = dsService.allocateTestServer();
        return ApiResponse.ok(result);
    }

    /**
     * Lấy logs của một DS container.
     * GET /api/admin/ds/logs/{containerId}?tail=200
     */
    @GetMapping("/logs/{containerId}")
    public ApiResponse<Map<String, String>> containerLogs(
            @PathVariable String containerId,
            @RequestParam(defaultValue = "200") int tail,
            @RequestHeader("X-Admin-Secret") String secret) {

        if (!adminSecret.equals(secret)) {
            return ApiResponse.error("Unauthorized", "FORBIDDEN");
        }

        // Validate containerId — chỉ cho phép hex chars và dashes (container ID hoặc name)
        if (!containerId.matches("[a-zA-Z0-9_\\-]+")) {
            return ApiResponse.error("Invalid containerId", "BAD_REQUEST");
        }

        String logs = dockerManager.getContainerLogs(containerId, Math.min(tail, 1000));
        return ApiResponse.ok(Map.of("containerId", containerId, "logs", logs));
    }

    /**
     * Trả về image ref hiện tại backend đang dùng để spawn DS containers.
     * GET /api/admin/ds/current-image
     */
    @GetMapping("/current-image")
    public ApiResponse<Map<String, String>> currentImage(
            @RequestHeader("X-Admin-Secret") String secret) {

        if (!adminSecret.equals(secret)) {
            return ApiResponse.error("Unauthorized", "FORBIDDEN");
        }

        return ApiResponse.ok(Map.of("imageRef", dockerManager.getCurrentImageRef()));
    }

    /**
     * Force-terminate một DS instance cụ thể — dừng container, notify players, mark stopped.
     * Dùng từ Dashboard: nút "Terminate" bên cạnh mỗi server instance.
     *
     * POST /api/admin/ds/terminate/{serverId}
     * Header: X-Admin-Secret: <secret>
     * Body (optional): { "reason": "Admin maintenance" }
     */
    @PostMapping("/terminate/{serverId}")
    public ApiResponse<Map<String, Object>> terminateServer(
            @PathVariable String serverId,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader("X-Admin-Secret") String secret) {

        if (!adminSecret.equals(secret)) {
            return ApiResponse.error("Unauthorized", "FORBIDDEN");
        }
        if (!serverId.matches("[a-zA-Z0-9\\-]+")) {
            return ApiResponse.error("Invalid serverId", "BAD_REQUEST");
        }

        String reason = body != null ? body.getOrDefault("reason", null) : null;
        try {
            dsService.terminateServerByAdmin(serverId, reason);
            return ApiResponse.ok(Map.of("serverId", serverId, "status", "stopped"));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage(), "NOT_FOUND");
        }
    }

    /**
     * Khởi động một DS instance mới từ Dashboard (không qua matchmaking flow).
     * Useful để pre-warm servers hoặc test DS boot.
     *
     * POST /api/admin/ds/allocate
     * Header: X-Admin-Secret: <secret>
     * Body: { "region": "vn", "mapId": "map_01" }
     */
    @PostMapping("/allocate")
    public ApiResponse<Map<String, Object>> allocateServer(
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader("X-Admin-Secret") String secret) {

        if (!adminSecret.equals(secret)) {
            return ApiResponse.error("Unauthorized", "FORBIDDEN");
        }

        String region = body != null ? body.getOrDefault("region", "vn") : "vn";
        String mapId  = body != null ? body.getOrDefault("mapId",  "map_01") : "map_01";

        log.info("[AdminDS] Dashboard allocate: region={} mapId={}", region, mapId);

        try {
            var allocation = dsService.allocateServer(region, mapId);
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("serverId", allocation.getServerId());
            result.put("port",     allocation.getPort());
            result.put("status",   allocation.getStatus());
            result.put("region",   region);
            result.put("mapId",    mapId);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            log.error("[AdminDS] allocate failed: {}", e.getMessage());
            return ApiResponse.error("Allocate failed: " + e.getMessage(), "SERVER_ERROR");
        }
    }

    /**
     * Force-stop tất cả DS containers đang chạy + mark stopped trong DB.
     * Dùng sau khi deploy image mới để đảm bảo lần allocate tiếp theo dùng image mới.
     * POST /api/admin/ds/cleanup-all
     */
    @PostMapping("/cleanup-all")
    public ApiResponse<Map<String, Object>> cleanupAll(
            @RequestHeader("X-Admin-Secret") String secret) {

        if (!adminSecret.equals(secret)) {
            return ApiResponse.error("Unauthorized", "FORBIDDEN");
        }

        List<DedicatedServer> active = dsRepo.findAll().stream()
                .filter(s -> !"stopped".equals(s.getStatus()))
                .toList();

        List<String> removed  = new ArrayList<>();
        List<String> failed   = new ArrayList<>();

        for (DedicatedServer server : active) {
            try {
                dockerManager.stopContainer(server.getDockerContainerId());
                server.setStatus("stopped");
                server.setStoppedAt(LocalDateTime.now());
                dsRepo.save(server);
                removed.add(server.getServerId());
                log.info("[AdminDS] cleanup-all: stopped container={} serverId={}",
                        server.getDockerContainerId(), server.getServerId());
            } catch (Exception e) {
                log.error("[AdminDS] cleanup-all: failed to stop serverId={}: {}",
                        server.getServerId(), e.getMessage());
                failed.add(server.getServerId());
            }
        }

        return ApiResponse.ok(Map.of(
                "removed", removed,
                "failed",  failed,
                "total",   active.size()
        ));
    }

    // ── GET /api/admin/ds/match-history ────────────────────────────────────────
    /**
     * Match history for dashboard (admin). Accepts X-Admin-Secret.
     * GET /api/admin/ds/match-history?page=0&size=20&mode=&status=
     */
    @GetMapping("/match-history")
    public ApiResponse<Map<String, Object>> matchHistory(
            @RequestHeader("X-Admin-Secret") String secret,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)     String mode,
            @RequestParam(required = false)     String status) {

        if (!adminSecret.equals(secret)) {
            return ApiResponse.error("Unauthorized", "FORBIDDEN");
        }
        page = Math.max(0, page);
        size = Math.min(Math.max(1, size), 100);

        Page<Match> pageResult = matchRepo.findFiltered(
                status, mode,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<Map<String, Object>> rows = pageResult.getContent().stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("matchId",       m.getMatchId());
            row.put("roomId",        m.getRoomId());
            row.put("status",        m.getStatus());
            row.put("gameMode",      m.getGameMode());
            row.put("winnerTeamId",  m.getWinnerTeamId());
            row.put("endReason",     m.getEndReason());
            row.put("startedAt",     m.getStartedAt());
            row.put("finishedAt",    m.getFinishedAt());
            row.put("createdAt",     m.getCreatedAt());
            row.put("durationSeconds", m.getDurationSeconds());
            return row;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page",    page);
        result.put("size",    size);
        result.put("total",   pageResult.getTotalElements());
        result.put("matches", rows);
        return ApiResponse.ok(result);
    }

    // ── GET /api/admin/ds/match-detail/{matchId} ──────────────────────────────
    /**
     * Full match details + per-player results for dashboard.
     * GET /api/admin/ds/match-detail/{matchId}
     */
    @GetMapping("/match-detail/{matchId}")
    public ApiResponse<Map<String, Object>> matchDetail(
            @RequestHeader("X-Admin-Secret") String secret,
            @PathVariable String matchId) {

        if (!adminSecret.equals(secret)) {
            return ApiResponse.error("Unauthorized", "FORBIDDEN");
        }
        if (!matchId.matches("[a-zA-Z0-9\\-]{8,50}")) {
            return ApiResponse.error("Invalid matchId", "BAD_REQUEST");
        }

        Match match = matchRepo.findByMatchId(matchId).orElse(null);
        if (match == null) {
            return ApiResponse.error("Match not found: " + matchId, "NOT_FOUND");
        }

        List<MatchPlayerResult> playerResults = matchPlayerResultRepo.findByMatchId(matchId);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("matchId",         match.getMatchId());
        details.put("roomId",          match.getRoomId());
        details.put("status",          match.getStatus());
        details.put("gameMode",        match.getGameMode());
        details.put("winnerTeamId",    match.getWinnerTeamId());
        details.put("endReason",       match.getEndReason());
        details.put("startedAt",       match.getStartedAt());
        details.put("finishedAt",      match.getFinishedAt());
        details.put("createdAt",       match.getCreatedAt());
        details.put("durationSeconds", match.getDurationSeconds());

        List<Map<String, Object>> players = playerResults.stream().map(p -> {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("userId",      p.getUserId());
            pm.put("displayName", p.getDisplayName());
            pm.put("teamId",      p.getTeamId());
            pm.put("kills",       p.getKills());
            pm.put("deaths",      p.getDeaths());
            pm.put("score",       p.getScore());
            pm.put("eloBefore",   p.getEloBefore());
            pm.put("eloAfter",    p.getEloAfter());
            pm.put("eloChange",   p.getEloChange());
            pm.put("placement",   p.getPlacement());
            return pm;
        }).toList();
        details.put("players", players);

        return ApiResponse.ok(details);
    }

    // ── GET /api/admin/ds/system-metrics ──────────────────────────────────────
    /**
     * Aggregated system metrics for the admin dashboard.
     * Pulls data directly from Micrometer MeterRegistry — no raw actuator exposure needed.
     *
     * GET /api/admin/ds/system-metrics
     * Header: X-Admin-Secret: <secret>
     *
     * Returns:
     *   http  → totalRequests, successRate, errorRate4xx, errorRate5xx,
     *           avgLatencyMs, p95LatencyMs, p99LatencyMs, throughputPerMin
     *   jvm   → heapUsedMb, heapMaxMb, heapPercent, nonHeapUsedMb
     *   db    → activeConnections, idleConnections, pendingConnections, maxConnections
     *   system → uptimeSeconds, availableProcessors, systemCpuLoad
     *   topEndpoints → list of { uri, method, count, avgMs, maxMs, errors }
     */
    @GetMapping("/system-metrics")
    public ApiResponse<Map<String, Object>> systemMetrics(
            @RequestHeader("X-Admin-Secret") String secret) {

        if (!adminSecret.equals(secret)) {
            return ApiResponse.error("Unauthorized", "FORBIDDEN");
        }

        Map<String, Object> result = new LinkedHashMap<>();

        // ── HTTP metrics ──────────────────────────────────────────────────────
        try {
            Map<String, Object> http = new LinkedHashMap<>();
            long totalCount   = 0;
            long errorCount4x = 0;
            long errorCount5x = 0;
            double totalMs    = 0;
            double maxMs      = 0;
            double p95Ms      = 0;
            double p99Ms      = 0;

            List<Map<String, Object>> topEndpoints = new ArrayList<>();

            var timers = meterRegistry.find("http.server.requests").timers();
            for (Timer t : timers) {
                long count = t.count();
                if (count == 0) continue;

                String uri    = t.getId().getTag("uri");
                String method = t.getId().getTag("method");
                String status = t.getId().getTag("status");

                double tAvgMs = t.mean(TimeUnit.MILLISECONDS);
                double tMaxMs = t.max(TimeUnit.MILLISECONDS);

                totalCount += count;
                totalMs    += tAvgMs * count;
                maxMs       = Math.max(maxMs, tMaxMs);

                if (status != null && status.startsWith("4")) errorCount4x += count;
                if (status != null && status.startsWith("5")) errorCount5x += count;

                if (uri != null && !uri.contains("actuator")) {
                    Map<String, Object> ep = new LinkedHashMap<>();
                    ep.put("uri",    uri);
                    ep.put("method", method);
                    ep.put("count",  count);
                    ep.put("avgMs",  Math.round(tAvgMs * 10.0) / 10.0);
                    ep.put("maxMs",  Math.round(tMaxMs * 10.0) / 10.0);
                    ep.put("errors", status != null && (status.startsWith("4") || status.startsWith("5")) ? count : 0);
                    topEndpoints.add(ep);
                }

                // p95 / p99 via percentile snapshot
                var snapshot = t.takeSnapshot();
                if (snapshot != null) {
                    for (var pv : snapshot.percentileValues()) {
                        if (Math.abs(pv.percentile() - 0.95) < 0.001) p95Ms = Math.max(p95Ms, pv.value(TimeUnit.MILLISECONDS));
                        if (Math.abs(pv.percentile() - 0.99) < 0.001) p99Ms = Math.max(p99Ms, pv.value(TimeUnit.MILLISECONDS));
                    }
                }
            }

            double avgLatencyMs = totalCount > 0 ? totalMs / totalCount : 0;
            double successRate  = totalCount > 0 ? (double)(totalCount - errorCount4x - errorCount5x) / totalCount * 100 : 100;

            // Approximate throughput: total requests / uptime minutes
            long uptimeSec = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
            double uptimeMin = Math.max(uptimeSec / 60.0, 1.0);
            double throughputPerMin = totalCount / uptimeMin;

            http.put("totalRequests",    totalCount);
            http.put("successRate",      Math.round(successRate * 10.0) / 10.0);
            http.put("errorRate4xx",     totalCount > 0 ? Math.round((double) errorCount4x / totalCount * 1000.0) / 10.0 : 0);
            http.put("errorRate5xx",     totalCount > 0 ? Math.round((double) errorCount5x / totalCount * 1000.0) / 10.0 : 0);
            http.put("avgLatencyMs",     Math.round(avgLatencyMs * 10.0) / 10.0);
            http.put("p95LatencyMs",     Math.round(p95Ms * 10.0) / 10.0);
            http.put("p99LatencyMs",     Math.round(p99Ms * 10.0) / 10.0);
            http.put("maxLatencyMs",     Math.round(maxMs * 10.0) / 10.0);
            http.put("throughputPerMin", Math.round(throughputPerMin * 10.0) / 10.0);

            // Top 10 endpoints by count
            topEndpoints.sort((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")));
            http.put("topEndpoints", topEndpoints.stream().limit(10).toList());

            result.put("http", http);
        } catch (Exception e) {
            result.put("http", Map.of("error", e.getMessage()));
        }

        // ── JVM memory ───────────────────────────────────────────────────────
        try {
            Map<String, Object> jvm = new LinkedHashMap<>();
            var heapUsed = meterRegistry.find("jvm.memory.used").tag("area", "heap").gauge();
            var heapMax  = meterRegistry.find("jvm.memory.max").tag("area", "heap").gauge();
            var nonHeap  = meterRegistry.find("jvm.memory.used").tag("area", "nonheap").gauge();

            double heapUsedMb  = heapUsed != null ? heapUsed.value()  / 1_048_576.0 : -1;
            double heapMaxMb   = heapMax  != null ? heapMax.value()   / 1_048_576.0 : -1;
            double nonHeapUsedMb = nonHeap != null ? nonHeap.value()  / 1_048_576.0 : -1;
            double heapPercent = heapMaxMb > 0 ? heapUsedMb / heapMaxMb * 100 : -1;

            jvm.put("heapUsedMb",   Math.round(heapUsedMb   * 10.0) / 10.0);
            jvm.put("heapMaxMb",    Math.round(heapMaxMb    * 10.0) / 10.0);
            jvm.put("heapPercent",  Math.round(heapPercent  * 10.0) / 10.0);
            jvm.put("nonHeapUsedMb", Math.round(nonHeapUsedMb * 10.0) / 10.0);
            result.put("jvm", jvm);
        } catch (Exception e) {
            result.put("jvm", Map.of("error", e.getMessage()));
        }

        // ── DB Connection Pool (HikariCP) ─────────────────────────────────────
        try {
            Map<String, Object> db = new LinkedHashMap<>();
            var active  = meterRegistry.find("hikaricp.connections.active").gauge();
            var idle    = meterRegistry.find("hikaricp.connections.idle").gauge();
            var pending = meterRegistry.find("hikaricp.connections.pending").gauge();
            var maxConn = meterRegistry.find("hikaricp.connections.max").gauge();

            db.put("activeConnections",  active  != null ? (int) active.value()  : -1);
            db.put("idleConnections",    idle    != null ? (int) idle.value()    : -1);
            db.put("pendingConnections", pending != null ? (int) pending.value() : -1);
            db.put("maxConnections",     maxConn != null ? (int) maxConn.value() : -1);
            result.put("db", db);
        } catch (Exception e) {
            result.put("db", Map.of("error", e.getMessage()));
        }

        // ── System ────────────────────────────────────────────────────────────
        try {
            Map<String, Object> sys = new LinkedHashMap<>();
            long uptimeSec = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
            int cpus = Runtime.getRuntime().availableProcessors();

            var cpuLoad = meterRegistry.find("system.cpu.usage").gauge();
            double cpuPct = cpuLoad != null ? Math.round(cpuLoad.value() * 1000.0) / 10.0 : -1;

            sys.put("uptimeSeconds",       uptimeSec);
            sys.put("availableProcessors", cpus);
            sys.put("systemCpuPercent",    cpuPct);
            result.put("system", sys);
        } catch (Exception e) {
            result.put("system", Map.of("error", e.getMessage()));
        }

        // ── Active DS count ───────────────────────────────────────────────────
        try {
            long activeDs = dsRepo.findAll().stream()
                    .filter(s -> !"stopped".equals(s.getStatus()))
                    .count();
            result.put("activeDedicatedServers", activeDs);
        } catch (Exception e) {
            result.put("activeDedicatedServers", -1);
        }

        result.put("collectedAt", java.time.Instant.now().toString());
        return ApiResponse.ok(result);
    }
}
