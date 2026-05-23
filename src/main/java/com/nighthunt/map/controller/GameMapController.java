package com.nighthunt.map.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.nighthunt.common.ApiResponse;
import com.nighthunt.map.dto.GameMapDTO;
import com.nighthunt.map.service.GameMapService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * GET /api/maps            — all active maps for the client (MapConfig).
 * GET /api/maps/available  — only playable (unlocked) maps.
 * GET /api/maps/{mapId}/zone-config — SafeZoneMatchConfig JSON for a specific map.
 *                                     Called by ServerBootstrap on DS startup.
 * No authentication required (public endpoints).
 */
@RestController
@RequestMapping("/maps")
@RequiredArgsConstructor
public class GameMapController {

    private final GameMapService mapService;

    /** All active maps (including locked ones so client can show "Coming Soon"). */
    @GetMapping
    public ApiResponse<List<GameMapDTO>> getAllMaps() {
        return ApiResponse.ok(mapService.getAllMaps());
    }

    /** Only unlocked, playable maps. */
    @GetMapping("/available")
    public ApiResponse<List<GameMapDTO>> getAvailableMaps() {
        return ApiResponse.ok(mapService.getAvailableMaps());
    }

    /**
     * Returns the SafeZoneMatchConfig JSON for a specific map.
     * The DS calls this on boot via ServerBootstrap.FetchZoneConfig().
     * Returns 204 No Content if map not found or zone config not configured
     * (DS falls back to SafeZoneMatchConfig.Default() on any non-200 or empty response).
     *
     * NOTE: Returns raw JSON — NOT wrapped in ApiResponse — so Unity's JsonUtility
     * can deserialize it directly as SafeZoneMatchConfig.
     */
    @GetMapping("/{mapId}/zone-config")
    public ResponseEntity<JsonNode> getZoneConfig(@PathVariable String mapId) {
        JsonNode config = mapService.getZoneConfig(mapId);
        if (config == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(config);
    }
}
