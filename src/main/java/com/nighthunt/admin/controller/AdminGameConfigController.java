package com.nighthunt.admin.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.nighthunt.common.ApiResponse;
import com.nighthunt.config.gameconfig.GameConfig;
import com.nighthunt.config.gameconfig.RuntimeConfigService;
import com.nighthunt.gamemode.dto.GameModeDTO;
import com.nighthunt.gamemode.service.GameModeService;
import com.nighthunt.map.dto.GameMapDTO;
import com.nighthunt.map.service.GameMapService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Admin REST API for runtime game configuration.
 *
 * All endpoints require {@code X-Admin-Secret} header.
 * Base path: /api/admin/config
 *
 * ── Game Modes ──────────────────────────────────────
 *   GET  /admin/config/modes              → all modes (incl. dev/inactive)
 *   PATCH /admin/config/modes/{modeKey}   → toggle status / matchmaking / devMode
 *
 * ── Maps ────────────────────────────────────────────
 *   GET  /admin/config/maps               → all maps (incl. inactive)
 *   PATCH /admin/config/maps/{mapId}      → toggle active/locked, edit displayName
 *   POST  /admin/config/maps              → add new map entry
 *
 * ── Runtime Config ──────────────────────────────────
 *   GET  /admin/config/runtime            → all game_config key/value rows
 *   PATCH /admin/config/runtime/{key}     → update a single config value
 *
 * Dashboard calls these on every tab to hot-reload changes
 * without any server restart or client update.
 */
@RestController
@RequestMapping("/admin/config")
@RequiredArgsConstructor
public class AdminGameConfigController {

    @Value("${ADMIN_SECRET:change-me-in-production}")
    private String adminSecret;

    private final GameModeService     gameModeService;
    private final GameMapService      gameMapService;
    private final RuntimeConfigService runtimeConfigService;

    // ── Security ──────────────────────────────────────────────────────────────

    private void checkSecret(String secret) {
        if (secret == null || !adminSecret.equals(secret))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid admin secret");
    }

    // ════════════════════════════════════════════════════════════════════════
    // GAME MODES
    // ════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/admin/config/modes
     * Returns ALL modes (including dev and disabled) for the admin panel.
     * Contrast with GET /api/game-modes which excludes isDevMode=true and DISABLED.
     */
    @GetMapping("/modes")
    public ApiResponse<List<GameModeDTO>> getModes(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        checkSecret(secret);
        return ApiResponse.ok(gameModeService.getAllGameModes());
    }

    /**
     * PATCH /api/admin/config/modes/{modeKey}
     * Partial update — only provided fields are changed.
     *
     * Body example:
     * {@code { "modeStatus": "AVAILABLE", "matchmakingEnabled": true, "isDevMode": false }}
     *
     * Supported fields: modeStatus, matchmakingEnabled, isDevMode, isActive,
     *                   displayOrder, displayName, description
     */
    @PatchMapping("/modes/{modeKey}")
    public ApiResponse<GameModeDTO> patchMode(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @PathVariable String modeKey,
            @RequestBody GameModeService.PatchGameModeRequest body) {
        checkSecret(secret);
        return ApiResponse.ok(gameModeService.patchMode(modeKey, body));
    }

    // ════════════════════════════════════════════════════════════════════════
    // MAPS
    // ════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/admin/config/maps
     * Returns ALL maps including inactive ones.
     * Client-facing GET /api/maps only returns isActive=true.
     */
    @GetMapping("/maps")
    public ApiResponse<List<GameMapDTO>> getMaps(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        checkSecret(secret);
        return ApiResponse.ok(gameMapService.getAllMapsAdmin());
    }

    /**
     * PATCH /api/admin/config/maps/{mapId}
     * Toggle isActive, isLocked, change displayName, description, displayOrder,
     * or update supportedModes array.
     *
     * Body example:
     * {@code { "isActive": true, "isLocked": false, "supportedModes": ["2v2","3v3"] }}
     *
     * NOTE: sceneName is intentionally NOT patchable at runtime because it must match
     * a pre-baked SceneId in the client build. To change a scene, update the migration.
     */
    @PatchMapping("/maps/{mapId}")
    public ApiResponse<GameMapDTO> patchMap(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @PathVariable String mapId,
            @RequestBody GameMapService.PatchGameMapRequest body) {
        checkSecret(secret);
        return ApiResponse.ok(gameMapService.patchMap(mapId, body));
    }

    /**
     * POST /api/admin/config/maps
     * Add a brand-new map entry at runtime.
     *
     * IMPORTANT: {@code sceneName} must match a pre-baked entry in the client's
     * {@code SceneId} enum (e.g. "GameMap_03"). The scene file must already exist
     * in the client build. Client resolves it via {@code Enum.TryParse(sceneName)}.
     *
     * Workflow for adding new maps without client update:
     *  1. Pre-bake scene files (GameMap_03…GameMap_10) in every client build
     *  2. Add SceneId enum entries (GameMap_03=102 … GameMap_10=109)
     *  3. At runtime, POST this endpoint from dashboard → map immediately available
     */
    @PostMapping("/maps")
    public ApiResponse<GameMapDTO> addMap(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @RequestBody GameMapService.AddGameMapRequest body) {
        checkSecret(secret);
        return ApiResponse.ok(gameMapService.addMap(body));
    }

    /**
     * PATCH /api/admin/config/maps/{mapId}/zone
     * Replace the SafeZoneMatchConfig JSON for a specific map.
     *
     * Body: full SafeZoneMatchConfig JSON object.
     * Example:
     * <pre>
     * {
     *   "initialRadius": 400.0,
     *   "finalZoneMinRadius": 25.0,
     *   "centerMode": "PureRandom",
     *   "phases": [
     *     {"zoneIndex":0,"startRadius":400,"endRadius":200,"waitBeforeShrink":60,"shrinkDuration":90,...},
     *     ...
     *   ]
     * }
     * </pre>
     * DS reads this via GET /api/maps/{mapId}/zone-config on boot.
     */
    @PatchMapping("/maps/{mapId}/zone")
    public ApiResponse<GameMapDTO> setZoneConfig(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @PathVariable String mapId,
            @RequestBody JsonNode body) {
        checkSecret(secret);
        return ApiResponse.ok(gameMapService.setZoneConfig(mapId, body));
    }

    // ════════════════════════════════════════════════════════════════════════
    // RUNTIME CONFIG (game_config table)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/admin/config/runtime
     * Returns all key/value config entries for dashboard display and inline editing.
     */
    @GetMapping("/runtime")
    public ApiResponse<List<GameConfig>> getRuntimeConfig(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret) {
        checkSecret(secret);
        return ApiResponse.ok(runtimeConfigService.getAll());
    }

    /**
     * PATCH /api/admin/config/runtime/{key}
     * Update a single config value at runtime.
     *
     * Body: {@code { "value": "200" }}
     *
     * Changes take effect immediately — no server restart needed for config-driven values
     * (MatchmakingQueueService reads game_config each tick via RuntimeConfigService).
     */
    @PatchMapping("/runtime/{key}")
    public ApiResponse<GameConfig> setRuntimeConfig(
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @PathVariable String key,
            @RequestBody Map<String, String> body) {
        checkSecret(secret);
        String newValue = body.get("value");
        if (newValue == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body must contain 'value' field");
        return ApiResponse.ok(runtimeConfigService.setValue(key, newValue));
    }
}
