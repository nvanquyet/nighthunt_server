package com.nighthunt.game.websocket.dto;

import com.nighthunt.room.dto.RoomResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamChangedEventDTO {
    private Long userId;
    private Integer newTeam;
    private Integer newSlot;
    private RoomResponse room;
}
