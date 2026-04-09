package com.nighthunt.map.service;

import com.fasterxml.jackson.core.type.TypeReference;
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

    // ── Conversion ────────────────────────────────────────────────────────────

    private GameMapDTO toDTO(GameMap map) {
        List<String> modes = parseSupportedModes(map.getSupportedModesJson());
        return GameMapDTO.builder()
                .mapId(map.getMapId())
                .displayName(map.getDisplayName())
                .description(map.getDescription())
                .sceneName(map.getSceneName())
                .supportedModes(modes)
                .isLocked(map.isLocked())
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
}
