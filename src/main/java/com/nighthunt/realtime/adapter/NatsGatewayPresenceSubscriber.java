package com.nighthunt.realtime.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nighthunt.realtime.dto.GatewayPresenceEvent;
import com.nighthunt.realtime.service.GatewayPresenceEventHandler;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import io.nats.client.Options;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "nighthunt.realtime.nats.enabled", havingValue = "true")
public class NatsGatewayPresenceSubscriber {
    private final ObjectMapper objectMapper;
    private final GatewayPresenceEventHandler eventHandler;

    @Value("${nighthunt.realtime.nats.url:nats://localhost:4222}")
    private String natsUrl;

    @Value("${nighthunt.realtime.nats.subject-prefix:rt.gateway}")
    private String subjectPrefix;

    private Connection connection;

    @PostConstruct
    void connect() throws Exception {
        connection = Nats.connect(new Options.Builder()
                .server(natsUrl)
                .connectionTimeout(Duration.ofSeconds(3))
                .maxReconnects(-1)
                .build());

        Dispatcher dispatcher = connection.createDispatcher(message -> {
            try {
                GatewayPresenceEvent event = objectMapper.readValue(
                        new String(message.getData(), StandardCharsets.UTF_8),
                        GatewayPresenceEvent.class
                );
                if ("connected".equals(event.type())) {
                    eventHandler.handleConnected(event);
                } else if ("disconnected".equals(event.type())) {
                    eventHandler.handleDisconnected(event);
                } else {
                    log.debug("Ignoring gateway presence event with type={}", event.type());
                }
            } catch (Exception error) {
                log.warn("Failed to handle gateway presence event subject={}: {}",
                        message.getSubject(), error.getMessage());
            }
        });
        dispatcher.subscribe(subjectPrefix + ".events.*");
        log.info("Gateway presence subscriber connected to {} subject={}.events.*", natsUrl, subjectPrefix);
    }

    @PreDestroy
    void close() throws InterruptedException {
        if (connection != null) {
            connection.close();
        }
    }
}
