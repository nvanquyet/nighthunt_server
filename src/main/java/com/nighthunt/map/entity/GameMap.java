package com.nighthunt.map.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "game_maps", indexes = {
        @Index(name = "idx_maps_active", columnList = "is_active, is_locked"),
        @Index(name = "idx_maps_order",  columnList = "display_order")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique string key matching MapEntry.mapId on the client (e.g. "map_01"). */
    @Column(name = "map_id", nullable = false, unique = true, length = 50)
    private String mapId;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "description", length = 200)
    private String description;

    /** Unity scene file name without .unity extension (e.g. "GameMap_01"). */
    @Column(name = "scene_name", nullable = false, length = 80)
    private String sceneName;

    /**
     * JSON array of modeKey strings this map supports, e.g. ["2v2","3v3"].
     * NULL = supports all modes.
     */
    @Column(name = "supported_modes", columnDefinition = "JSON")
    private String supportedModesJson;

    @Column(name = "is_locked", nullable = false)
    @Builder.Default
    private boolean isLocked = false;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
