package com.nighthunt.config.gameconfig;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Runtime-tunable key/value configuration stored in the {@code game_config} table.
 * Admin can UPDATE any key at runtime via dashboard without redeploying the server.
 */
@Entity
@Table(name = "game_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameConfig {

    @Id
    @Column(name = "config_key", length = 80)
    private String configKey;

    @Column(name = "config_value", nullable = false, length = 255)
    private String configValue;

    /**
     * Hint for UI rendering: STRING, INT, FLOAT, BOOL, JSON.
     */
    @Column(name = "value_type", nullable = false, length = 20)
    @Builder.Default
    private String valueType = "STRING";

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PreUpdate
    @PrePersist
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
