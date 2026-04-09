package com.nighthunt.room.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateRoomRequest {
    @NotBlank
    private String mode; // 2v2, 3v3, 5v5
    private String mapId; // Optional map ID. Null/blank = server default
    private Boolean isPublic = true;
    private Boolean isLocked = false;
    private String password; // Optional password for room
}

