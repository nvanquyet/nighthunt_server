package com.nighthunt.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nighthunt.realtime.outbox.RealtimeOutboxEvent;
import com.nighthunt.realtime.outbox.RealtimeOutboxRepository;
import com.nighthunt.realtime.outbox.RealtimeOutboxStatus;
import com.nighthunt.realtime.port.DurableEventPublisher;
import com.nighthunt.realtime.service.RealtimeOutboxService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeOutboxServiceTest {
    @Test
    void publishesPendingEventsAndMarksThemComplete() throws Exception {
        RealtimeOutboxRepository repository = mock(RealtimeOutboxRepository.class);
        DurableEventPublisher publisher = mock(DurableEventPublisher.class);
        RealtimeOutboxService service = new RealtimeOutboxService(repository, publisher, new ObjectMapper());
        ReflectionTestUtils.setField(service, "batchSize", 10);
        RealtimeOutboxEvent event = RealtimeOutboxEvent.pending("events.match.ended", "{\"matchId\":\"m-1\"}");
        when(publisher.isEnabled()).thenReturn(true);
        when(repository.findReadyForPublish(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(event));

        service.publishReady();

        verify(publisher).publish("events.match.ended", "{\"matchId\":\"m-1\"}");
        assertThat(event.getStatus()).isEqualTo(RealtimeOutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
    }

    @Test
    void enqueuePersistsSerializedPayload() {
        RealtimeOutboxRepository repository = mock(RealtimeOutboxRepository.class);
        DurableEventPublisher publisher = mock(DurableEventPublisher.class);
        RealtimeOutboxService service = new RealtimeOutboxService(repository, publisher, new ObjectMapper());

        String eventId = service.enqueue("events.ds.ready", Map.of("serverId", "ds-1"));

        assertThat(eventId).isNotBlank();
        verify(repository).save(any(RealtimeOutboxEvent.class));
    }
}
