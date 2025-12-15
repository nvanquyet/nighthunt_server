package com.nighthunt.room.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SwapRequestRequest {
    // targetUserId can be null when swapping with empty slot
    private Long targetUserId;

    @NotNull
    private Integer targetTeam;

    @NotNull
    private Integer targetSlot;
}

