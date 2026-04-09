-- =====================================================
-- NIGHT HUNT SERVER - CONSOLIDATED DATABASE SCHEMA
-- =====================================================
-- Generated: March 15, 2026
-- Description: Final state after all 16 Flyway migrations (V1-V16)
-- Compatible with: MySQL 8.0+
-- Usage: Copy this file to initialize a fresh database
-- =====================================================

-- =====================================================
-- SECTION 1: USER MANAGEMENT
-- =====================================================

-- ─────────────────────────────────────────────────────
-- Users Table
-- Core user accounts with authentication, ELO ranking, and social status tracking
-- ─────────────────────────────────────────────────────
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
    online_status VARCHAR(20) NOT NULL DEFAULT 'OFFLINE' COMMENT 'ONLINE, OFFLINE, AWAY, IN_GAME',
    current_party_id BIGINT NULL COMMENT 'Party ID if user is in a party',
    current_room_id BIGINT NULL COMMENT 'Room ID if user is in custom lobby',
    last_seen_at TIMESTAMP NULL COMMENT 'Last activity timestamp when user went offline',
    password_hash VARCHAR(255) NOT NULL,
    elo INT NOT NULL DEFAULT 1000 COMMENT 'Current ELO rating',
    tier VARCHAR(20) NOT NULL DEFAULT 'BRONZE' COMMENT 'BRONZE/SILVER/GOLD/PLATINUM/DIAMOND/MASTER',
    total_wins INT NOT NULL DEFAULT 0,
    total_losses INT NOT NULL DEFAULT 0,
    total_draws INT NOT NULL DEFAULT 0,
    selected_character_id VARCHAR(64) NULL COMMENT 'Backend string ID of selected character (e.g. character_01)',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_username (username),
    INDEX idx_email (email),
    INDEX idx_users_online_status (online_status),
    INDEX idx_users_current_party_id (current_party_id),
    INDEX idx_users_current_room_id (current_room_id),
    INDEX idx_users_last_seen_at (last_seen_at)
);

-- ─────────────────────────────────────────────────────
-- Sessions Table
-- Active user sessions with JWT access tokens
-- ─────────────────────────────────────────────────────
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

-- ─────────────────────────────────────────────────────
-- Refresh Tokens Table
-- Long-lived tokens for session management (30 days)
-- ─────────────────────────────────────────────────────
CREATE TABLE refresh_tokens (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token VARCHAR(512) NOT NULL UNIQUE COMMENT 'Opaque UUID-based token',
    expiry_date DATETIME NOT NULL COMMENT 'Hard expiry date',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'TRUE after logout or token rotation',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_rt_user_id (user_id),
    INDEX idx_rt_token (token),
    INDEX idx_rt_expiry (expiry_date),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Long-lived refresh tokens. One active token per user (old tokens revoked on rotate)';

-- ─────────────────────────────────────────────────────
-- User Activity Logs Table
-- Activity tracking for admin dashboard monitoring
-- ─────────────────────────────────────────────────────
CREATE TABLE user_activity_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT,
    username VARCHAR(50),
    event_type VARCHAR(50) NOT NULL,
    event_data TEXT,
    ip_address VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_ual_user_id (user_id),
    INDEX idx_ual_event_type (event_type),
    INDEX idx_ual_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================
-- SECTION 2: BAN & RATE LIMITING SYSTEM
-- =====================================================

-- ─────────────────────────────────────────────────────
-- Bans Table
-- User, IP, and device bans with auto-unban support
-- ─────────────────────────────────────────────────────
CREATE TABLE bans (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NULL COMMENT 'NULL for IP/device bans before user verification',
    ban_type VARCHAR(20) NOT NULL COMMENT 'USER, IP, DEVICE',
    ip_address VARCHAR(45),
    device_fingerprint VARCHAR(255),
    reason VARCHAR(255) NOT NULL,
    ban_duration_minutes INT NOT NULL COMMENT '0 = permanent',
    banned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP COMMENT 'NULL = permanent',
    banned_by BIGINT COMMENT 'Admin user ID, NULL = auto-ban',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    auto_unbanned BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_user_id (user_id),
    INDEX idx_ip_address (ip_address),
    INDEX idx_device_fingerprint (device_fingerprint),
    INDEX idx_expires_at (expires_at),
    INDEX idx_is_active (is_active),
    INDEX idx_ban_type (ban_type),
    INDEX idx_bans_user_active (user_id, is_active, expires_at),
    INDEX idx_bans_ip_active (ip_address, is_active, expires_at)
);

-- ─────────────────────────────────────────────────────
-- Failed Login Attempts Table
-- Track failed login attempts for auto-ban
-- ─────────────────────────────────────────────────────
CREATE TABLE failed_login_attempts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    identifier VARCHAR(255) NOT NULL COMMENT 'Username or email',
    ip_address VARCHAR(45) NOT NULL,
    device_fingerprint VARCHAR(255),
    attempt_count INT NOT NULL DEFAULT 1,
    first_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_banned BOOLEAN NOT NULL DEFAULT FALSE,
    ban_id BIGINT,
    
    INDEX idx_identifier (identifier),
    INDEX idx_ip_address (ip_address),
    INDEX idx_device_fingerprint (device_fingerprint),
    INDEX idx_last_attempt_at (last_attempt_at),
    INDEX idx_is_banned (is_banned),
    INDEX idx_failed_login_identifier_time (identifier, last_attempt_at),
    FOREIGN KEY (ban_id) REFERENCES bans(id) ON DELETE SET NULL
);

-- ─────────────────────────────────────────────────────
-- Concurrent Login Attempts Table
-- Track concurrent login attempts from same IP/device
-- ─────────────────────────────────────────────────────
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

-- ─────────────────────────────────────────────────────
-- Ban Config Table
-- Configuration parameters for ban system
-- ─────────────────────────────────────────────────────
CREATE TABLE ban_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_key VARCHAR(100) NOT NULL UNIQUE,
    config_value VARCHAR(500) NOT NULL,
    description VARCHAR(500),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_config_key (config_key)
);

-- ─────────────────────────────────────────────────────
-- Rate Limit Rules Table
-- Configurable rate limiting rules per endpoint
-- ─────────────────────────────────────────────────────
CREATE TABLE rate_limit_rules (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    endpoint_pattern VARCHAR(255) NOT NULL UNIQUE,
    method VARCHAR(10) COMMENT 'GET, POST, PUT, DELETE, * for all',
    limit_type VARCHAR(20) NOT NULL COMMENT 'FIXED_WINDOW, SLIDING_WINDOW, TOKEN_BUCKET',
    max_requests INT NOT NULL,
    window_seconds INT NOT NULL,
    refill_rate INT COMMENT 'For TOKEN_BUCKET: tokens per second',
    bucket_size INT COMMENT 'For TOKEN_BUCKET: max tokens',
    scope VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT 'USER, IP, GLOBAL',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_endpoint_pattern (endpoint_pattern),
    INDEX idx_is_active (is_active),
    INDEX idx_scope (scope),
    INDEX idx_rate_limit_rule_active (endpoint_pattern, method, is_active)
);

-- ─────────────────────────────────────────────────────
-- Rate Limit Tracking Table
-- Track request counts for fixed/sliding window algorithms
-- ─────────────────────────────────────────────────────
CREATE TABLE rate_limit_tracking (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_id BIGINT NOT NULL,
    identifier VARCHAR(255) NOT NULL COMMENT 'user_id, ip_address, or global',
    request_count INT NOT NULL DEFAULT 1,
    window_start TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    window_end TIMESTAMP NOT NULL,
    last_request_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_rule_identifier (rule_id, identifier),
    INDEX idx_window_end (window_end),
    INDEX idx_identifier (identifier),
    FOREIGN KEY (rule_id) REFERENCES rate_limit_rules(id) ON DELETE CASCADE
);

-- ─────────────────────────────────────────────────────
-- Rate Limit Token Buckets Table
-- Token bucket state for token bucket algorithm
-- ─────────────────────────────────────────────────────
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
-- SECTION 3: FRIEND SYSTEM
-- =====================================================

-- ─────────────────────────────────────────────────────
-- Friends Table
-- Bidirectional friendships (A→B and B→A)
-- ─────────────────────────────────────────────────────
CREATE TABLE friends (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT 'Owner of this friendship record',
    friend_user_id BIGINT NOT NULL COMMENT 'The friend user',
    friendship_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE or BLOCKED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    CONSTRAINT fk_friends_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_friends_friend_user_id FOREIGN KEY (friend_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_user_friend UNIQUE (user_id, friend_user_id),
    
    INDEX idx_friends_user_id (user_id),
    INDEX idx_friends_friend_user_id (friend_user_id),
    INDEX idx_friends_status (friendship_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Bidirectional friendships. status: ACTIVE=normal, BLOCKED=user blocked this friend';

-- ─────────────────────────────────────────────────────
-- Friend Requests Table
-- Pending, accepted, declined, or cancelled friend requests
-- ─────────────────────────────────────────────────────
CREATE TABLE friend_requests (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    requester_user_id BIGINT NOT NULL COMMENT 'User who sent the request',
    addressee_user_id BIGINT NOT NULL COMMENT 'User who receives the request',
    request_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, ACCEPTED, DECLINED, CANCELLED',
    expires_at TIMESTAMP NULL COMMENT 'Optional expiry date',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    CONSTRAINT fk_friend_requests_requester FOREIGN KEY (requester_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_friend_requests_addressee FOREIGN KEY (addressee_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_friend_request UNIQUE (requester_user_id, addressee_user_id),
    
    INDEX idx_friend_requests_addressee (addressee_user_id, request_status),
    INDEX idx_friend_requests_requester (requester_user_id),
    INDEX idx_friend_requests_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Friend requests. status: PENDING=waiting, ACCEPTED=friendship created, DECLINED=rejected, CANCELLED=requester cancelled';

-- ─────────────────────────────────────────────────────
-- Blocked Users Table
-- User block relationships (blocker cannot see blocked user)
-- ─────────────────────────────────────────────────────
CREATE TABLE blocked_users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    blocker_user_id BIGINT NOT NULL COMMENT 'User who blocked',
    blocked_user_id BIGINT NOT NULL COMMENT 'User who got blocked',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT fk_blocked_users_blocker FOREIGN KEY (blocker_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_blocked_users_blocked FOREIGN KEY (blocked_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_blocker_blocked UNIQUE (blocker_user_id, blocked_user_id),
    
    INDEX idx_blocked_users_blocker (blocker_user_id),
    INDEX idx_blocked_users_blocked (blocked_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Blocked user relationships. Blocker cannot see blocked user activities';

-- =====================================================
-- SECTION 4: PARTY SYSTEM
-- =====================================================

-- ─────────────────────────────────────────────────────
-- Parties Table
-- Pre-match party information (PUBG-style squads)
-- ─────────────────────────────────────────────────────
CREATE TABLE parties (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    host_user_id BIGINT NOT NULL COMMENT 'Party host (creator)',
    party_status VARCHAR(20) NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE, IN_QUEUE, IN_ROOM, IN_GAME, DISBANDED',
    current_room_id BIGINT NULL COMMENT 'If party is in a custom lobby',
    current_matchmaking_id BIGINT NULL COMMENT 'If party is in queue',
    max_members INT NOT NULL DEFAULT 4 COMMENT 'Maximum party size',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    CONSTRAINT fk_parties_host_user_id FOREIGN KEY (host_user_id) REFERENCES users(id) ON DELETE CASCADE,
    
    INDEX idx_parties_status (party_status),
    INDEX idx_parties_host_user_id (host_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Pre-match parties. status: IDLE=in menu, IN_QUEUE=searching, IN_ROOM=in lobby, IN_GAME=playing, DISBANDED=deleted';

-- ─────────────────────────────────────────────────────
-- Party Members Table
-- Party membership information
-- ─────────────────────────────────────────────────────
CREATE TABLE party_members (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    party_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    join_order INT NOT NULL COMMENT '0=host, 1,2,3...=guests',
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT fk_party_members_party_id FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE,
    CONSTRAINT fk_party_members_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_party_user UNIQUE (party_id, user_id),
    
    INDEX idx_party_members_user_id (user_id),
    INDEX idx_party_members_party_id (party_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Party membership. join_order: 0=host, 1,2,3=guests';

-- ─────────────────────────────────────────────────────
-- Party Invitations Table
-- Party invitations with 30 second timeout
-- ─────────────────────────────────────────────────────
CREATE TABLE party_invitations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    party_id BIGINT NOT NULL,
    inviter_user_id BIGINT NOT NULL COMMENT 'Party member who sent invite',
    invitee_user_id BIGINT NOT NULL COMMENT 'User who receives invite',
    invitation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, ACCEPTED, DECLINED, EXPIRED, CANCELLED',
    expires_at TIMESTAMP NOT NULL COMMENT '30 seconds timeout',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    CONSTRAINT fk_party_invitations_party_id FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE,
    CONSTRAINT fk_party_invitations_inviter FOREIGN KEY (inviter_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_party_invitations_invitee FOREIGN KEY (invitee_user_id) REFERENCES users(id) ON DELETE CASCADE,
    
    INDEX idx_party_invitations_invitee (invitee_user_id, invitation_status),
    INDEX idx_party_invitations_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Party invitations. status: PENDING=waiting, ACCEPTED=joined, DECLINED=rejected, EXPIRED=timeout, CANCELLED=inviter cancelled';

-- =====================================================
-- SECTION 5: ROOM & MATCHMAKING SYSTEM
-- =====================================================

-- ─────────────────────────────────────────────────────
-- Game Modes Table
-- Configurable game modes (2v2, 3v3, 4v4, 5v5)
-- ─────────────────────────────────────────────────────
CREATE TABLE game_modes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    mode_key VARCHAR(20) NOT NULL UNIQUE COMMENT 'Unique key: 2v2, 3v3, 4v4, 5v5',
    display_name VARCHAR(50) NOT NULL COMMENT 'Display name: "2 vs 2"',
    players_per_team INT NOT NULL COMMENT 'Number of players per team',
    total_players INT NOT NULL COMMENT 'Total players in match',
    mode_status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE, LOCKED, COMING_SOON, DISABLED',
    display_order INT NOT NULL DEFAULT 0 COMMENT 'Sort order in UI',
    is_active BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'Soft delete flag',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_game_modes_status (mode_status, is_active),
    INDEX idx_game_modes_order (display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Configurable game modes. status: AVAILABLE=can play, LOCKED=not accessible, COMING_SOON=future, DISABLED=removed';

-- ─────────────────────────────────────────────────────
-- Rooms Table
-- Custom lobby rooms for matches
-- ─────────────────────────────────────────────────────
CREATE TABLE rooms (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    room_code VARCHAR(8) NOT NULL UNIQUE,
    mode VARCHAR(20) NOT NULL COMMENT '2v2, 3v3, 5v5',
    map_id VARCHAR(50) NOT NULL DEFAULT 'map_01' COMMENT 'Selected gameplay map ID',
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING' COMMENT 'WAITING, IN_GAME, CLOSED, FINISHED',
    is_public BOOLEAN NOT NULL DEFAULT TRUE,
    is_locked BOOLEAN NOT NULL DEFAULT FALSE,
    password VARCHAR(50),
    owner_id BIGINT NOT NULL,
    match_id VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_room_code (room_code),
    INDEX idx_status (status),
    INDEX idx_owner_id (owner_id),
    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
);

-- ─────────────────────────────────────────────────────
-- Room Players Table
-- Players in a room with team and slot assignment
-- ─────────────────────────────────────────────────────
CREATE TABLE room_players (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    team INT NOT NULL COMMENT '1 or 2',
    slot INT NOT NULL COMMENT 'Position in team (0, 1, 2...)',
    is_ready BOOLEAN NOT NULL DEFAULT FALSE,
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_room_id (room_id),
    INDEX idx_user_id (user_id),
    INDEX idx_room_user (room_id, user_id),
    UNIQUE KEY uk_room_user (room_id, user_id),
    UNIQUE KEY uk_room_team_slot (room_id, team, slot),
    FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- ─────────────────────────────────────────────────────
-- Swap Requests Table
-- Team/slot swap requests between players
-- ─────────────────────────────────────────────────────
CREATE TABLE swap_requests (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    room_id BIGINT NOT NULL,
    requester_id BIGINT NOT NULL,
    target_id BIGINT NOT NULL,
    requester_team INT NOT NULL,
    requester_slot INT NOT NULL,
    target_team INT NOT NULL,
    target_slot INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, ACCEPTED, REJECTED, EXPIRED',
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

-- ─────────────────────────────────────────────────────
-- Matchmaking Queue Table
-- Ranked matchmaking queue with ELO-based matching
-- ─────────────────────────────────────────────────────
CREATE TABLE matchmaking_queue (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL UNIQUE,
    elo INT NOT NULL DEFAULT 1000,
    game_mode VARCHAR(20) NOT NULL,
    queued_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    search_min_elo INT NOT NULL DEFAULT 0,
    search_max_elo INT NOT NULL DEFAULT 9999,
    status VARCHAR(20) NOT NULL DEFAULT 'SEARCHING' COMMENT 'SEARCHING, MATCHED, CANCELLED',
    lobby_token VARCHAR(64) NULL COMMENT 'Shared token for matched group',
    accept_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, ACCEPTED, DECLINED',
    
    PRIMARY KEY (id),
    INDEX idx_mmq_user_id (user_id),
    INDEX idx_mmq_game_mode (game_mode),
    INDEX idx_mmq_status (status),
    INDEX idx_mmq_lobby_token (lobby_token),
    INDEX idx_mmq_accept_status (accept_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================
-- SECTION 6: MATCH & SERVER MANAGEMENT
-- =====================================================

-- ─────────────────────────────────────────────────────
-- Dedicated Servers Table
-- Docker container instances for game servers
-- ─────────────────────────────────────────────────────
CREATE TABLE dedicated_servers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    server_id VARCHAR(36) NOT NULL UNIQUE COMMENT 'UUID - matches SERVER_ID env in container',
    docker_container_id VARCHAR(64) COMMENT 'Docker container ID',
    ip VARCHAR(45) NOT NULL COMMENT 'Public IP of VPS',
    port INT NOT NULL COMMENT 'UDP port (7777-7900)',
    status VARCHAR(20) NOT NULL DEFAULT 'starting' COMMENT 'starting, ready, in_game, stopped',
    region VARCHAR(20) NOT NULL DEFAULT 'vn',
    current_players INT NOT NULL DEFAULT 0,
    max_players INT NOT NULL DEFAULT 16,
    image_tag VARCHAR(100) COMMENT 'Docker image tag',
    server_secret_hash VARCHAR(255) NOT NULL COMMENT 'BCrypt hash of SERVER_SECRET',
    last_heartbeat_at DATETIME COMMENT 'Last heartbeat timestamp',
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    stopped_at DATETIME COMMENT 'NULL = running',

    INDEX idx_ds_status (status),
    INDEX idx_ds_port (port),
    INDEX idx_ds_region (region, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────
-- Matches Table
-- Match instances with status and results
-- ─────────────────────────────────────────────────────
CREATE TABLE matches (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    match_id VARCHAR(50) NOT NULL UNIQUE,
    room_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'LOBBY' COMMENT 'LOBBY, IN_GAME, FINISHED',
    winner_team_id INT NULL COMMENT '-1 = DRAW',
    end_reason VARCHAR(30) NULL COMMENT 'TEAM_ELIMINATED, TIMER_EXPIRED, DRAW',
    game_mode VARCHAR(20) NULL COMMENT '2v2, 3v3, 5v5',
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_match_id (match_id),
    INDEX idx_room_id (room_id),
    INDEX idx_status (status),
    FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE
);

-- ─────────────────────────────────────────────────────
-- Match Player Results Table
-- Per-player statistics for finished matches
-- ─────────────────────────────────────────────────────
CREATE TABLE match_player_results (
    id BIGINT NOT NULL AUTO_INCREMENT,
    match_id VARCHAR(50) NOT NULL,
    user_id BIGINT NOT NULL,
    team_id INT NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    kills INT NOT NULL DEFAULT 0,
    deaths INT NOT NULL DEFAULT 0,
    score INT NOT NULL DEFAULT 0,
    elo_before INT NOT NULL DEFAULT 1000,
    elo_after INT NOT NULL DEFAULT 1000,
    elo_change INT NOT NULL DEFAULT 0,
    placement INT NOT NULL DEFAULT 0 COMMENT '1=winner, 2=loser, 0=draw',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    PRIMARY KEY (id),
    INDEX idx_mpr_match_id (match_id),
    INDEX idx_mpr_user_id (user_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================
-- SECTION 7: FOREIGN KEY CONSTRAINTS (users table)
-- =====================================================

-- Add foreign keys for users table (created after dependent tables)
ALTER TABLE users 
ADD CONSTRAINT fk_users_current_party_id 
FOREIGN KEY (current_party_id) REFERENCES parties(id) ON DELETE SET NULL;

ALTER TABLE users 
ADD CONSTRAINT fk_users_current_room_id 
FOREIGN KEY (current_room_id) REFERENCES rooms(id) ON DELETE SET NULL;

-- Add foreign key for parties.current_room_id (after rooms table created)
ALTER TABLE parties
ADD CONSTRAINT fk_parties_current_room_id 
FOREIGN KEY (current_room_id) REFERENCES rooms(id) ON DELETE SET NULL;

-- =====================================================
-- SECTION 8: DEFAULT DATA
-- =====================================================

-- ─────────────────────────────────────────────────────
-- Ban System Configuration
-- ─────────────────────────────────────────────────────
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

-- ─────────────────────────────────────────────────────
-- Rate Limiting Rules
-- ─────────────────────────────────────────────────────
INSERT INTO rate_limit_rules (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description) VALUES
-- Authentication endpoints
('/auth/login', 'POST', 'FIXED_WINDOW', 5, 60, 'IP', 'Login attempts per minute per IP'),
('/auth/register', 'POST', 'FIXED_WINDOW', 3, 3600, 'IP', 'Registration attempts per hour per IP'),
('/auth/auto-login', 'POST', 'FIXED_WINDOW', 10, 60, 'USER', 'Auto-login attempts per minute per user'),
('/auth/change-password', 'POST', 'FIXED_WINDOW', 5, 300, 'USER', 'Password change attempts per 5 minutes per user'),
-- Room endpoints
('/api/rooms/create', 'POST', 'FIXED_WINDOW', 10, 60, 'USER', 'Room creation per minute per user'),
('/api/rooms/join-by-code', 'POST', 'FIXED_WINDOW', 20, 60, 'USER', 'Join room attempts per minute per user'),
('/api/rooms/quick-play', 'POST', 'FIXED_WINDOW', 15, 60, 'USER', 'Quick play attempts per minute per user'),
('/api/rooms/*/ready', 'POST', 'FIXED_WINDOW', 30, 60, 'USER', 'Set ready attempts per minute per user'),
('/api/rooms/*/change-team', 'POST', 'FIXED_WINDOW', 20, 60, 'USER', 'Change team attempts per minute per user'),
('/api/rooms/*/start', 'POST', 'FIXED_WINDOW', 5, 60, 'USER', 'Start game attempts per minute per user'),
-- Friend & Party endpoints
('/api/friends/*', '*', 'FIXED_WINDOW', 100, 60, 'USER', 'Friend system API rate limit per minute per user'),
('/api/party/*', '*', 'FIXED_WINDOW', 100, 60, 'USER', 'Party system API rate limit per minute per user'),
-- General API rate limit
('/api/*', '*', 'FIXED_WINDOW', 1000, 60, 'USER', 'General API rate limit per minute per user')
ON DUPLICATE KEY UPDATE 
    max_requests = VALUES(max_requests),
    window_seconds = VALUES(window_seconds);

-- ─────────────────────────────────────────────────────
-- Game Modes
-- ─────────────────────────────────────────────────────
INSERT INTO game_modes (mode_key, display_name, players_per_team, total_players, mode_status, display_order) VALUES
('2v2', '2 vs 2', 2, 4, 'AVAILABLE', 1),
('3v3', '3 vs 3', 3, 6, 'AVAILABLE', 2),
('4v4', '4 vs 4', 4, 8, 'COMING_SOON', 3),
('5v5', '5 vs 5', 5, 10, 'AVAILABLE', 4);

-- =====================================================
-- END OF CONSOLIDATED SCHEMA
-- =====================================================
-- Total Tables: 27
-- - User Management: 4 tables
-- - Ban & Rate Limiting: 7 tables  
-- - Friend System: 3 tables
-- - Party System: 3 tables
-- - Room & Matchmaking: 5 tables
-- - Match & Server: 3 tables
-- - Configuration: 2 tables
-- =====================================================
