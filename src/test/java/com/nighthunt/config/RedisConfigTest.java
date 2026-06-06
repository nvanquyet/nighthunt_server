package com.nighthunt.config;

import com.nighthunt.match.dto.MatchPresenceState;
import com.nighthunt.match.model.MatchPresenceSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RedisConfigTest {

    @Test
    void redisJsonSerializerRoundTripsMatchPresenceSnapshotWithJavaTime() {
        RedisSerializer<Object> serializer = new RedisConfig().redisJsonSerializer();
        LocalDateTime reportedAt = LocalDateTime.of(2026, 6, 6, 11, 56, 32);

        MatchPresenceSnapshot snapshot = MatchPresenceSnapshot.builder()
                .matchId("match-1")
                .roomId(10L)
                .userId(3L)
                .displayName("player-3")
                .state(MatchPresenceState.CONNECTED)
                .reportedAt(reportedAt)
                .disconnectedAt(reportedAt.plusSeconds(5))
                .build();

        byte[] bytes = serializer.serialize(snapshot);
        Object restored = serializer.deserialize(bytes);

        assertThat(restored).isInstanceOf(MatchPresenceSnapshot.class);
        MatchPresenceSnapshot actual = (MatchPresenceSnapshot) restored;
        assertThat(actual.getReportedAt()).isEqualTo(reportedAt);
        assertThat(actual.getDisconnectedAt()).isEqualTo(reportedAt.plusSeconds(5));
        assertThat(actual.getState()).isEqualTo(MatchPresenceState.CONNECTED);
    }
}
