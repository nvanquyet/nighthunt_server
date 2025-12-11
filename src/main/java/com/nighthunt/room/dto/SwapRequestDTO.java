package com.nighthunt.room.dto;

import lombok.Data;

@Data
public class SwapRequestDTO {
    private Long requestId;
    private Long requesterId;
    private String requesterUsername;
    private Integer requesterTeam;
    private Integer requesterSlot;
    private Integer targetTeam;
    private Integer targetSlot;
    private String status;
}

