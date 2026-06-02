package com.nighthunt.friend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * FriendCacheService — Redis-backed cache for friend ID lists.
 *
 * <p>The friend status broadcast path queries {@code findFriendIdsByUserId()} on every
 * status-change event. With 1000+ CCU this produces significant DB load. This cache
 * stores the friend-id list per user with a short TTL so that repeated events within
 * the same window reuse the cached result.</p>
 *
 * <p>Cache key: {@code "friend_ids:{userId}"}, TTL: 60 seconds.</p>
 *
 * <p>Invalidation: called explicitly from {@link FriendService} after any mutation
 * (add, remove, block) that changes the friend list.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FriendCacheService {

    private static final String KEY_PREFIX = "friend_ids:";
    private static final long TTL_SECONDS = 60L;

    private final RedisTemplate<String, Object> redisTemplate;
    private final com.nighthunt.friend.repository.FriendRepository friendRepository;

    /**
     * Returns the cached friend-id list for the given user, or fetches from DB on miss.
     *
     * @param userId the user whose friend IDs to retrieve
     * @return list of friend user-IDs (never null)
     */
    public List<Long> getFriendIds(Long userId) {
        String key = KEY_PREFIX + userId;
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            // P0-5 FIX: Jackson may deserialize numbers as Integer on cache hit.
            // Safe-cast each element to Long regardless of actual numeric subtype.
            if (cached instanceof List<?> list) {
                log.debug("Friend-IDs cache HIT for userId={}", userId);
                return list.stream()
                    .map(item -> item instanceof Number n ? n.longValue()
                                                         : Long.parseLong(item.toString()))
                    .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("Redis read error for key={}, falling back to DB: {}", key, e.getMessage());
        }

        List<Long> ids = friendRepository.findFriendIdsByUserId(userId);
        try {
            redisTemplate.opsForValue().set(key, ids, TTL_SECONDS, TimeUnit.SECONDS);
            log.debug("Friend-IDs cache SET for userId={} ({} friends)", userId, ids.size());
        } catch (Exception e) {
            log.warn("Redis write error for key={}: {}", key, e.getMessage());
        }
        return ids;
    }

    /**
     * Evicts the cached friend-id list for both users involved in a friendship mutation.
     * Must be called after add, remove, or block operations.
     *
     * @param userIdA first user
     * @param userIdB second user
     */
    public void evict(Long userIdA, Long userIdB) {
        try {
            redisTemplate.delete(List.of(KEY_PREFIX + userIdA, KEY_PREFIX + userIdB));
            log.debug("Friend-IDs cache evicted for userId={} and userId={}", userIdA, userIdB);
        } catch (Exception e) {
            log.warn("Redis delete error during cache eviction: {}", e.getMessage());
        }
    }

    /**
     * Evicts the cached friend-id list for a single user.
     *
     * @param userId the user whose cache to evict
     */
    public void evict(Long userId) {
        try {
            redisTemplate.delete(KEY_PREFIX + userId);
            log.debug("Friend-IDs cache evicted for userId={}", userId);
        } catch (Exception e) {
            log.warn("Redis delete error during cache eviction: {}", e.getMessage());
        }
    }
}
