package com.nighthunt.realtime.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nighthunt.realtime.outbox.RealtimeOutboxEvent;
import com.nighthunt.realtime.outbox.RealtimeOutboxRepository;
import com.nighthunt.realtime.port.DurableEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeOutboxService {
    private final RealtimeOutboxRepository repository;
    private final DurableEventPublisher publisher;
    private final ObjectMapper objectMapper;

    @Value("${nighthunt.realtime.jetstream.batch-size:100}")
    private int batchSize;

    @Transactional
    public String enqueue(String subject, Object payload) {
        try {
            RealtimeOutboxEvent event = RealtimeOutboxEvent.pending(
                    subject,
                    objectMapper.writeValueAsString(payload)
            );
            repository.save(event);
            return event.getEventId();
        } catch (Exception error) {
            throw new IllegalStateException("Failed to append realtime outbox event", error);
        }
    }

    @Scheduled(fixedDelayString = "${nighthunt.realtime.jetstream.poll-delay-millis:500}")
    @Transactional
    public void publishReady() {
        if (!publisher.isEnabled()) {
            return;
        }

        for (RealtimeOutboxEvent event : repository.findReadyForPublish(
                LocalDateTime.now(),
                PageRequest.of(0, batchSize)
        )) {
            try {
                publisher.publish(event.getSubject(), event.getPayload());
                event.markPublished();
            } catch (Exception error) {
                event.scheduleRetry(error);
                log.warn("JetStream outbox publish failed eventId={} subject={} attempts={}: {}",
                        event.getEventId(), event.getSubject(), event.getAttempts(), error.getMessage());
            }
        }
    }
}
