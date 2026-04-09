package com.nighthunt.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Returned by /auth/login, /auth/auto-login, and /auth/refresh-token.
 * Always carries both tokens + the user's current profile fields so the
 * client does not need a separate GET /api/profile call after auth.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {
    private String accessToken;
    /** Long-lived token used to obtain new access tokens. Persist client-side. */
    private String refreshToken;
    private String sessionId;
    private Long   userId;
    private String username;
    private String email;
    /**
     * String ID of the player’s selected character model (e.g. "character_01").
     * Matches CharacterDefinition.CharacterId in the Unity client.
     * NULL when the player has never chosen a character.
     */
    private String selectedCharacterId;
}
