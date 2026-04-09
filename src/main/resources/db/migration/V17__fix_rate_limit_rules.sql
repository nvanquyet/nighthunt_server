-- =====================================================
-- Fix Rate Limiting Rules
-- =====================================================
-- V17: Multiple fixes:
--   1. Rename /api/* → /* (interceptor now uses getServletPath, no /api prefix)
--   2. Increase limits x10–x20 to avoid false positives during normal gameplay
--   3. Add specific rules for /friends/* and /party/* endpoints
--   4. Keep window in seconds but now measured properly per minute (= 60s)
-- =====================================================

-- ── 1. Rename general catch-all pattern ──────────────────────────────────────
-- Old pattern '/api/*' never matched because interceptor now uses servlet path.
-- Rename it to '/*' which will match everything not caught by a specific rule.
UPDATE rate_limit_rules
SET endpoint_pattern = '/*',
    max_requests     = 2000,
    window_seconds   = 60,
    description      = 'General API catch-all: 2000 req/min per user',
    updated_at       = CURRENT_TIMESTAMP
WHERE endpoint_pattern = '/api/*';

-- ── 2. Update Auth endpoint limits (x10) ─────────────────────────────────────
-- Login: was 5/min → now 50/min per IP  (enough for re-tries, auto-reconnect)
UPDATE rate_limit_rules
SET max_requests   = 50,
    window_seconds = 60,
    description    = 'Login: 50 attempts/min per IP',
    updated_at     = CURRENT_TIMESTAMP
WHERE endpoint_pattern = '/auth/login';

-- Register: keep conservative per hour but raise from 3 → 10 per hour
UPDATE rate_limit_rules
SET max_requests   = 10,
    window_seconds = 3600,
    description    = 'Registration: 10 attempts/hour per IP',
    updated_at     = CURRENT_TIMESTAMP
WHERE endpoint_pattern = '/auth/register';

-- Auto-login / refresh: was 10/min → 120/min (2 per second, client polls on reconnect)
UPDATE rate_limit_rules
SET max_requests   = 120,
    window_seconds = 60,
    description    = 'Auto-login/session-check: 120 req/min per user',
    updated_at     = CURRENT_TIMESTAMP
WHERE endpoint_pattern = '/auth/auto-login';

-- Change password: keep strict
UPDATE rate_limit_rules
SET max_requests   = 10,
    window_seconds = 300,
    description    = 'Password change: 10 attempts per 5 min per user',
    updated_at     = CURRENT_TIMESTAMP
WHERE endpoint_pattern = '/auth/change-password';

-- ── 3. Update Room endpoint limits (x10) ─────────────────────────────────────
UPDATE rate_limit_rules
SET max_requests = 60,  window_seconds = 60, description = 'Room create: 60/min per user', updated_at = CURRENT_TIMESTAMP
WHERE endpoint_pattern = '/rooms/create';

UPDATE rate_limit_rules
SET max_requests = 120, window_seconds = 60, description = 'Join room by code: 120/min per user', updated_at = CURRENT_TIMESTAMP
WHERE endpoint_pattern = '/rooms/join-by-code';

UPDATE rate_limit_rules
SET max_requests = 120, window_seconds = 60, description = 'Quick play: 120/min per user', updated_at = CURRENT_TIMESTAMP
WHERE endpoint_pattern = '/rooms/quick-play';

UPDATE rate_limit_rules
SET max_requests = 300, window_seconds = 60, description = 'Set ready: 300/min per user', updated_at = CURRENT_TIMESTAMP
WHERE endpoint_pattern = '/rooms/*/ready';

UPDATE rate_limit_rules
SET max_requests = 200, window_seconds = 60, description = 'Change team: 200/min per user', updated_at = CURRENT_TIMESTAMP
WHERE endpoint_pattern = '/rooms/*/change-team';

UPDATE rate_limit_rules
SET max_requests = 30,  window_seconds = 60, description = 'Start game: 30/min per user', updated_at = CURRENT_TIMESTAMP
WHERE endpoint_pattern = '/rooms/*/start';

-- ── 4. Insert Friend System rules ─────────────────────────────────────────────
-- GET /friends  — list friends (client may call on tab open, login, WS reconnect)
INSERT INTO rate_limit_rules (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description)
VALUES ('/friends', 'GET', 'FIXED_WINDOW', 120, 60, 'USER', 'Get friend list: 120/min per user')
ON DUPLICATE KEY UPDATE max_requests = 120, window_seconds = 60, updated_at = CURRENT_TIMESTAMP;

-- POST /friends/requests  — send friend request
INSERT INTO rate_limit_rules (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description)
VALUES ('/friends/requests', 'POST', 'FIXED_WINDOW', 60, 60, 'USER', 'Send friend request: 60/min per user')
ON DUPLICATE KEY UPDATE max_requests = 60, window_seconds = 60, updated_at = CURRENT_TIMESTAMP;

-- GET /friends/requests/*  — list incoming/outgoing requests
INSERT INTO rate_limit_rules (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description)
VALUES ('/friends/requests/*', 'GET', 'FIXED_WINDOW', 120, 60, 'USER', 'Get friend requests: 120/min per user')
ON DUPLICATE KEY UPDATE max_requests = 120, window_seconds = 60, updated_at = CURRENT_TIMESTAMP;

-- POST /friends/requests/* (accept/decline actions)
INSERT INTO rate_limit_rules (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description)
VALUES ('/friends/requests/*', 'POST', 'FIXED_WINDOW', 120, 60, 'USER', 'Accept/decline friend request: 120/min per user')
ON DUPLICATE KEY UPDATE max_requests = 120, window_seconds = 60, updated_at = CURRENT_TIMESTAMP;

-- Catch-all for all other /friends/* actions (remove, block, etc.)
INSERT INTO rate_limit_rules (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description)
VALUES ('/friends/*', '*', 'FIXED_WINDOW', 120, 60, 'USER', 'Friend misc actions: 120/min per user')
ON DUPLICATE KEY UPDATE max_requests = 120, window_seconds = 60, updated_at = CURRENT_TIMESTAMP;

-- ── 5. Insert Party System rules ──────────────────────────────────────────────
-- GET /party/current  — polled when opening party panel or on reconnect
INSERT INTO rate_limit_rules (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description)
VALUES ('/party/current', 'GET', 'FIXED_WINDOW', 300, 60, 'USER', 'Get party state: 300/min per user (5/sec)')
ON DUPLICATE KEY UPDATE max_requests = 300, window_seconds = 60, updated_at = CURRENT_TIMESTAMP;

-- POST /party/create
INSERT INTO rate_limit_rules (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description)
VALUES ('/party/create', 'POST', 'FIXED_WINDOW', 60, 60, 'USER', 'Create party: 60/min per user')
ON DUPLICATE KEY UPDATE max_requests = 60, window_seconds = 60, updated_at = CURRENT_TIMESTAMP;

-- POST /party/invite  — leader invites members rapidly
INSERT INTO rate_limit_rules (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description)
VALUES ('/party/invite', 'POST', 'FIXED_WINDOW', 300, 60, 'USER', 'Party invite: 300/min per user')
ON DUPLICATE KEY UPDATE max_requests = 300, window_seconds = 60, updated_at = CURRENT_TIMESTAMP;

-- Party invitation responses (accept/decline)
INSERT INTO rate_limit_rules (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description)
VALUES ('/party/invitations/*', '*', 'FIXED_WINDOW', 120, 60, 'USER', 'Party invitation response: 120/min per user')
ON DUPLICATE KEY UPDATE max_requests = 120, window_seconds = 60, updated_at = CURRENT_TIMESTAMP;

-- Party queue/cancel-queue/join-room/disband/leave/kick   — frequent rapid calls
INSERT INTO rate_limit_rules (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description)
VALUES ('/party/*', '*', 'FIXED_WINDOW', 300, 60, 'USER', 'Party operations catch-all: 300/min per user')
ON DUPLICATE KEY UPDATE max_requests = 300, window_seconds = 60, updated_at = CURRENT_TIMESTAMP;

-- ── 6. Token refresh (new endpoint) ──────────────────────────────────────────
INSERT INTO rate_limit_rules (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description)
VALUES ('/auth/refresh-token', 'POST', 'FIXED_WINDOW', 120, 60, 'USER', 'Token refresh: 120/min per user')
ON DUPLICATE KEY UPDATE max_requests = 120, window_seconds = 60, updated_at = CURRENT_TIMESTAMP;
