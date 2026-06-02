package com.nighthunt.realtime.dto;

public record RealtimeTicketResponse(
        String ticket,
        long expiresInSeconds,
        String wsPath
) {
}
