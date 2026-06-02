package com.nighthunt.party.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.nighthunt.party.repository.PartyMemberRepository;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * PartyCacheService — Redis-backed cache for party member ID lists.
 *
 * <p>Mirror of {@link com.nighthunt.friend.service.FriendCacheService} applied to
 * parties (P1-3 implementation). The {@code WebSocketEventSubscriber} calls
 * {@code partyMemberRepository.findUserIdsByPartyId()} on <b>every</b> party event
 * (member joined, left, kicked, host changed, status changed). With 1000+ CCU and
 * frequent party transitions this produces significant DB round-trips.</p>
 *
 * <h2>Cache contract</h2>
 * <ul>
 *   <li>Key: {@code "party_members:{partyId}"}, TTL: 30 s (parties change more often than friend lists).</li>
 *   <li>On miss: falls through to DB and warms the cache.</li>
 *   <li>Eviction: must be called explicitly after any mutation that changes party membership
 *       (join, leave, kick, disband).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartyCacheService {

    private static final String KEY_PREFIX  = "party_members:";
    private static final long   TTL_SECONDS = 30L;

    private final RedisTemplate<String, Object> redisTemplate;
    private final PartyMemberRepository         partyMemberRepository;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the cached member-user-ID list for the given party, or fetches from DB on miss.
     *
     * @param partyId the party whose member IDs to retrieve
     * @return list of member user-IDs (never null, may be empty)
     */
    public List<Long> getMemberIds(Long partyId) {
        if (partyId == null || partyId <= 0) return Collections.emptyList();
        String key = KEY_PREFIX + partyId;
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            // Safe-cast: Jackson deserializes longs as Integer on round-trip — normalise here
            if (cached instanceof List<?> list) {
                log.debug("Party-member cache HIT for partyId={}", partyId);
                return list.stream()
                        .map(item -> item instanceof Number n ? n.longValue()
                                                              : Long.parseLong(item.toString()))
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("Redis read error for key={}, falling back to DB: {}", key, e.getMessage());
        }

        List<Long> ids = partyMemberRepository.findUserIdsByPartyId(partyId);
        try {
            redisTemplate.opsForValue().set(key, ids, TTL_SECONDS, TimeUnit.SECONDS);
            log.debug("Party-member cache SET for partyId={} ({} members)", partyId, ids.size());
        } catch (Exception e) {
            log.warn("Redis write error for key={}: {}", key, e.getMessage());
        }
        return ids;
    }

    /**
     * Evicts the cached member list for a party.
     * Call this after any membership change (join, leave, kick, disband).
     *
     * @param partyId the party whose cache to evict
     */
    public void evict(Long partyId) {
        if (partyId == null || partyId <= 0) return;
        try {
            redisTemplate.delete(KEY_PREFIX + partyId);
            log.debug("Party-member cache evicted for partyId={}", partyId);
        } catch (Exception e) {
            log.warn("Redis delete error during party cache eviction: {}", e.getMessage());
        }
    }
}
