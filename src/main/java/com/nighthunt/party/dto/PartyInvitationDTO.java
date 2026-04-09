package com.nighthunt.party.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for party invitation information.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartyInvitationDTO {
    private Long invitationId;
    private Long partyId;
    private Long inviterUserId;
    private String inviterUsername;
    private Long inviteeUserId;
    private String inviteeUsername;
    private String invitationStatus;     // PENDING, ACCEPTED, DECLINED, EXPIRED, CANCELLED
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private int secondsRemaining;        // Countdown for UI
}
