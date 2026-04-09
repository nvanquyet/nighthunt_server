package com.nighthunt.game.websocket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * BE-31 — WebSocket event DTO: {@code match_found}.
 *
 * <p>Sent to each player in a matched group when the matchmaking scheduler
 * forms a full lobby. The client switches from the Searching panel to the
 * MatchFound / Accept panel in {@code HomeView}.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchFoundEventDTO {

    /** Event discriminator consumed by Unity client. */
    private final String event = "match_found";

    /** Opaque lobby token that identifies this pre-match group. */
    private String lobbyToken;

    /** Game mode string: "2v2" | "3v3" | "5v5". */
    private String gameMode;

    /** User IDs of all players in this group (including the recipient). */
    private List<Long> playerIds;

    /**
     * Seconds the client has to accept before auto-decline.
     * Driven by {@code matchmaking.accept-timeout-seconds} config.
     */
    private int acceptTimeoutSeconds;
}
