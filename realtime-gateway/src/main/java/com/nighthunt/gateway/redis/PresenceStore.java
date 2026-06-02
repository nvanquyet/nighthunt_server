package com.nighthunt.gateway.redis;

import java.util.concurrent.CompletionStage;

public interface PresenceStore {
    CompletionStage<Void> refresh(long userId, String connectionId, String clientIp);
    CompletionStage<Void> release(long userId, String connectionId);
}
