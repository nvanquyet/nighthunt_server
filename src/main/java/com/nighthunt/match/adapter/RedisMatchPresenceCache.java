package com.nighthunt.match.adapter;

import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.match.model.MatchPresenceSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class RedisMatchPresenceCache {
    private final RedisTemplate<String, Object> redisTemplate;

    public void save(MatchPresenceSnapshot snapshot) {
        if (snapshot == null || snapshot.getMatchId() == null || snapshot.getUserId() == null) {
            return;
        }
        redisTemplate.opsForValue().set(key(snapshot.getMatchId(), snapshot.getUserId()), snapshot,
                GameConstants.MATCH_PRESENCE_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
    }

    public Optional<MatchPresenceSnapshot> get(String matchId, Long userId) {
        if (matchId == null || userId == null) {
            return Optional.empty();
        }
        Object value = redisTemplate.opsForValue().get(key(matchId, userId));
        if (value instanceof MatchPresenceSnapshot snapshot) {
            return Optional.of(snapshot);
        }
        return Optional.empty();
    }

    public void delete(String matchId, Long userId) {
        if (matchId == null || userId == null) {
            return;
        }
        redisTemplate.delete(key(matchId, userId));
    }

    private String key(String matchId, Long userId) {
        return GameConstants.REDIS_KEY_MATCH_PRESENCE_PREFIX + matchId + ":" + userId;
    }
}
