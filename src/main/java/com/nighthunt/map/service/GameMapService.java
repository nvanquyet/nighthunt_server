package com.nighthunt.map.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.map.dto.GameMapDTO;
import com.nighthunt.map.entity.GameMap;
import com.nighthunt.map.repository.GameMapRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameMapService {

    private final GameMapRepository mapRepository;
    private final ObjectMapper      objectMapper;

    // ── Public API ────────────────────────────────────────────────────────────

    /** All active maps (returned to client via GET /api/maps). */
    @Transactional(readOnly = true)
    public List<GameMapDTO> getAllMaps() {
        return mapRepository.findByIsActiveTrueOrderByDisplayOrderAsc()
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    /** Only unlocked, active maps (used in matchmaking queue validation). */
    @Transactional(readOnly = true)
    public List<GameMapDTO> getAvailableMaps() {
        return mapRepository.findByIsActiveTrueAndIsLockedFalseOrderByDisplayOrderAsc()
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    /** Validate a mapId exists and is active. Returns true if valid. */
    @Transactional(readOnly = true)
    public boolean isMapValid(String mapId) {
        if (mapId == null || mapId.isBlank()) return true; // null = any map, always valid
        return mapRepository.findByMapId(mapId)
                .map(m -> m.isActive() && !m.isLocked())
                .orElse(false);
    }

    /** Resolve a map's scene name from its mapId. Used by DS allocation. */
    @Transactional(readOnly = true)
    public String getSceneName(String mapId) {
        if (mapId == null || mapId.isBlank()) return "GameMap_01"; // default
        return mapRepository.findByMapId(mapId)
                .map(GameMap::getSceneName)
                .orElse("GameMap_01");
    }

    /**
     * Returns the raw zone_config JSON node for the given mapId.
     * Returns null if map not found or zone_config is not set.
     * Client (ServerBootstrap) parses this into SafeZoneMatchConfig.
     */
    @Transactional(readOnly = true)
    public JsonNode getZoneConfig(String mapId) {
        return mapRepository.findByMapId(mapId)
                .map(m -> parseJsonNode(m.getZoneConfigJson()))
                .orElse(null);
    }

    /**
     * Admin: update the zone_config JSON blob for a specific map.
     * The payload must be a valid SafeZoneMatchConfig JSON object.
     */
    @Transactional
    public GameMapDTO setZoneConfig(String mapId, JsonNode zoneConfigNode) {
        GameMap map = mapRepository.findByMapId(mapId).orElseThrow(() ->
                new BusinessException(ErrorCodes.ROOM_NOT_FOUND, "Map not found: " + mapId));
        try {
            map.setZoneConfigJson(objectMapper.writeValueAsString(zoneConfigNode));
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.DS_BAD_REQUEST, "Invalid zone config JSON");
        }
        log.info("[AdminConfig] setZoneConfig {} → {} bytes", mapId,
                map.getZoneConfigJson() != null ? map.getZoneConfigJson().length() : 0);
        return toDTO(mapRepository.save(map));
    }

    // ── Admin API ─────────────────────────────────────────────────────────────

    /** Return ALL maps (including inactive) for admin management view. */
    @Transactional(readOnly = true)
    public List<GameMapDTO> getAllMapsAdmin() {
        return mapRepository.findAll().stream().map(this::toDTO).collect(Collectors.toList());
    }

    /**
     * Patch any subset of a map's admin fields.
     * Null fields are ignored (partial update).
     */
    @Transactional
    public GameMapDTO patchMap(String mapId, PatchGameMapRequest req) {
        GameMap map = mapRepository.findByMapId(mapId).orElseThrow(() ->
                new BusinessException(ErrorCodes.ROOM_NOT_FOUND, "Map not found: " + mapId));

        if (req.isActive      != null) map.setActive(req.isActive);
        if (req.isLocked      != null) map.setLocked(req.isLocked);
        if (req.displayName   != null) map.setDisplayName(req.displayName);
        if (req.description   != null) map.setDescription(req.description);
        if (req.sceneName     != null) map.setSceneName(req.sceneName);
        if (req.displayOrder  != null) map.setDisplayOrder(req.displayOrder);
        if (req.supportedModes != null) {
            try {
                map.setSupportedModesJson(objectMapper.writeValueAsString(req.supportedModes));
            } catch (Exception e) {
                log.warn("[AdminConfig] failed to serialize supportedModes: {}", req.supportedModes);
            }
        }
        if (req.supportedPlayerCounts != null) {
            try {
                map.setSupportedPlayerCountsJson(objectMapper.writeValueAsString(req.supportedPlayerCounts));
            } catch (Exception e) {
                log.warn("[AdminConfig] failed to serialize supportedPlayerCounts: {}", req.supportedPlayerCounts);
            }
        }
        log.info("[AdminConfig] patchMap {} → {}", mapId, req);
        return toDTO(mapRepository.save(map));
    }

    /**
     * Add a brand-new map entry at runtime.
     * The sceneName must match a pre-baked SceneId in the client build.
     * Client will resolve it via Enum.TryParse on next startup refresh.
     */
    @Transactional
    public GameMapDTO addMap(AddGameMapRequest req) {
        if (mapRepository.existsByMapId(req.mapId))
            throw new BusinessException(ErrorCodes.ROOM_NOT_FOUND, "mapId already exists: " + req.mapId);

        String modesJson = null;
        if (req.supportedModes != null && !req.supportedModes.isEmpty()) {
            try {
                modesJson = objectMapper.writeValueAsString(req.supportedModes);
            } catch (Exception e) {
                log.warn("[AdminConfig] addMap: failed to serialize supportedModes");
            }
        }

        GameMap map = GameMap.builder()
                .mapId(req.mapId)
                .displayName(req.displayName)
                .description(req.description)
                .sceneName(req.sceneName)
                .supportedModesJson(modesJson)
                .isLocked(req.isLocked != null && req.isLocked)
                .isActive(req.isActive == null || req.isActive)
                .displayOrder(req.displayOrder != null ? req.displayOrder : 99)
                .build();

        log.info("[AdminConfig] addMap {}", req.mapId);
        return toDTO(mapRepository.save(map));
    }

    /** Request DTO for patchMap — all fields optional. */
    public static class PatchGameMapRequest {
        public Boolean       isActive;
        public Boolean       isLocked;
        public String        displayName;
        public String        description;
        public String        sceneName;
        public Integer       displayOrder;
        public java.util.List<String>  supportedModes;
        public java.util.List<Integer> supportedPlayerCounts;
    }

    /** Request DTO for addMap — mapId, displayName, sceneName required. */
    public static class AddGameMapRequest {
        public String  mapId;
        public String  displayName;
        public String  description;
        public String  sceneName;           // must match pre-baked SceneId enum name in client
        public java.util.List<String> supportedModes;
        public Boolean isLocked;
        public Boolean isActive;
        public Integer displayOrder;
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    private GameMapDTO toDTO(GameMap map) {
        List<String>  modes    = parseSupportedModes(map.getSupportedModesJson());
        List<Integer> counts   = parseSupportedPlayerCounts(map.getSupportedPlayerCountsJson());
        JsonNode      zoneNode = parseJsonNode(map.getZoneConfigJson());
        return GameMapDTO.builder()
                .mapId(map.getMapId())
                .displayName(map.getDisplayName())
                .description(map.getDescription())
                .sceneName(map.getSceneName())
                .supportedModes(modes)
                .zoneConfig(zoneNode)
                .supportedPlayerCounts(counts)
                .isLocked(map.isLocked())
                .isActive(map.isActive())
                .displayOrder(map.getDisplayOrder())
                .build();
    }

    private List<String> parseSupportedModes(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("[GameMapService] Failed to parse supportedModesJson: {}", json);
            return Collections.emptyList();
        }
    }

    private List<Integer> parseSupportedPlayerCounts(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<Integer>>() {});
        } catch (Exception e) {
            log.warn("[GameMapService] Failed to parse supportedPlayerCountsJson: {}", json);
            return Collections.emptyList();
        }
    }

    private JsonNode parseJsonNode(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("[GameMapService] Failed to parse JSON node: {}", json);
            return null;
        }
    }
}
