package com.nighthunt.match.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "headless_servers", indexes = {
        @Index(name = "idx_server_id", columnList = "serverId", unique = true),
        @Index(name = "idx_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HeadlessServer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "server_id", nullable = false, unique = true, length = 50)
    private String serverId; // unique identifier

    @Column(name = "server_ip", nullable = false, length = 50)
    private String serverIp;

    @Column(name = "server_port", nullable = false)
    private Integer serverPort;

    @Column(name = "api_base_url", nullable = false, length = 200)
    private String apiBaseUrl; // REST API endpoint for internal calls

    @Column(nullable = false, length = 20)
    private String status; // ACTIVE, INACTIVE, MAINTENANCE

    @Column(name = "max_matches", nullable = false)
    private Integer maxMatches;

    @Column(name = "current_matches", nullable = false)
    private Integer currentMatches;

    @Column(name = "cpu_usage")
    private Double cpuUsage;

    @Column(name = "memory_usage")
    private Double memoryUsage;

    @Column(name = "last_heartbeat")
    private LocalDateTime lastHeartbeat;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @Column(name = "version", length = 50)
    private String version; // Build version (e.g., Ver0.0.1)

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (currentMatches == null) {
            currentMatches = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

