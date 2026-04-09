package com.nighthunt.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Body for POST /auth/refresh-token. */
@Data
public class RefreshTokenRequest {
    @NotBlank
    private String refreshToken;
}
