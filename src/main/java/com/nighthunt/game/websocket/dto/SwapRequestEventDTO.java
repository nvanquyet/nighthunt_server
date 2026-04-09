package com.nighthunt.game.websocket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SwapRequestEventDTO {
    private Long requesterId;
    private String requesterUsername;
    private Long targetUserId;
    private Long requestId;
}
