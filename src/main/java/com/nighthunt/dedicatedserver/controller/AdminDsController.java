package com.nighthunt.dedicatedserver.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.dedicatedserver.service.DedicatedServerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin-only endpoints cho CI/CD pipeline và monitoring.
 * Bảo mật bằng: X-Admin-Secret header.
 */
@RestController
@RequestMapping("/api/admin/ds")
@RequiredArgsConstructor
@Slf4j
public class AdminDsController {

    private final DedicatedServerService dsService;

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

        if (allocation.getDevSecret() == null) {
            return ApiResponse.error(
                "devSecret is null — set ds.docker.enabled=false in application.properties for local dev",
                "CONFIG_ERROR"
            );
        }

        return ApiResponse.ok(Map.of(
            "serverId",  allocation.getServerId(),
            "devSecret", allocation.getDevSecret(),
            "port",      allocation.getPort(),
            "mapId",     mapId,
            "hint",      "Paste serverId+devSecret into ServerBootstrap Inspector, then press Play in Unity Editor"
        ));
    }
}
