package com.nighthunt.gateway.redis;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class RedisPresenceStore implements PresenceStore {
    private static final String PRESENCE_PREFIX = "presence:user:";
    private static final String ROUTE_PREFIX = "route:user:";
    private static final String COMPARE_DELETE_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final RedisAsyncCommands<String, String> redis;
    private final String gatewayId;
    private final Duration lease;

    public RedisPresenceStore(
            RedisAsyncCommands<String, String> redis,
            String gatewayId,
            Duration lease
    ) {
        this.redis = redis;
        this.gatewayId = gatewayId;
        this.lease = lease;
    }

    @Override
    public CompletionStage<Void> refresh(long userId, String connectionId, String clientIp) {
        String value = routeValue(connectionId, clientIp);
        SetArgs ttl = SetArgs.Builder.ex(lease);
        CompletableFuture<String> presence = redis.set(PRESENCE_PREFIX + userId, value, ttl).toCompletableFuture();
        CompletableFuture<String> route = redis.set(ROUTE_PREFIX + userId, value, ttl).toCompletableFuture();
        return CompletableFuture.allOf(presence, route);
    }

    @Override
    public CompletionStage<Void> release(long userId, String connectionId) {
        String expectedPrefix = gatewayId + "|" + connectionId + "|";
        CompletableFuture<Long> presence = compareAndDelete(PRESENCE_PREFIX + userId, expectedPrefix);
        CompletableFuture<Long> route = compareAndDelete(ROUTE_PREFIX + userId, expectedPrefix);
        return CompletableFuture.allOf(presence, route);
    }

    private CompletableFuture<Long> compareAndDelete(String key, String expectedPrefix) {
        String script = """
                local value = redis.call('GET', KEYS[1])
                if value and string.sub(value, 1, string.len(ARGV[1])) == ARGV[1] then
                  return redis.call('DEL', KEYS[1])
                end
                return 0
                """;
        return redis.<Long>eval(script, ScriptOutputType.INTEGER, new String[]{key}, expectedPrefix)
                .toCompletableFuture();
    }

    private String routeValue(String connectionId, String clientIp) {
        return gatewayId + "|" + connectionId + "|" + (clientIp == null ? "" : clientIp);
    }
}
