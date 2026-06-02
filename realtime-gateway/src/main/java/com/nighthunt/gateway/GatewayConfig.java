package com.nighthunt.gateway;

import java.time.Duration;
import java.util.Map;

public record GatewayConfig(
        String gatewayId,
        int port,
        int metricsPort,
        int acceptBacklog,
        String redisUri,
        String natsUrl,
        String natsSubjectPrefix,
        String wsPath,
        int maxFrameBytes,
        long maxPendingOutboundBytes,
        Duration heartbeatTimeout,
        Duration presenceLease
) {
    public static GatewayConfig fromEnvironment() {
        return from(System.getenv());
    }

    static GatewayConfig from(Map<String, String> env) {
        return new GatewayConfig(
                value(env, "GATEWAY_ID", "gateway-1"),
                integer(env, "GATEWAY_PORT", 8090),
                integer(env, "GATEWAY_METRICS_PORT", 9091),
                integer(env, "WS_ACCEPT_BACKLOG", 8192),
                redisUri(env),
                natsUrl(env),
                value(env, "NATS_SUBJECT_PREFIX", "rt.gateway"),
                value(env, "WS_PATH", "/api/ws/game"),
                integer(env, "WS_MAX_FRAME_BYTES", 4096),
                longValue(env, "WS_MAX_PENDING_OUTBOUND_BYTES", 262_144L),
                Duration.ofSeconds(longValue(env, "WS_HEARTBEAT_TIMEOUT_SECONDS", 45L)),
                Duration.ofSeconds(longValue(env, "WS_PRESENCE_LEASE_SECONDS", 60L))
        );
    }

    private static String value(Map<String, String> env, String key, String fallback) {
        String value = env.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String redisUri(Map<String, String> env) {
        String uri = env.get("REDIS_URI");
        if (uri != null && !uri.isBlank()) {
            return uri.trim();
        }

        String host = value(env, "REDIS_HOST", "localhost");
        String port = value(env, "REDIS_PORT", "6379");
        String password = value(env, "REDIS_PASSWORD", "");
        if (password.isBlank()) {
            return "redis://" + host + ":" + port;
        }
        return "redis://:" + password + "@" + host + ":" + port;
    }

    private static String natsUrl(Map<String, String> env) {
        return value(env, "GATEWAY_NATS_URL", value(env, "NATS_URL", "nats://localhost:4222"));
    }

    private static int integer(Map<String, String> env, String key, int fallback) {
        return Integer.parseInt(value(env, key, String.valueOf(fallback)));
    }

    private static long longValue(Map<String, String> env, String key, long fallback) {
        return Long.parseLong(value(env, key, String.valueOf(fallback)));
    }
}
