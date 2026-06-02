package com.nighthunt.gateway.event;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

public final class NatsGatewayEventPublisher implements GatewayEventPublisher {
    private final String gatewayId;
    private final String subjectPrefix;
    private final Connection connection;

    public NatsGatewayEventPublisher(
            String natsUrl,
            String subjectPrefix,
            String gatewayId
    ) throws IOException, InterruptedException {
        this.gatewayId = gatewayId;
        this.subjectPrefix = subjectPrefix;
        this.connection = Nats.connect(new Options.Builder()
                .server(natsUrl)
                .connectionTimeout(Duration.ofSeconds(3))
                .maxReconnects(-1)
                .build());
    }

    @Override
    public void connected(long userId, String connectionId, String clientIp) {
        publish("connected", userId, connectionId, clientIp, "");
    }

    @Override
    public void disconnected(long userId, String connectionId, String clientIp, String reason) {
        publish("disconnected", userId, connectionId, clientIp, reason);
    }

    private void publish(String eventType, long userId, String connectionId, String clientIp, String reason) {
        String payload = """
                {"type":"%s","userId":%d,"gatewayId":"%s","connectionId":"%s","clientIp":"%s","reason":"%s","occurredAt":"%s"}
                """.formatted(
                json(eventType),
                userId,
                json(gatewayId),
                json(connectionId),
                json(clientIp == null ? "" : clientIp),
                json(reason == null ? "" : reason),
                Instant.now()
        ).trim();
        connection.publish(
                subjectPrefix + ".events." + eventType,
                payload.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String json(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    @Override
    public void close() throws InterruptedException {
        connection.close();
    }
}
