-- ============================================================
-- cleanup-stale-data.sql
-- One-shot cleanup script for stale/fake data.
--
-- USAGE (on VPS):
--   docker exec -i nighthunt_server-db-1 mysql \
--     -u$MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE \
--     < /opt/nighthunt/scripts/cleanup-stale-data.sql
--
-- Safe: does NOT delete users or completed match history.
-- ============================================================

SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
START TRANSACTION;

-- ── 1. Evict all room_players from non-active rooms ──────────────────────────
DELETE rp FROM room_players rp
INNER JOIN rooms r ON r.id = rp.room_id
WHERE r.status NOT IN ('WAITING', 'IN_GAME');

-- ── 2. Evict room_players for rooms that somehow still have "active" status
--       but no user in the system (orphan rows)
DELETE rp FROM room_players rp
LEFT JOIN users u ON u.id = rp.user_id
WHERE u.id IS NULL;

-- ── 3. Close all WAITING/IN_GAME rooms that have zero room_players ───────────
UPDATE rooms
SET status = 'CLOSED', updated_at = NOW()
WHERE status IN ('WAITING', 'IN_GAME')
  AND id NOT IN (SELECT DISTINCT room_id FROM room_players);

-- ── 4. Close all WAITING/IN_GAME rooms that were created more than 24 hours
--       ago (leftover test/fake rooms regardless of player count) ─────────────
UPDATE rooms
SET status = 'CLOSED', updated_at = NOW()
WHERE status IN ('WAITING', 'IN_GAME')
  AND created_at < DATE_SUB(NOW(), INTERVAL 24 HOUR);

-- ── 5. Mark stuck LOBBY/IN_GAME matches as FINISHED ─────────────────────────
UPDATE matches
SET status = 'FINISHED',
    end_reason = 'ABANDONED_CLEANUP',
    finished_at = COALESCE(finished_at, NOW()),
    updated_at = NOW()
WHERE status IN ('LOBBY', 'IN_GAME')
  AND (
      -- Room is no longer active
      room_id NOT IN (SELECT id FROM rooms WHERE status IN ('WAITING', 'IN_GAME'))
      -- Or match has been open for more than 4 hours
      OR created_at < DATE_SUB(NOW(), INTERVAL 4 HOUR)
  );

-- ── 6. Remove dedicated_servers with no heartbeat and status != stopped ──────
UPDATE dedicated_servers
SET status = 'stopped', stopped_at = COALESCE(stopped_at, NOW())
WHERE status IN ('ready', 'in_game', 'starting')
  AND (last_heartbeat_at IS NULL OR last_heartbeat_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE));

COMMIT;

-- ── Summary ──────────────────────────────────────────────────────────────────
SELECT 'rooms' AS entity,
       SUM(status = 'CLOSED')   AS closed,
       SUM(status = 'WAITING')  AS waiting,
       SUM(status = 'IN_GAME')  AS in_game,
       SUM(status = 'FINISHED') AS finished
FROM rooms
UNION ALL
SELECT 'matches',
       0,
       SUM(status = 'LOBBY'),
       SUM(status = 'IN_GAME'),
       SUM(status = 'FINISHED')
FROM matches
UNION ALL
SELECT 'dedicated_servers',
       SUM(status = 'stopped'),
       SUM(status = 'ready'),
       SUM(status = 'in_game'),
       SUM(status = 'starting')
FROM dedicated_servers;
