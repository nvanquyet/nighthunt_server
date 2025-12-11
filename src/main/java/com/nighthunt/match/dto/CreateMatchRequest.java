package com.nighthunt.match.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateMatchRequest {
    private String mode; // 2v2, 3v3, 5v5
    private Long ownerId;
    private String mapConfig; // optional map configuration
}

