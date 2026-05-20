package com.nighthunt.party.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for party matchmaking (queue as party).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartyRankedQueueRequest {
    
    /**
     * Game mode to queue for: "2v2", "3v3", "4v4", "5v5"
     */
    @NotNull(message = "Game mode is required")
    private String gameMode;
    
    /**
     * Fill option: Allow random players to fill empty slots.
     * If true, party with less than full team will be matched with solo players.
     * If false, party must have full team (e.g., 2 players for 2v2).
     */
    @Builder.Default
    private boolean allowFill = true;

    /**
     * Optional map preference selected by the party host. Null means any map.
     */
    private String mapId;
}
