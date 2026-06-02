package com.nighthunt.realtime.adapter;

import com.nighthunt.realtime.port.RealtimeEventPublisher;
import com.nighthunt.realtime.service.RealtimeRouteStore;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class NatsRealtimeEventPublisher implements RealtimeEventPublisher {
    private final RealtimeRouteStore routeStore;

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
        log.info("Realtime NATS publisher connected to {}", natsUrl);
    }

    @Override
    public void publishToUser(Long userId, String encodedClientMessage) {
        if (userId == null || encodedClientMessage == null || connection == null) {
            return;
        }
        String gatewayId = routeStore.getGatewayIdForUser(userId);
        if (gatewayId == null) {
            return;
        }
        String subject = subjectPrefix + "." + gatewayId + ".user." + userId;
        connection.publish(subject, encodedClientMessage.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void publishToRoom(Long roomId, String encodedClientMessage) {
        for (String member : routeStore.getRoomUserIds(roomId)) {
            try {
                publishToUser(Long.parseLong(member), encodedClientMessage);
            } catch (NumberFormatException ignored) {
                // Route sets are internal Redis state; invalid entries are ignored.
            }
        }
    }

    @PreDestroy
    void close() throws InterruptedException {
        if (connection != null) {
            connection.close();
        }
    }
}
