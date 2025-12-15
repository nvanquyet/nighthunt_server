-- =====================================================
-- Core Application Database Schema
-- =====================================================
-- Flyway Migration: V2
-- Description: Core tables for users, sessions, rooms, matches, and headless servers
-- Compatible with: MySQL 8.0+
-- =====================================================

-- =====================================================
-- 1. USERS TABLE
-- =====================================================
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_username (username),
    INDEX idx_email (email)
);

-- =====================================================
-- 2. SESSIONS TABLE
-- =====================================================
CREATE TABLE sessions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(100) NOT NULL UNIQUE,
    access_token VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    last_activity_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_user_id (user_id),
    INDEX idx_session_id (session_id),
    INDEX idx_expires_at (expires_at),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- =====================================================
-- 3. HEADLESS SERVERS TABLE
-- =====================================================
CREATE TABLE headless_servers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    server_id VARCHAR(50) NOT NULL UNIQUE,
    server_ip VARCHAR(50) NOT NULL,
    server_port INT NOT NULL,
    api_base_url VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'INACTIVE', -- ACTIVE, INACTIVE, MAINTENANCE
    max_matches INT NOT NULL DEFAULT 10,
    current_matches INT NOT NULL DEFAULT 0,
    cpu_usage DOUBLE,
    memory_usage DOUBLE,
    last_heartbeat TIMESTAMP,
    version VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_server_id (server_id),
    INDEX idx_status (status),
    INDEX idx_last_heartbeat (last_heartbeat)
);

-- =====================================================
-- 4. HEADLESS SERVER CONFIG TABLE
-- =====================================================
CREATE TABLE headless_server_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_key VARCHAR(100) NOT NULL UNIQUE,
    config_value VARCHAR(500) NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_config_key (config_key)
);

-- =====================================================
-- 5. ROOMS TABLE
-- =====================================================
CREATE TABLE rooms (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    room_code VARCHAR(8) NOT NULL UNIQUE,
    mode VARCHAR(20) NOT NULL, -- 2v2, 3v3, 5v5
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING', -- WAITING, IN_GAME, CLOSED, FINISHED
    is_public BOOLEAN NOT NULL DEFAULT TRUE,
    is_locked BOOLEAN NOT NULL DEFAULT FALSE,
    password VARCHAR(50),
    owner_id BIGINT NOT NULL,
    headless_server_id BIGINT,
    match_id VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_room_code (room_code),
    INDEX idx_status (status),
    INDEX idx_owner_id (owner_id),
    INDEX idx_headless_server_id (headless_server_id),
    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (headless_server_id) REFERENCES headless_servers(id) ON DELETE SET NULL
);

-- =====================================================
-- 6. ROOM PLAYERS TABLE
-- =====================================================
CREATE TABLE room_players (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    team INT NOT NULL, -- 1 or 2
    slot INT NOT NULL, -- position in team (0, 1, 2...)
    is_ready BOOLEAN NOT NULL DEFAULT FALSE,
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_room_id (room_id),
    INDEX idx_user_id (user_id),
    INDEX idx_room_user (room_id, user_id),
    UNIQUE KEY uk_room_user (room_id, user_id),
    FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- =====================================================
-- 7. SWAP REQUESTS TABLE
-- =====================================================
CREATE TABLE swap_requests (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    room_id BIGINT NOT NULL,
    requester_id BIGINT NOT NULL,
    target_id BIGINT NOT NULL,
    requester_team INT NOT NULL,
    requester_slot INT NOT NULL,
    target_team INT NOT NULL,
    target_slot INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING, ACCEPTED, REJECTED, EXPIRED
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    
    INDEX idx_room_id (room_id),
    INDEX idx_requester_id (requester_id),
    INDEX idx_target_id (target_id),
    INDEX idx_status (status),
    INDEX idx_expires_at (expires_at),
    FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE,
    FOREIGN KEY (requester_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (target_id) REFERENCES users(id) ON DELETE CASCADE
);

-- =====================================================
-- 8. MATCHES TABLE
-- =====================================================
CREATE TABLE matches (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    match_id VARCHAR(50) NOT NULL UNIQUE,
    room_id BIGINT NOT NULL,
    headless_server_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'LOBBY', -- LOBBY, IN_GAME, FINISHED
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_match_id (match_id),
    INDEX idx_room_id (room_id),
    INDEX idx_headless_server_id (headless_server_id),
    INDEX idx_status (status),
    FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE,
    FOREIGN KEY (headless_server_id) REFERENCES headless_servers(id) ON DELETE CASCADE
);

-- =====================================================
-- 9. INSERT DEFAULT HEADLESS SERVER CONFIG
-- =====================================================
INSERT INTO headless_server_config (config_key, config_value, description) VALUES
('build.path', '../Build/Server', 'Path to headless server build directory'),
('default.version', 'Ver0.0.1', 'Default headless server version'),
('log.path', './logs/headless-servers', 'Path to headless server logs'),
('auto-scaling.enabled', 'true', 'Enable automatic server scaling'),
('scale-up.threshold', '0.8', 'Scale up when server capacity reaches 80%'),
('scale-down.threshold', '0.2', 'Scale down when server capacity drops below 20%'),
('idle-timeout.minutes', '5', 'Idle timeout in minutes before shutting down server'),
('max-servers', '10', 'Maximum number of headless servers'),
('default-ip', '127.0.0.1', 'Default server IP address'),
('base-port', '7777', 'Base port for headless servers'),
('max-matches', '10', 'Maximum matches per server'),
('server.version', 'Ver0.0.1', 'Server version')
ON DUPLICATE KEY UPDATE config_value = VALUES(config_value);

