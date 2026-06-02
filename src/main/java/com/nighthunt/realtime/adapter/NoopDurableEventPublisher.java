package com.nighthunt.realtime.adapter;

import com.nighthunt.realtime.port.DurableEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        value = "nighthunt.realtime.jetstream.enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class NoopDurableEventPublisher implements DurableEventPublisher {
    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public void publish(String subject, String payload) {
        throw new IllegalStateException("JetStream publisher is disabled");
    }
}
