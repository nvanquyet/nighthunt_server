package com.nighthunt.relay.dto;

import lombok.Data;

/** Request body for POST /api/relay/join — client announces it connected to relay. */
@Data
public class RelayJoinRequest {
    /** The relay session token received in the room-start WS event. */
    private String sessionToken;
}
