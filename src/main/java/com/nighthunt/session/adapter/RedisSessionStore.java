package com.nighthunt.session.adapter;

import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.session.port.SessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSessionStore implements SessionStore {
    private static final long REFRESH_THROTTLE_MILLIS = TimeUnit.SECONDS.toMillis(30);

    private final RedisTemplate<String, Object> redisTemplate;
    private final Map<String, SessionRefreshState> refreshStateByKey = new ConcurrentHashMap<>();

    @Override
    public void saveSession(String userId, String sessionId, int timeoutSeconds) {
        String key = GameConstants.REDIS_KEY_SESSION_PREFIX + userId;

        SessionRefreshState currentState = refreshStateByKey.get(key);
        long now = System.currentTimeMillis();
        if (currentState != null
                && sessionId.equals(currentState.sessionId)
                && now - currentState.lastRefreshAtMillis < REFRESH_THROTTLE_MILLIS) {
            return;
        }

        redisTemplate.opsForValue().set(key, sessionId, timeoutSeconds, TimeUnit.SECONDS);
        refreshStateByKey.put(key, new SessionRefreshState(sessionId, now));
        log.debug("Session saved to Redis - userId: {}, timeout: {}s, key: {}",
                userId, timeoutSeconds, key);
    }

    @Override
    public String getSessionId(String userId) {
        String key = GameConstants.REDIS_KEY_SESSION_PREFIX + userId;
        Object value = redisTemplate.opsForValue().get(key);
        String sessionId = value != null ? value.toString() : null;
        log.debug("Session retrieved from Redis - userId: {}, sessionId: {}, key: {}", userId, sessionId, key);
        return sessionId;
    }

    @Override
    public void deleteSession(String userId) {
        String key = GameConstants.REDIS_KEY_SESSION_PREFIX + userId;
        redisTemplate.delete(key);
        refreshStateByKey.remove(key);
    }

    @Override
    public void setForceLogout(String userId, boolean forceLogout) {
        String key = GameConstants.REDIS_KEY_FORCE_LOGOUT_PREFIX + userId;
        if (forceLogout) {
            redisTemplate.opsForValue().set(key, "true", 3600, TimeUnit.SECONDS);
        } else {
            redisTemplate.delete(key);
        }
    }

    @Override
    public boolean isForceLogout(String userId) {
        String key = GameConstants.REDIS_KEY_FORCE_LOGOUT_PREFIX + userId;
        Object value = redisTemplate.opsForValue().get(key);
        return value != null && "true".equals(value.toString());
    }

    @Override
    public void deleteForceLogout(String userId) {
        String key = GameConstants.REDIS_KEY_FORCE_LOGOUT_PREFIX + userId;
        redisTemplate.delete(key);
    }

    private record SessionRefreshState(String sessionId, long lastRefreshAtMillis) {
    }
}

