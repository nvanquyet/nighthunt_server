package com.nighthunt.messaging;

import com.nighthunt.messaging.adapter.RedisMessageBroker;
import com.nighthunt.messaging.constants.MessageTopics;
import com.nighthunt.messaging.service.MessageBrokerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MessageBrokerServiceTest {

    @Mock private RedisMessageBroker messageBroker;

    private MessageBrokerService service;

    @BeforeEach
    void setUp() {
        service = new MessageBrokerService(messageBroker);
    }

    @Test
    void deferredPartyInvitationEventAllowsNullPartyId() {
        service.publishPartyInvitationReceived(null, 2L, 1L, "host", 100L);

        ArgumentCaptor<Map<String, Object>> payload = payloadCaptor();
        verify(messageBroker).publish(
                org.mockito.ArgumentMatchers.eq(MessageTopics.PARTY_INVITATION_RECEIVED),
                org.mockito.ArgumentMatchers.eq("party.invitation.received"),
                payload.capture());
        assertThat(payload.getValue()).containsKey("partyId");
        assertThat(payload.getValue().get("partyId")).isNull();
    }

    @Test
    void deferredPartyInvitationExpiryEventAllowsNullPartyId() {
        service.publishPartyInvitationExpired(null, 1L, 2L, 100L);

        ArgumentCaptor<Map<String, Object>> payload = payloadCaptor();
        verify(messageBroker).publish(
                org.mockito.ArgumentMatchers.eq(MessageTopics.PARTY_INVITATION_EXPIRED),
                org.mockito.ArgumentMatchers.eq("party.invitation.expired"),
                payload.capture());
        assertThat(payload.getValue()).containsKey("partyId");
        assertThat(payload.getValue().get("partyId")).isNull();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<Map<String, Object>> payloadCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }
}
