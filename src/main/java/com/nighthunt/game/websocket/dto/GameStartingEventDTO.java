package com.nighthunt.game.websocket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * BE-31 — WebSocket event DTO: {@code game_starting}.
 *
 * <p>Broadcast to all players in a room when the host starts the match.
 * Carries relay connection info (Custom mode) or DS address (Ranked mode)
 * so the Unity client can begin connecting.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameStartingEventDTO {

    private final String event = "game_starting";

    /** Room identifier. */
    private Long roomId;

    /** Match UUID. */
    private String matchId;

    /** Relay session token (Custom mode). Null for Ranked/DS mode. */
    private String relayToken;

    /** Relay host address (Custom mode). Null for Ranked/DS mode. */
    private String relayHost;

    /** Relay port (Custom mode). 0 for Ranked/DS mode. */
    private int relayPort;

    /** Host-facing upstream ports for Custom relay. Empty for Ranked/DS mode. */
    private List<Integer> relayHostPorts;

    /** Dedicated server connection address (Ranked mode). Null for Custom. */
    private String dsAddress;

    /** Dedicated server port (Ranked mode). 0 for Custom. */
    private int dsPort;

    /** "CUSTOM" | "RANKED". */
    private String gameMode;
}
