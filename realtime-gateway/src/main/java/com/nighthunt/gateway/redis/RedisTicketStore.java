package com.nighthunt.gateway.redis;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.util.OptionalLong;
import java.util.concurrent.CompletionStage;

public final class RedisTicketStore implements TicketStore {
    private static final String TICKET_KEY_PREFIX = "ws:ticket:";
    private static final String CONSUME_SCRIPT = """
            local value = redis.call('GET', KEYS[1])
            if value then redis.call('DEL', KEYS[1]) end
            return value
            """;

    private final RedisAsyncCommands<String, String> redis;

    public RedisTicketStore(RedisAsyncCommands<String, String> redis) {
        this.redis = redis;
    }

    @Override
    public CompletionStage<OptionalLong> consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(OptionalLong.empty());
        }
        return redis.eval(
                        CONSUME_SCRIPT,
                        ScriptOutputType.VALUE,
                        new String[]{TICKET_KEY_PREFIX + ticket}
                )
                .thenApply(value -> value == null
                        ? OptionalLong.empty()
                        : OptionalLong.of(Long.parseLong(String.valueOf(value))));
    }
}
