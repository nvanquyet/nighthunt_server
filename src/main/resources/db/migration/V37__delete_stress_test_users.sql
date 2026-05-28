-- V37: Delete stress test users so SETUP phase can re-register them cleanly.
-- nh_stress_1..500 were created in v1/v2 JMeter test runs.
-- Some rows may already have been deleted by the partial V37 run; DELETEs with
-- no matching rows are safe no-ops.

-- 1. Sessions, refresh tokens, activity
DELETE FROM sessions WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');
DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');
DELETE FROM user_activity_logs WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');
DELETE FROM user_abandon_records WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');

-- 2. Social / match data  (use correct column names from actual schema)
DELETE FROM friend_requests WHERE requester_user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%')
                               OR addressee_user_id  IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');
DELETE FROM friends WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%')
                       OR friend_user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');
DELETE FROM blocked_users WHERE blocker_user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%')
                             OR blocked_user_id  IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');

-- 3. Parties / rooms
DELETE FROM party_invitations WHERE inviter_user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%')
                                 OR invitee_user_id  IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');
DELETE FROM party_members WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');
DELETE FROM room_players WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');
DELETE FROM matchmaking_queue WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');
DELETE FROM swap_requests WHERE requester_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%')
                             OR target_id     IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');

-- 4. Match results
DELETE FROM match_player_results WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'nh_stress_%');

-- 5. Finally delete the users themselves
DELETE FROM users WHERE username LIKE 'nh_stress_%';
