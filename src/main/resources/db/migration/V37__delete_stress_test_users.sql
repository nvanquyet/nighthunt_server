-- V37: Delete stress test users so SETUP phase can re-register them cleanly.
-- nh_stress_1..500 were created in v1/v2 JMeter test runs.
-- Cascading delete order to avoid FK constraint violations.

-- 1. Sessions, refresh tokens, activity
DELETE FROM sessions WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');
DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');
DELETE FROM user_activity_logs WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');
DELETE FROM user_abandon_records WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');

-- 2. Social / match data
DELETE FROM friend_requests WHERE sender_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%')
                               OR receiver_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');
DELETE FROM friends WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%')
                       OR friend_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');
DELETE FROM blocked_users WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%')
                             OR blocked_user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');

-- 3. Parties / rooms
DELETE FROM party_invitations WHERE inviter_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%')
                                 OR invitee_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');
DELETE FROM party_members WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');
DELETE FROM room_players WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');
DELETE FROM matchmaking_queue WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');
DELETE FROM swap_requests WHERE requester_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%')
                             OR target_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');

-- 4. Match results
DELETE FROM match_player_results WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');

-- 5. Ban records
DELETE FROM failed_login_attempts WHERE identifier IN (SELECT username FROM users WHERE username LIKE 'nh_stress_%');
DELETE FROM concurrent_login_attempts WHERE id IN (
    SELECT cla.id FROM (SELECT id FROM concurrent_login_attempts) cla
);  -- already truncated by V36; keep empty

-- 6. Rate-limit tracking for stress test IPs (cleanup stale data)
-- Not user-scoped, skip.

-- 7. Finally delete the users themselves
DELETE FROM users WHERE username LIKE 'nh_stress_%';
