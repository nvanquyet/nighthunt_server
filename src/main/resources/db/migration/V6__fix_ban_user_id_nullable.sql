-- Migration V6: Make bans.user_id nullable
-- Reason: IP and device bans may not be tied to a specific user account
-- (e.g., auto-ban during failed login before user identity is verified)
ALTER TABLE bans MODIFY COLUMN user_id BIGINT NULL;
