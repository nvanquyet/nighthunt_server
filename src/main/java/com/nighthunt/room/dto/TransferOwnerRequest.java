package com.nighthunt.room.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TransferOwnerRequest {
    @NotNull(message = "Target user ID is required")
    private Long targetUserId;
}

