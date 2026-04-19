-- =====================================================
-- V25 — Fix rate limit: add missing endpoint rules
--       and repair catch-all bucket.
-- =====================================================
-- Root causes addressed:
--   1. /game-modes, /maps, /profile, /matchmaking/* had NO specific rule.
--      All shared the catch-all /*  which meant any burst on unrelated
--      endpoints could exhaust quota for critical game actions.
--   2. The catch-all /* max_requests is confirmed / raised to 3000 to give
--      more headroom while the specific rules now handle hot endpoints.
--   3. RequestQueueInterceptor double-counted every request (fixed in code).
--      This migration compensates by not reducing limits further.
-- =====================================================

-- ── 1. Ensure catch-all has correct limit (idempotent) ───────────────────────
-- Repair in case V17 UPDATE did not find the row (e.g. already renamed
-- or DB was in a different state). Use UPSERT so it always ends up correct.
INSERT INTO rate_limit_rules
    (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description)
VALUES
    ('/*', '*', 'FIXED_WINDOW', 3000, 60, 'USER',
     'General API catch-all: 3000 req/min per user')
ON DUPLICATE KEY UPDATE
    max_requests     = 3000,
    window_seconds   = 60,
    description      = 'General API catch-all: 3000 req/min per user',
    updated_at       = CURRENT_TIMESTAMP;

-- ── 2. Game config endpoints (public, called once at startup) ─────────────────
-- GET /game-modes — fetched once per session at login; isolated bucket
INSERT INTO rate_limit_rules
    (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description)
VALUES
    ('/game-modes', 'GET', 'FIXED_WINDOW', 30, 60, 'IP',
     'Game modes config: 30 req/min per IP (public endpoint)')
ON DUPLICATE KEY UPDATE
    max_requests = 30, window_seconds = 60, updated_at = CURRENT_TIMESTAMP;

-- GET /maps — fetched once per session at login; isolated bucket
INSERT INTO rate_limit_rules
    (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description)
VALUES
    ('/maps', 'GET', 'FIXED_WINDOW', 30, 60, 'IP',
     'Maps config: 30 req/min per IP (public endpoint)')
ON DUPLICATE KEY UPDATE
    max_requests = 30, window_seconds = 60, updated_at = CURRENT_TIMESTAMP;

-- ── 3. User profile ───────────────────────────────────────────────────────────
-- GET /profile — fetched once after login and on reconnect
INSERT INTO rate_limit_rules
    (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description)
VALUES
    ('/profile', 'GET', 'FIXED_WINDOW', 60, 60, 'USER',
     'User profile: 60 req/min per user')
ON DUPLICATE KEY UPDATE
    max_requests = 60, window_seconds = 60, updated_at = CURRENT_TIMESTAMP;

-- ── 4. Matchmaking ────────────────────────────────────────────────────────────
-- NOTE: endpoint_pattern has a UNIQUE constraint, so we cannot have separate
--       rows for POST and DELETE on the same pattern. Use method='*' to cover
--       all verbs (join queue POST, cancel queue DELETE) under one rule.
INSERT INTO rate_limit_rules
    (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description)
VALUES
    ('/matchmaking/queue', '*', 'FIXED_WINDOW', 20, 60, 'USER',
     'Matchmaking queue (join + cancel): 20 req/min per user')
ON DUPLICATE KEY UPDATE
    method       = '*',
    max_requests = 20,
    window_seconds = 60,
    updated_at   = CURRENT_TIMESTAMP;

-- GET /matchmaking/* — polling queue status (broader pattern, lower priority)
INSERT INTO rate_limit_rules
    (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description)
VALUES
    ('/matchmaking/*', '*', 'FIXED_WINDOW', 120, 60, 'USER',
     'Matchmaking status poll: 120 req/min per user')
ON DUPLICATE KEY UPDATE
    method       = '*',
    max_requests = 120,
    window_seconds = 60,
    updated_at   = CURRENT_TIMESTAMP;

-- ── 5. Clear stale tracking rows to reset any accumulated counts ──────────────
-- Purge tracking for catch-all rule so current sessions start fresh.
-- This is safe: tracking rows are recreated on next request.
DELETE FROM rate_limit_tracking
WHERE window_end < NOW();
