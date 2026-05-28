-- Clear duplicate rows created by concurrent requests during stress test.
-- NonUniqueResultException (same bug as rate_limit_tracking / V34) affects:
--   - concurrent_login_attempts: multiple rows per same IP due to concurrent inserts
--   - failed_login_attempts: multiple rows per identifier+ipAddress due to race condition
--
-- Root fix: all findBy* queries changed to findFirst*OrderBy* in repositories.
-- This migration clears stale data so the fixed queries work immediately.

TRUNCATE TABLE concurrent_login_attempts;
TRUNCATE TABLE failed_login_attempts;
