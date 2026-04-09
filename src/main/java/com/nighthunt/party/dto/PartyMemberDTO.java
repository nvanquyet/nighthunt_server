package com.nighthunt.party.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for party member information.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartyMemberDTO {
    private Long userId;
    private String username;
    private int joinOrder;               // 0=host, 1,2,3=guests
    private String onlineStatus;         // ONLINE, OFFLINE, IN_GAME
    private LocalDateTime joinedAt;
    private boolean isHost;
}
