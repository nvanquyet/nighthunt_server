-- Fix duplicate rate_limit_tracking records caused by concurrent-request race condition.
-- Multiple concurrent requests could all see Optional.empty() and each insert a new row,
-- leading to IncorrectResultSizeDataAccessException on the next findBy call.
-- The interceptor caught that exception as a generic 429, blocking ALL requests.

-- Clear all stale tracking data to start fresh
TRUNCATE TABLE rate_limit_tracking;

-- Clear token bucket state as well
TRUNCATE TABLE rate_limit_token_buckets;
