package com.nighthunt.room.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * RedisRoomStateCache - DISABLED
 * Room state caching functionality has been disabled.
 * This adapter remains for backward compatibility but all methods are no-ops.
 */
@Slf4j
@Component
public class RedisRoomStateCache {
    
    public void saveRoomState(String roomId, Object state, int timeoutSeconds) {
        log.debug("Room state caching disabled - saveRoomState is no-op");
    }

    public Object getRoomState(String roomId) {
        log.debug("Room state caching disabled - getRoomState returns null");
        return null;
    }

    public void deleteRoomState(String roomId) {
        log.debug("Room state caching disabled - deleteRoomState is no-op");
    }
}
