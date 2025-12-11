package com.nighthunt.room.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoomPlayerResponse {
    private Long userId;
    private String username;
    private Integer team;
    private Integer slot;
    private Boolean isReady;
}

