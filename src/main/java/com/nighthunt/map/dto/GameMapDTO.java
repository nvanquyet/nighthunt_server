package com.nighthunt.map.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO sent to client for map configuration.
 * Client uses this to populate MapConfig at runtime.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameMapDTO {

    /** Matches MapEntry.mapId on client (e.g. "map_01"). */
    private String mapId;

    private String displayName;
    private String description;

    /** Unity scene file name (without .unity). Client maps this to SceneId enum. */
    private String sceneName;

    /** Null = all modes. Non-null = array of modeKey strings the map supports. */
    private List<String> supportedModes;

    /**
     * Raw SafeZoneMatchConfig JSON blob. Null = client uses Default().
     * DS fetches this via GET /api/maps/{mapId}/zone-config.
     */
    private Object zoneConfig;

    /**
     * Total-player counts this map supports, e.g. [4, 6, 10].
     * Null = no player-count filter.
     */
    private List<Integer> supportedPlayerCounts;

    @JsonProperty("isLocked")
    private boolean isLocked;
    private int     displayOrder;
}
