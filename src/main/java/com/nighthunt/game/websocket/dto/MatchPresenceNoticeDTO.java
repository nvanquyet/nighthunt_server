package com.nighthunt.game.websocket.dto;

import com.nighthunt.room.dto.RoomResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Room broadcast emitted whenever a player's match presence changes.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MatchPresenceNoticeDTO {
    private String matchId;
    private Long userId;
    private String displayName;
    private String state;
    private String reason;
    private Integer graceSeconds;
    private String message;
    private RoomResponse room;
}
