package com.nighthunt.gateway.redis;

import java.util.OptionalLong;
import java.util.concurrent.CompletionStage;

public interface TicketStore {
    CompletionStage<OptionalLong> consume(String ticket);
}
