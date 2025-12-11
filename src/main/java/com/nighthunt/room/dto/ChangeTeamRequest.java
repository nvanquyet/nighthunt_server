package com.nighthunt.room.dto;

import lombok.Data;

@Data
public class ChangeTeamRequest {
    private Integer team; // 1 or 2
    private Integer slot; // position in team
}

