package com.nighthunt.party.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO for party information.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartyDTO {
    private Long partyId;
    private Long hostUserId;
    private String hostUsername;
    private String partyStatus;          // IDLE, IN_QUEUE, IN_ROOM, IN_GAME, DISBANDED
    private Long currentRoomId;
    private Long currentMatchmakingId;
    private int maxMembers;
    private int currentMemberCount;
    private List<PartyMemberDTO> members;
    private LocalDateTime createdAt;
}
