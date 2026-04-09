package com.nighthunt.room.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoomResponse {
    private Long roomId;
    private String roomCode;
    private String mode;
    private String mapId;
    private String status;
    private Boolean isPublic;
    private Boolean isLocked;
    private Long ownerId;
    private String matchId;
    private String joinToken;
    private List<RoomPlayerResponse> players;
}

