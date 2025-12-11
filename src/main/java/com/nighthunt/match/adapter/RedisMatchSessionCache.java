package com.nighthunt.match.adapter;

import com.nighthunt.common.constants.GameConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisMatchSessionCache {
    private final RedisTemplate<String, Object> redisTemplate;

    public void saveMatchSession(String playerId, Object sessionData, int timeoutSeconds) {
        String key = GameConstants.REDIS_KEY_MATCH_SESSION_PREFIX + playerId;
        redisTemplate.opsForValue().set(key, sessionData, timeoutSeconds, TimeUnit.SECONDS);
    }

    public Object getMatchSession(String playerId) {
        String key = GameConstants.REDIS_KEY_MATCH_SESSION_PREFIX + playerId;
        return redisTemplate.opsForValue().get(key);
    }

    public void deleteMatchSession(String playerId) {
        String key = GameConstants.REDIS_KEY_MATCH_SESSION_PREFIX + playerId;
        redisTemplate.delete(key);
    }
}

