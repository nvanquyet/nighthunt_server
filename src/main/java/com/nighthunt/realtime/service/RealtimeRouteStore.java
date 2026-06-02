package com.nighthunt.realtime.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RealtimeRouteStore {
    private static final String USER_ROUTE_PREFIX = "route:user:";
    private static final String USER_ROOM_PREFIX = "route:user-room:";
    private static final String ROOM_USERS_PREFIX = "route:room:";
    private static final String ROOM_USERS_SUFFIX = ":users";
    private static final long USER_ROUTE_CACHE_MILLIS = TimeUnit.SECONDS.toMillis(1);
    private static final RedisScript<String> UPDATE_USER_ROOM_SCRIPT = RedisScript.of("""
            local oldRoom = redis.call('GET', KEYS[1])
            if oldRoom and oldRoom ~= '' then
              redis.call('SREM', ARGV[3] .. oldRoom .. ARGV[4], ARGV[2])
            end
            if ARGV[1] and ARGV[1] ~= '' and ARGV[1] ~= '0' then
              redis.call('SET', KEYS[1], ARGV[1])
              redis.call('SADD', ARGV[3] .. ARGV[1] .. ARGV[4], ARGV[2])
            else
              redis.call('DEL', KEYS[1])
            end
            return oldRoom or ''
            """, String.class);

    private final StringRedisTemplate redisTemplate;
    private final ConcurrentHashMap<Long, CachedRoute> routeCache = new ConcurrentHashMap<>();

    public void updateUserRoom(Long userId, Long roomId) {
        if (userId == null) {
            return;
        }

        redisTemplate.execute(
                UPDATE_USER_ROOM_SCRIPT,
                List.of(USER_ROOM_PREFIX + userId),
                roomId == null || roomId <= 0 ? "" : String.valueOf(roomId),
                String.valueOf(userId),
                ROOM_USERS_PREFIX,
                ROOM_USERS_SUFFIX
        );
    }

    public Set<String> getRoomUserIds(Long roomId) {
        if (roomId == null || roomId <= 0) {
            return Collections.emptySet();
        }
        Set<String> members = redisTemplate.opsForSet().members(roomUsersKey(String.valueOf(roomId)));
        return members == null ? Collections.emptySet() : members;
    }

    public String getGatewayIdForUser(Long userId) {
        Route route = getRoute(userId);
        return route == null ? null : route.gatewayId();
    }

    public String getClientIp(Long userId) {
        Route route = getRoute(userId);
        return route == null || route.clientIp().isBlank() ? null : route.clientIp();
    }

    public boolean isUserRouted(Long userId) {
        return getGatewayIdForUser(userId) != null;
    }

    public boolean isCurrentConnection(Long userId, String connectionId) {
        Route route = getRouteFresh(userId);
        return route != null && route.connectionId().equals(connectionId);
    }

    public boolean isRouteMissingOrCurrentConnection(Long userId, String connectionId) {
        Route route = getRouteFresh(userId);
        return route == null || route.connectionId().equals(connectionId);
    }

    public int countActiveRoutes() {
        Long count = redisTemplate.execute((RedisConnection connection) -> {
            long total = 0;
            ScanOptions options = ScanOptions.scanOptions()
                    .match(USER_ROUTE_PREFIX + "*")
                    .count(1000)
                    .build();
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                while (cursor.hasNext()) {
                    cursor.next();
                    total++;
                }
            }
            return total;
        });
        return count == null ? 0 : Math.toIntExact(count);
    }

    private static String roomUsersKey(String roomId) {
        return ROOM_USERS_PREFIX + roomId + ROOM_USERS_SUFFIX;
    }

    private Route getRoute(Long userId) {
        if (userId == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        CachedRoute cached = routeCache.get(userId);
        if (cached != null && now - cached.cachedAtMillis() < USER_ROUTE_CACHE_MILLIS) {
            return cached.route();
        }

        String encoded = redisTemplate.opsForValue().get(USER_ROUTE_PREFIX + userId);
        Route route = decode(encoded);
        routeCache.put(userId, new CachedRoute(route, now));
        return route;
    }

    private Route getRouteFresh(Long userId) {
        if (userId == null) {
            return null;
        }
        String encoded = redisTemplate.opsForValue().get(USER_ROUTE_PREFIX + userId);
        Route route = decode(encoded);
        routeCache.put(userId, new CachedRoute(route, System.currentTimeMillis()));
        return route;
    }

    private static Route decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] parts = encoded.split("\\|", 3);
        if (parts.length < 2 || parts[0].isBlank()) {
            return null;
        }
        return new Route(parts[0], parts[1], parts.length == 3 ? parts[2] : "");
    }

    private record Route(String gatewayId, String connectionId, String clientIp) {
    }

    private record CachedRoute(Route route, long cachedAtMillis) {
    }
}
