package com.nighthunt.room.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ReconnectRequest {
    @NotBlank
    private String accessToken;
    @NotBlank
    private String sessionId;
    private Long roomId; // optional, can be inferred from session
}

