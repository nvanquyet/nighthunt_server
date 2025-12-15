-- =====================================================
-- Ban/Block System & Rate Limiting Database Schema
-- =====================================================
-- Flyway Migration: V1
-- Description: Tables for ban system and rate limiting configuration
-- Compatible with: MySQL, PostgreSQL, H2 (for testing)
-- =====================================================

-- =====================================================
-- 1. BAN SYSTEM TABLES
-- =====================================================

-- Ban records table
CREATE TABLE bans (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    ban_type VARCHAR(20) NOT NULL, -- 'USER', 'IP', 'DEVICE'
    ip_address VARCHAR(45), -- IPv6 max length
    device_fingerprint VARCHAR(255),
    reason VARCHAR(255) NOT NULL,
    ban_duration_minutes INT NOT NULL, -- 0 = permanent
    banned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP, -- NULL = permanent
    banned_by BIGINT, -- Admin user ID, NULL = auto-ban
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    auto_unbanned BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_user_id (user_id),
    INDEX idx_ip_address (ip_address),
    INDEX idx_device_fingerprint (device_fingerprint),
    INDEX idx_expires_at (expires_at),
    INDEX idx_is_active (is_active),
    INDEX idx_ban_type (ban_type)
);

-- Failed login attempts tracking
CREATE TABLE failed_login_attempts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    identifier VARCHAR(255) NOT NULL, -- username or email
    ip_address VARCHAR(45) NOT NULL,
    device_fingerprint VARCHAR(255),
    attempt_count INT NOT NULL DEFAULT 1,
    first_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_banned BOOLEAN NOT NULL DEFAULT FALSE,
    ban_id BIGINT, -- Reference to bans table
    
    INDEX idx_identifier (identifier),
    INDEX idx_ip_address (ip_address),
    INDEX idx_device_fingerprint (device_fingerprint),
    INDEX idx_last_attempt_at (last_attempt_at),
    INDEX idx_is_banned (is_banned),
    FOREIGN KEY (ban_id) REFERENCES bans(id) ON DELETE SET NULL
);

-- Concurrent login attempts tracking
CREATE TABLE concurrent_login_attempts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ip_address VARCHAR(45) NOT NULL,
    device_fingerprint VARCHAR(255),
    attempt_count INT NOT NULL DEFAULT 1,
    window_start TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    window_end TIMESTAMP NOT NULL,
    is_banned BOOLEAN NOT NULL DEFAULT FALSE,
    ban_id BIGINT,
    
    INDEX idx_ip_address (ip_address),
    INDEX idx_device_fingerprint (device_fingerprint),
    INDEX idx_window_end (window_end),
    INDEX idx_is_banned (is_banned),
    FOREIGN KEY (ban_id) REFERENCES bans(id) ON DELETE SET NULL
);

-- =====================================================
-- 2. RATE LIMITING CONFIGURATION TABLES
-- =====================================================

-- Rate limit rules configuration
CREATE TABLE rate_limit_rules (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    endpoint_pattern VARCHAR(255) NOT NULL UNIQUE, -- e.g., '/auth/login', '/rooms/*'
    method VARCHAR(10), -- 'GET', 'POST', 'PUT', 'DELETE', '*' for all
    limit_type VARCHAR(20) NOT NULL, -- 'FIXED_WINDOW', 'SLIDING_WINDOW', 'TOKEN_BUCKET'
    max_requests INT NOT NULL,
    window_seconds INT NOT NULL, -- Time window in seconds
    refill_rate INT, -- For TOKEN_BUCKET: tokens per second
    bucket_size INT, -- For TOKEN_BUCKET: max tokens
    scope VARCHAR(20) NOT NULL DEFAULT 'USER', -- 'USER', 'IP', 'GLOBAL'
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_endpoint_pattern (endpoint_pattern),
    INDEX idx_is_active (is_active),
    INDEX idx_scope (scope)
);

-- Rate limit tracking (for fixed/sliding window)
CREATE TABLE rate_limit_tracking (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_id BIGINT NOT NULL,
    identifier VARCHAR(255) NOT NULL, -- user_id, ip_address, or 'global'
    request_count INT NOT NULL DEFAULT 1,
    window_start TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    window_end TIMESTAMP NOT NULL,
    last_request_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_rule_identifier (rule_id, identifier),
    INDEX idx_window_end (window_end),
    INDEX idx_identifier (identifier),
    FOREIGN KEY (rule_id) REFERENCES rate_limit_rules(id) ON DELETE CASCADE
);

-- Token bucket state (for token bucket algorithm)
CREATE TABLE rate_limit_token_buckets (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_id BIGINT NOT NULL,
    identifier VARCHAR(255) NOT NULL,
    tokens INT NOT NULL,
    last_refill_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_rule_identifier (rule_id, identifier),
    INDEX idx_identifier (identifier),
    FOREIGN KEY (rule_id) REFERENCES rate_limit_rules(id) ON DELETE CASCADE
);

-- =====================================================
-- 3. BAN SYSTEM CONFIGURATION TABLE
-- =====================================================

-- Ban system configuration
CREATE TABLE ban_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_key VARCHAR(100) NOT NULL UNIQUE,
    config_value VARCHAR(500) NOT NULL,
    description VARCHAR(500),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_config_key (config_key)
);

-- =====================================================
-- 4. INSERT DEFAULT CONFIGURATIONS
-- =====================================================

-- Ban system default configurations
INSERT INTO ban_config (config_key, config_value, description) VALUES
('MAX_FAILED_LOGIN_ATTEMPTS', '5', 'Maximum failed login attempts before auto-ban'),
('FAILED_LOGIN_WINDOW_MINUTES', '15', 'Time window for counting failed login attempts (minutes)'),
('FAILED_LOGIN_BAN_DURATION_MINUTES', '30', 'Auto-ban duration for failed login attempts (minutes)'),
('MAX_CONCURRENT_LOGIN_ATTEMPTS', '10', 'Maximum concurrent login attempts from same IP/device'),
('CONCURRENT_LOGIN_WINDOW_SECONDS', '60', 'Time window for counting concurrent login attempts (seconds)'),
('CONCURRENT_LOGIN_BAN_DURATION_MINUTES', '15', 'Auto-ban duration for concurrent login attempts (minutes)'),
('AUTO_UNBAN_ENABLED', 'true', 'Enable automatic unban after ban duration expires'),
('AUTO_UNBAN_CHECK_INTERVAL_SECONDS', '60', 'Interval to check for expired bans (seconds)')
ON DUPLICATE KEY UPDATE config_value = VALUES(config_value);

-- Rate limiting default rules
INSERT INTO rate_limit_rules (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description) VALUES
-- Authentication endpoints
('/auth/login', 'POST', 'FIXED_WINDOW', 5, 60, 'IP', 'Login attempts per minute per IP'),
('/auth/register', 'POST', 'FIXED_WINDOW', 3, 3600, 'IP', 'Registration attempts per hour per IP'),
('/auth/auto-login', 'POST', 'FIXED_WINDOW', 10, 60, 'USER', 'Auto-login attempts per minute per user'),
('/auth/change-password', 'POST', 'FIXED_WINDOW', 5, 300, 'USER', 'Password change attempts per 5 minutes per user'),

-- Room endpoints
('/rooms/create', 'POST', 'FIXED_WINDOW', 10, 60, 'USER', 'Room creation per minute per user'),
('/rooms/join-by-code', 'POST', 'FIXED_WINDOW', 20, 60, 'USER', 'Join room attempts per minute per user'),
('/rooms/quick-play', 'POST', 'FIXED_WINDOW', 15, 60, 'USER', 'Quick play attempts per minute per user'),
('/rooms/*/ready', 'POST', 'FIXED_WINDOW', 30, 60, 'USER', 'Set ready attempts per minute per user'),
('/rooms/*/change-team', 'POST', 'FIXED_WINDOW', 20, 60, 'USER', 'Change team attempts per minute per user'),
('/rooms/*/start', 'POST', 'FIXED_WINDOW', 5, 60, 'USER', 'Start game attempts per minute per user'),

-- General API rate limit
('/api/*', '*', 'FIXED_WINDOW', 1000, 60, 'USER', 'General API rate limit per minute per user')
ON DUPLICATE KEY UPDATE 
    max_requests = VALUES(max_requests),
    window_seconds = VALUES(window_seconds);

-- =====================================================
-- 5. CLEANUP PROCEDURES (Optional - for MySQL)
-- =====================================================

-- Cleanup expired rate limit tracking (run periodically)
-- DELETE FROM rate_limit_tracking WHERE window_end < NOW() - INTERVAL 1 HOUR;

-- Cleanup expired token buckets (run periodically)
-- DELETE FROM rate_limit_token_buckets WHERE last_refill_at < NOW() - INTERVAL 24 HOUR;

-- Cleanup old failed login attempts (run periodically)
-- DELETE FROM failed_login_attempts WHERE last_attempt_at < NOW() - INTERVAL 24 HOUR AND is_banned = FALSE;

-- Cleanup old concurrent login attempts (run periodically)
-- DELETE FROM concurrent_login_attempts WHERE window_end < NOW() - INTERVAL 1 HOUR AND is_banned = FALSE;

-- =====================================================
-- 6. INDEXES FOR PERFORMANCE
-- =====================================================

-- Additional composite indexes for common queries
CREATE INDEX idx_bans_user_active ON bans(user_id, is_active, expires_at);
CREATE INDEX idx_bans_ip_active ON bans(ip_address, is_active, expires_at);
CREATE INDEX idx_failed_login_identifier_time ON failed_login_attempts(identifier, last_attempt_at);
CREATE INDEX idx_rate_limit_rule_active ON rate_limit_rules(endpoint_pattern, method, is_active);

