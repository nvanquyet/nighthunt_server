package com.nighthunt.game.websocket.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Standard WebSocket message format used by both client and server
 * Format: {"type": "event_type", "data": "{\"key\":\"value\"}"}
 * Note: data is a JSON string (not object) for Unity JsonUtility compatibility
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketMessageDTO {
    @JsonProperty("type")
    private String type;
    
    @JsonProperty("data")
    private String data; // JSON string for Unity JsonUtility compatibility
}

