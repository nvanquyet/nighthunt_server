package com.nighthunt.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body for PUT /api/profile/character. */
@Data
public class UpdateCharacterRequest {
    /**
     * String ID of the character to set (e.g. "character_02").
     * Must match a CharacterDefinition.CharacterId registered in CharacterDatabase on the client.
     */
    @NotBlank
    @Size(max = 64)
    private String selectedCharacterId;
}
