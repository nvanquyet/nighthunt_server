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
public class PlayerLeftEventDTO {
    private Long userId;
    private RoomResponse room;
}
