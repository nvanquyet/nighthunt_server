package com.nighthunt.match.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for in-match connection presence updates.
 *
 * <p>Used by both the host/relay side and the dedicated server to tell the backend
 * when a player connects, disconnects, or is permanently abandoned after TTL.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MatchPresenceRequest {
    private String matchId;
    private Long userId;
    private MatchPresenceState state;
    private String reason;

    /**
     * DS authentication fields. Ignored by the user-authenticated endpoint.
     */
    private String serverId;
    private String serverSecret;
}
