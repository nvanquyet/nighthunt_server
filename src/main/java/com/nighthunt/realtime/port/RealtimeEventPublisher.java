package com.nighthunt.realtime.port;

public interface RealtimeEventPublisher {
    void publishToUser(Long userId, String encodedClientMessage);
    void publishToRoom(Long roomId, String encodedClientMessage);
}
