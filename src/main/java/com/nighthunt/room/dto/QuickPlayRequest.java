package com.nighthunt.room.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class QuickPlayRequest {
    @NotBlank
    private String mode; // 2v2, 3v3, 5v5
    private String mapId; // Optional map ID. Null/blank = any/default map
}

