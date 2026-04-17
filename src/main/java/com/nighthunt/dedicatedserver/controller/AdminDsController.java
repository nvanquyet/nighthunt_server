package com.nighthunt.dedicatedserver.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.dedicatedserver.entity.DedicatedServer;
import com.nighthunt.dedicatedserver.repository.DedicatedServerRepository;
import com.nighthunt.dedicatedserver.service.DedicatedServerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    @Value("${ds.admin.secret:change-me-in-production}")
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
}
