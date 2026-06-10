#!/usr/bin/env bash
# Production matchmaking snapshot: backend logs, queue/config DB state, Redis
# matcher lock, and active Dedicated Server allocation state.
#
# Usage:
#   bash scripts/check-matchmaking.sh [since] [env-file]
# Example:
#   bash scripts/check-matchmaking.sh 15m .env.production

set -euo pipefail

SINCE="${1:-15m}"
ENV_FILE="${2:-.env.production}"
COMPOSE=(docker compose --env-file "$ENV_FILE")

section() {
    printf '\n===== %s =====\n' "$1"
}

section "Container state"
"${COMPOSE[@]}" ps backend mysql redis

section "Recent matchmaking and DS logs (${SINCE})"
"${COMPOSE[@]}" logs --since "$SINCE" backend \
    | grep -E '\[MM\]|\[DS-Alloc\]|\[DS-Svc\]|\[AdminConfig\]\[(GAME_MODE|ZONE_CONFIG)\]|match_ready|ds_ready' \
    || true

section "Recent dedicated-server runtime logs (${SINCE})"
"${COMPOSE[@]}" exec -T mysql sh -lc '
mysql --protocol=TCP -h127.0.0.1 \
  -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" \
  --batch --raw --skip-column-names \
  -e "SELECT docker_container_id FROM dedicated_servers WHERE docker_container_id IS NOT NULL AND docker_container_id <> \"\" AND status <> \"stopped\" ORDER BY started_at DESC LIMIT 5;"
' | while IFS= read -r container_id; do
    [ -n "$container_id" ] || continue
    printf '\n--- %s ---\n' "$container_id"
    docker logs --since "$SINCE" "$container_id" 2>&1 \
        | grep -E '\[DS-Boot\]|\[SafeZone|\[ServerGameManager\]' \
        || true
done

section "1v1 config, map zone config, migrations, live queue, and active DS rows"
"${COMPOSE[@]}" exec -T mysql sh -lc '
mysql --protocol=TCP -h127.0.0.1 \
  -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" --table <<'"'"'SQL'"'"'
SELECT mode_key, display_name, players_per_team, total_players,
       mode_status, is_active, matchmaking_enabled, allow_fill, platform_filter
FROM game_modes
WHERE mode_key = "1v1";

SELECT map_id, display_name, scene_name, is_active, is_locked,
       JSON_UNQUOTE(JSON_EXTRACT(supported_modes, "$")) AS supported_modes,
       JSON_UNQUOTE(JSON_EXTRACT(supported_player_counts, "$")) AS player_counts,
       JSON_UNQUOTE(JSON_EXTRACT(zone_config, "$.initialRadius")) AS initial_radius,
       JSON_UNQUOTE(JSON_EXTRACT(zone_config, "$.finalZoneMinRadius")) AS final_min_radius,
       JSON_LENGTH(zone_config, "$.phases") AS phase_count,
       JSON_UNQUOTE(JSON_EXTRACT(zone_config, "$.phases[0].waitBeforeShrink")) AS p0_wait,
       JSON_UNQUOTE(JSON_EXTRACT(zone_config, "$.phases[0].shrinkDuration")) AS p0_shrink,
       JSON_UNQUOTE(JSON_EXTRACT(zone_config, "$.phases[0].damagePerSecond")) AS p0_damage
FROM game_maps
ORDER BY display_order, map_id;

SELECT version, description, success, installed_on
FROM flyway_schema_history
WHERE version IN ("20", "26", "40", "45")
ORDER BY installed_rank;

SELECT q.id, q.user_id, u.username, q.elo,
       q.search_min_elo, q.search_max_elo,
       q.game_mode, q.map_id, q.platform,
       q.queue_group_id, q.party_size, q.allow_fill, q.status,
       TIMESTAMPDIFF(SECOND, q.queued_at, NOW()) AS wait_sec,
       q.queued_at
FROM matchmaking_queue q
LEFT JOIN users u ON u.id = q.user_id
ORDER BY q.queued_at;

SELECT server_id, status, region, map_id, match_id, ip, port,
       current_players, max_players,
       TIMESTAMPDIFF(SECOND, COALESCE(last_heartbeat_at, started_at), NOW()) AS heartbeat_age_sec,
       started_at
FROM dedicated_servers
WHERE status <> "stopped"
ORDER BY started_at DESC
LIMIT 20;
SQL
'

section "Redis matcher lock"
"${COMPOSE[@]}" exec -T redis sh -lc '
if [ -n "${REDIS_PASSWORD:-}" ]; then
  export REDISCLI_AUTH="$REDIS_PASSWORD"
fi
printf "key:  "
redis-cli --no-auth-warning GET lock:matchmaking:tick
printf "pttl: "
redis-cli --no-auth-warning PTTL lock:matchmaking:tick
'

section "Interpretation"
cat <<'EOF'
- TICK_BLOCKED: Redis matcher lock is preventing matcher ticks.
- ORPHAN_QUEUE: queued mode is not active/AVAILABLE/matchmaking-enabled.
- ENQUEUE_REJECTED: backend read the mode as inactive/unavailable when the client tried to queue.
- NO_MATCH_PAIRS: exact ELO/map/platform/group rejection reason.
- MATCH_FORMED without MATCH_READY: room/DS allocation pipeline is blocked or failed.
- MATCH_READY without client transition: inspect realtime gateway/WebSocket delivery.
- [AdminConfig][ZONE_CONFIG] but no [DS-Boot][ZONE_CONFIG]: dashboard saved DB, but no fresh DS consumed it yet.
- [DS-Boot][ZONE_CONFIG] applied fallback: backend endpoint/JSON failed or DS used a mapId with no valid zone_config.
EOF
