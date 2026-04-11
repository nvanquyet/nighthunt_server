package com.nighthunt.analytics.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PlayerStatusDTO {
    private long   userId;
    private String username;
    private int    elo;
    private String tier;
    /** ONLINE | IN_QUEUE | IN_GAME | OFFLINE */
    private String status;
    private boolean isBanned;
}
