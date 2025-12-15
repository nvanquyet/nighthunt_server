package com.nighthunt.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AutoLoginRequest {
    @NotBlank
    private String accessToken;
    @NotBlank
    private String sessionId;
    private String deviceFingerprint; // Optional: device fingerprint for ban tracking
}

