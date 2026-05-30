-- Speed up auth hot-path lookups used on every login.
-- The existing single-column indexes do not cover the repository query patterns.

CREATE INDEX idx_failed_login_identifier_ip_time
    ON failed_login_attempts(identifier, ip_address, last_attempt_at);

CREATE INDEX idx_concurrent_login_ip_window
    ON concurrent_login_attempts(ip_address, window_end);