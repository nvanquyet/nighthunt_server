package com.nighthunt.room.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoomListItemResponse {
    private Long roomId;
    private String roomCode;
    private String mode;
    private String mapId;
    private String status;
    private Boolean isPublic;
    private Boolean isLocked;
    private Long ownerId;
    private String ownerUsername;
    private int currentPlayers;
    private int maxPlayers;
}
