package com.nighthunt.relay.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Response returned to clients when they query a relay session. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelaySessionResponse {
    private String sessionToken;
    private String matchId;
    private String relayHost;
    private int relayPort;
    private int connectedPlayers;
    private boolean started;
    private boolean finished;
}
