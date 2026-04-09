package com.nighthunt.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response body for GET /api/profile.
 * Includes the fields the Unity client cares about for rendering / lobby display.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProfileResponse {
    private Long   userId;
    private String username;
    /** String ID matching CharacterDefinition.CharacterId in CharacterDatabase. */
    private String selectedCharacterId;
    private int    elo;
    private String tier;
    private int    totalWins;
    private int    totalLosses;
}
