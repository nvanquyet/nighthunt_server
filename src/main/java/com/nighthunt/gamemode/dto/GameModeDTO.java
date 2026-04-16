package com.nighthunt.gamemode.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for game mode information.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameModeDTO {
    private Long   id;
    private String modeKey;              // 2v2, 3v3, 4v4, 5v5
    private String displayName;          // "2 vs 2", "3 vs 3"
    private String description;          // Short UI description
    private int    playersPerTeam;       // 2, 3, 4, 5
    private int    totalPlayers;         // 4, 6, 8, 10
    private boolean allowFill;           // server may fill empty slots with solos
    private boolean matchmakingEnabled;  // ranked queue open for this mode
    private int    minElo;               // minimum ELO to queue
    private int    maxElo;               // max ELO cap
    private String modeStatus;           // AVAILABLE, LOCKED, COMING_SOON, DISABLED
    private int    displayOrder;
    @JsonProperty("isActive")
    private boolean isActive;
    @JsonProperty("isDevMode")
    private boolean isDevMode;            // true = dev/test mode, excluded from client-facing API
}
