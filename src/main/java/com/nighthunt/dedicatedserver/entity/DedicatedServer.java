package com.nighthunt.dedicatedserver.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "dedicated_servers", indexes = {
        @Index(name = "idx_ds_status", columnList = "status"),
        @Index(name = "idx_ds_port",   columnList = "port"),
        @Index(name = "idx_ds_region", columnList = "region,status"),
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DedicatedServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "server_id", nullable = false, unique = true, length = 36)
    private String serverId;

    @Column(name = "docker_container_id", length = 64)
    private String dockerContainerId;

    @Column(nullable = false, length = 45)
    private String ip;

    @Column(nullable = false)
    private Integer port;

    /**
     * starting → ready → in_game → stopped
     */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String region = "vn";

    @Column(name = "current_players", nullable = false)
    @Builder.Default
    private Integer currentPlayers = 0;

    @Column(name = "max_players", nullable = false)
    @Builder.Default
    private Integer maxPlayers = 16;

    @Column(name = "image_tag", length = 100)
    private String imageTag;

    /**
     * MapEntry.mapId this instance loaded (e.g. "map_01").
     * Null = not yet assigned / legacy entry.
     */
    @Column(name = "map_id", length = 50)
    private String mapId;

    /**
     * matchId of the ranked match this DS was allocated for.
     * Used by /ds/game-ready to broadcast ds_ready to the correct players.
     */
    @Column(name = "match_id", length = 36)
    private String matchId;

    /**
     * BCrypt hash của SERVER_SECRET.
     * DS phải gửi đúng secret → hash match → request được chấp nhận.
     */
    @Column(name = "server_secret_hash", nullable = false)
    private String serverSecretHash;

    @Column(name = "last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    @Column(name = "started_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "stopped_at")
    private LocalDateTime stoppedAt;

    // ── Helpers ──────────────────────────────────────────────────────────────

    public boolean isAvailable() {
        return "ready".equals(status) && currentPlayers < maxPlayers;
    }

    public boolean isAlive() {
        return stoppedAt == null && !"stopped".equals(status);
    }
}
