package com.nighthunt.party.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for inviting a user to party.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InviteToPartyRequest {
    
    /**
     * User ID to invite.
     * Can also invite by username (future enhancement).
     */
    @NotNull(message = "User ID is required")
    private Long inviteeUserId;
}
