package com.nighthunt.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Room Detail DTO for Dashboard
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomDetailDTO {
    private Long roomId;
    private String roomCode;
    private String status; // WAITING, IN_GAME, CLOSED
    private String mode; // 2v2, 4v4, etc.
    private Boolean isPublic;
    private Boolean isLocked;
    private Long ownerId;
    private String ownerUsername;
    private LocalDateTime createdAt;
    private LocalDateTime lastActivity;
    
    // Player information
    private Integer playerCount;
    private Integer maxPlayers;
    private List<PlayerDetailDTO> players;
    
    // WebSocket connection status
    private Integer activeWebSocketConnections;
}

