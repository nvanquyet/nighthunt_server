package com.nighthunt.dashboard.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Dedicated Server session info for Dashboard display.
 */
@Data
@Builder
public class DsSessionDTO {
    private String        serverId;
    private String        dockerContainerId;
    private String        ip;
    private Integer       port;
    /** starting | ready | in_game | stopped */
    private String        status;
    private String        region;
    private String        mapId;
    private Integer       currentPlayers;
    private Integer       maxPlayers;
    private String        imageTag;
    private LocalDateTime startedAt;
    private LocalDateTime lastHeartbeatAt;
}
