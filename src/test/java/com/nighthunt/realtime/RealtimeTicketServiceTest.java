package com.nighthunt.realtime;

import com.nighthunt.realtime.dto.RealtimeTicketResponse;
import com.nighthunt.realtime.service.RealtimeTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtimeTicketServiceTest {
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private RealtimeTicketService service;

    @BeforeEach
    void setUp() {
        service = new RealtimeTicketService(redisTemplate);
        ReflectionTestUtils.setField(service, "ticketTtlSeconds", 45L);
        ReflectionTestUtils.setField(service, "wsPath", "/api/ws/game");
    }

    @Test
    void issueStoresOpaqueTicketWithShortTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        RealtimeTicketResponse response = service.issue(42L);

        assertThat(response.ticket()).hasSize(43);
        assertThat(response.ticket()).doesNotContain("42");
        assertThat(response.expiresInSeconds()).isEqualTo(45);
        assertThat(response.wsPath()).isEqualTo("/api/ws/game");
        verify(valueOperations).set(
                "ws:ticket:" + response.ticket(),
                "42",
                Duration.ofSeconds(45)
        );
    }

    @Test
    void issueRejectsMissingIdentity() {
        assertThatThrownBy(() -> service.issue(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
