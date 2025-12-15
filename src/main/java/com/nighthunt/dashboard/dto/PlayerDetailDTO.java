package com.nighthunt.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Player Detail DTO for Dashboard
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerDetailDTO {
    private Long userId;
    private String username;
    private Integer team;
    private Integer slot;
    private Boolean isReady;
    private Boolean isOwner;
}

