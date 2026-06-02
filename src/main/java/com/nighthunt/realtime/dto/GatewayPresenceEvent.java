package com.nighthunt.realtime.dto;

public record GatewayPresenceEvent(
        String type,
        Long userId,
        String gatewayId,
        String connectionId,
        String clientIp,
        String reason,
        String occurredAt
) {
    public boolean hasIdentity() {
        return userId != null && userId > 0
                && connectionId != null
                && !connectionId.isBlank();
    }
}
