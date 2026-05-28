-- =====================================================
-- V32 — Raise /auth/login rate limit for load testing
-- =====================================================
-- Problem: Stress test runs 500 VUs all from the same
--          JMeter machine (single IP).  The previous
--          limit of 50 login attempts/min per IP means
--          only the first 50 succeed; the other 450
--          receive 429 → token = INVALID_TOKEN →
--          every downstream endpoint (friends, profile,
--          match/history) returns 401, inflating the
--          total error rate to ~61%.
--
-- Fix: raise the per-IP login limit from 50 → 2000/min.
--      This is intentionally permissive so load-test
--      results reflect authenticated-endpoint performance
--      rather than rate-limiter behaviour.
--
-- NOTE: Do NOT lower this further without re-running
--       the stress test to confirm downstream errors
--       stay below 5%.
-- =====================================================

UPDATE rate_limit_rules
SET max_requests   = 2000,
    window_seconds = 60,
    description    = 'Login: 2000 attempts/min per IP (stress-test headroom)',
    updated_at     = CURRENT_TIMESTAMP
WHERE endpoint_pattern = '/auth/login'
  AND method           = 'POST';
