package com.nighthunt.config.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "headless_server_config", indexes = {
        @Index(name = "idx_config_key", columnList = "configKey", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HeadlessServerConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, unique = true, length = 100)
    private String configKey; // e.g., "build.path", "default.version"

    @Column(name = "config_value", nullable = false, length = 500)
    private String configValue; // e.g., "../Build/Server", "Ver0.0.1"

    @Column(name = "description", length = 500)
    private String description;

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

