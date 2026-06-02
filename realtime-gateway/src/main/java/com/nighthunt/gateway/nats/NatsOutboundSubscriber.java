package com.nighthunt.gateway.nats;

import com.nighthunt.gateway.connection.ConnectionRegistry;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import io.nats.client.Options;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class NatsOutboundSubscriber implements AutoCloseable {
    private final Connection connection;

    public NatsOutboundSubscriber(
            String natsUrl,
            String subjectPrefix,
            String gatewayId,
            ConnectionRegistry registry
    ) throws IOException, InterruptedException {
        connection = Nats.connect(new Options.Builder()
                .server(natsUrl)
                .connectionTimeout(Duration.ofSeconds(3))
                .maxReconnects(-1)
                .build());
        Dispatcher dispatcher = connection.createDispatcher(message -> {
            String[] subjectParts = message.getSubject().split("\\.");
            if (subjectParts.length == 0) {
                return;
            }
            try {
                long userId = Long.parseLong(subjectParts[subjectParts.length - 1]);
                registry.send(userId, new String(message.getData(), StandardCharsets.UTF_8));
            } catch (NumberFormatException ignored) {
                // Invalid subjects are ignored and visible via NATS monitoring.
            }
        });
        dispatcher.subscribe(subjectPrefix + "." + gatewayId + ".user.*");
    }

    @Override
    public void close() throws InterruptedException {
        connection.close();
    }
}
