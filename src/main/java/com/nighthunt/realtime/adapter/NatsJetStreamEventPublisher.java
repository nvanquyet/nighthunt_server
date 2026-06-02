package com.nighthunt.realtime.adapter;

import com.nighthunt.realtime.port.DurableEventPublisher;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.PublishAck;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Slf4j
@Component
public class NatsJetStreamEventPublisher implements DurableEventPublisher {
    @Value("${nighthunt.realtime.nats.url:nats://localhost:4222}")
    private String natsUrl;

    @Value("${nighthunt.realtime.jetstream.stream-name:NIGHTHUNT_EVENTS}")
    private String streamName;

    @Value("${nighthunt.realtime.jetstream.subjects:events.>}")
    private String streamSubjects;

    private Connection connection;
    private JetStream jetStream;

    @PostConstruct
    void connect() throws Exception {
        connection = Nats.connect(new Options.Builder()
                .server(natsUrl)
                .connectionTimeout(Duration.ofSeconds(3))
                .maxReconnects(-1)
                .build());
        ensureStream(connection.jetStreamManagement());
        jetStream = connection.jetStream();
        log.info("JetStream outbox publisher connected to {} stream={}", natsUrl, streamName);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void publish(String subject, String payload) throws Exception {
        PublishAck ack = jetStream.publish(subject, payload.getBytes(StandardCharsets.UTF_8));
        if (ack.hasError()) {
            throw new IllegalStateException("JetStream rejected event: " + ack.getError());
        }
    }

    @PreDestroy
    void close() throws InterruptedException {
        if (connection != null) {
            connection.close();
        }
    }

    private void ensureStream(JetStreamManagement management) throws Exception {
        try {
            management.getStreamInfo(streamName);
        } catch (JetStreamApiException error) {
            if (error.getErrorCode() != 404) {
                throw error;
            }
            management.addStream(StreamConfiguration.builder()
                    .name(streamName)
                    .subjects(streamSubjects)
                    .storageType(StorageType.File)
                    .build());
        }
    }
}
