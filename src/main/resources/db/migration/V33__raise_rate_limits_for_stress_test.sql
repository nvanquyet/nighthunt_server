-- V33: Raise rate limits for stress testing (500 VUs from 1 IP)
-- Login: 500 VUs x ~10 iter/min = ~5000 logins/min. Need > 5000.
UPDATE rate_limit_rules
SET max_requests = 10000
WHERE endpoint_pattern = '/auth/login' AND method = 'POST';

-- Register: SETUP phase 500 VUs all register at once (ramp=10s) = ~3000/min from 1 IP.
UPDATE rate_limit_rules
SET max_requests = 600, window_seconds = 60
WHERE endpoint_pattern = '/auth/register' AND method = 'POST';

-- Profile: authenticated users use per-user limit (fine).
-- But if TOKEN=INVALID (unauthenticated) it falls to IP-based 60/min - raise for stress test.
UPDATE rate_limit_rules
SET max_requests = 10000
WHERE endpoint_pattern = '/profile' AND method = 'GET';

-- Friends: same reasoning.
UPDATE rate_limit_rules
SET max_requests = 10000
WHERE endpoint_pattern = '/friends' AND method = 'GET';

UPDATE rate_limit_rules
SET max_requests = 10000
WHERE endpoint_pattern = '/friends/*';

-- Wildcard catch-all: raise to avoid unexpected throttling under stress.
UPDATE rate_limit_rules
SET max_requests = 50000
WHERE endpoint_pattern = '/*' AND method = '*';
