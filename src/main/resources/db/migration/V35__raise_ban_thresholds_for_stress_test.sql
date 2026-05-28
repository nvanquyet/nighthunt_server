-- Raise ban thresholds for stress testing with 500+ concurrent VUs.
-- All logins come from the same IP (JMeter machine), so concurrent login
-- detection must be raised significantly above the VU count.

UPDATE ban_config SET config_value = '10000' WHERE config_key = 'MAX_CONCURRENT_LOGIN_ATTEMPTS';
UPDATE ban_config SET config_value = '10000' WHERE config_key = 'MAX_FAILED_LOGIN_ATTEMPTS';
UPDATE ban_config SET config_value = '600'   WHERE config_key = 'CONCURRENT_LOGIN_WINDOW_SECONDS';

-- Clear any stale IP bans from previous stress test runs
UPDATE bans SET is_active = 0 WHERE ban_type = 'IP';

-- Clear concurrent login attempt counters
TRUNCATE TABLE concurrent_login_attempts;
