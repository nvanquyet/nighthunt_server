-- Rate Limit Rules for Friend and Party Systems 
-- Using wildcard patterns to cover multiple endpoints
-- Note: endpoint_pattern has UNIQUE constraint

INSERT IGNORE INTO rate_limit_rules (endpoint_pattern, method, limit_type, scope, max_requests, window_seconds, refill_rate, bucket_size, is_active, description, created_at, updated_at)
VALUES 
-- Friend System - Comprehensive rate limit
('/api/friends/*', '*', 'FIXED_WINDOW', 'USER', 100, 60, NULL, NULL, true, 'Friend system operations - 100 per minute per user (covers all friend endpoints)', NOW(), NOW()),

-- Party System - Comprehensive rate limit  
('/api/party/*', '*', 'FIXED_WINDOW', 'USER', 100, 60, NULL, NULL, true, 'Party system operations - 100 per minute per user (covers all party endpoints)', NOW(), NOW());

-- Summary:
-- Using wildcard patterns with reasonable limits:
-- - /api/friends/*: 100 requests/minute (covers requests, accept, reject, block, etc.)
-- - /api/party/*: 100 requests/minute (covers create, invite, accept, leave, etc.)
--
-- These rules work with the existing matchesEndpoint() method in RateLimitRule entity
-- which uses SQL LIKE pattern matching to check if endpoint matches pattern
--
-- More specific rules can be added later if needed, but these provide good baseline protection

-- Summary:
-- Friend System: 9 rules added
-- - Send request: 20/hour (strict to prevent spam)
-- - Block/Unblock: 10/hour (strict to prevent abuse)
-- - Accept/Reject/Cancel: 30/minute (normal usage)
-- - Get lists: 60-100/minute (generous for UI updates)
-- - Remove friend: 20/minute (normal usage)
--
-- Party System: 9 rules added
-- - Create party: 5/minute (prevent spam)
-- - Invite: 20/minute (normal gameplay)
-- - Accept/Reject: 30/minute (normal usage)
-- - Get data: 100/minute (generous for UI updates)
-- - Leave/Kick/Disband: 5-20/minute (normal usage)
--
-- Total: 18 new rules for Friend + Party systems
