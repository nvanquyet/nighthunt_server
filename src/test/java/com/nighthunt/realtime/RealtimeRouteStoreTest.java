package com.nighthunt.realtime;

import com.nighthunt.realtime.service.RealtimeRouteStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RealtimeRouteStoreTest {
    @Test
    void parsesAndCachesGatewayRoute() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("route:user:7")).thenReturn("gateway-1|connection-abc|203.0.113.9");
        RealtimeRouteStore routes = new RealtimeRouteStore(redis);

        assertThat(routes.getGatewayIdForUser(7L)).isEqualTo("gateway-1");
        assertThat(routes.getClientIp(7L)).isEqualTo("203.0.113.9");

        verify(values).get("route:user:7");
        verifyNoMoreInteractions(values);
    }

    @Test
    void currentConnectionChecksByConnectionIdFromFreshRoute() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("route:user:7"))
                .thenReturn("gateway-1|connection-new|203.0.113.9")
                .thenReturn("gateway-1|connection-new|203.0.113.9")
                .thenReturn(null);
        RealtimeRouteStore routes = new RealtimeRouteStore(redis);

        assertThat(routes.isCurrentConnection(7L, "connection-new")).isTrue();
        assertThat(routes.isCurrentConnection(7L, "connection-old")).isFalse();
        assertThat(routes.isRouteMissingOrCurrentConnection(7L, "connection-old")).isTrue();

        verify(values, times(3)).get("route:user:7");
    }
}
