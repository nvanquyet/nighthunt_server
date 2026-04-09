-- =====================================================
-- Friend System & Party System Database Schema
-- =====================================================
-- Flyway Migration: V12
-- Description: Friend system (friends, requests, blocks) and Party system (parties, members, invitations)
-- Author: Social System Implementation
-- Date: March 15, 2026
-- Compatible with: MySQL 8.0+
-- =====================================================

-- =====================================================
-- PART 1: FRIEND SYSTEM
-- =====================================================

-- =====================================================
-- 1. FRIENDS TABLE
-- =====================================================
-- Stores bidirectional friendships
-- When A adds B as friend, two rows are created: (A→B) and (B→A)
CREATE TABLE friends (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT 'Owner of this friendship record',
    friend_user_id BIGINT NOT NULL COMMENT 'The friend user',
    friendship_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE or BLOCKED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    -- Foreign keys
    CONSTRAINT fk_friends_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_friends_friend_user_id FOREIGN KEY (friend_user_id) REFERENCES users(id) ON DELETE CASCADE,
    
    -- Unique constraint: Cannot have duplicate friendship
    CONSTRAINT uk_user_friend UNIQUE (user_id, friend_user_id),
    
    -- Indexes
    INDEX idx_friends_user_id (user_id),
    INDEX idx_friends_friend_user_id (friend_user_id),
    INDEX idx_friends_status (friendship_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Bidirectional friendships. status: ACTIVE=normal, BLOCKED=user blocked this friend';

-- =====================================================
-- 2. FRIEND REQUESTS TABLE
-- =====================================================
-- Stores pending, accepted, declined, or cancelled friend requests
CREATE TABLE friend_requests (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    requester_user_id BIGINT NOT NULL COMMENT 'User who sent the request',
    addressee_user_id BIGINT NOT NULL COMMENT 'User who receives the request',
    request_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, ACCEPTED, DECLINED, CANCELLED',
    expires_at TIMESTAMP NULL COMMENT 'Optional expiry date (e.g. 30 days)',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    -- Foreign keys
    CONSTRAINT fk_friend_requests_requester FOREIGN KEY (requester_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_friend_requests_addressee FOREIGN KEY (addressee_user_id) REFERENCES users(id) ON DELETE CASCADE,
    
    -- Unique constraint: Cannot send duplicate request
    CONSTRAINT uk_friend_request UNIQUE (requester_user_id, addressee_user_id),
    
    -- Indexes
    INDEX idx_friend_requests_addressee (addressee_user_id, request_status),
    INDEX idx_friend_requests_requester (requester_user_id),
    INDEX idx_friend_requests_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Friend requests. status: PENDING=waiting, ACCEPTED=friendship created, DECLINED=rejected, CANCELLED=requester cancelled';

-- =====================================================
-- 3. BLOCKED USERS TABLE
-- =====================================================
-- Stores blocked user relationships
-- If A blocks B, A cannot see B's activities and B cannot interact with A
CREATE TABLE blocked_users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    blocker_user_id BIGINT NOT NULL COMMENT 'User who blocked',
    blocked_user_id BIGINT NOT NULL COMMENT 'User who got blocked',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Foreign keys
    CONSTRAINT fk_blocked_users_blocker FOREIGN KEY (blocker_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_blocked_users_blocked FOREIGN KEY (blocked_user_id) REFERENCES users(id) ON DELETE CASCADE,
    
    -- Unique constraint: Cannot block twice
    CONSTRAINT uk_blocker_blocked UNIQUE (blocker_user_id, blocked_user_id),
    
    -- Indexes
    INDEX idx_blocked_users_blocker (blocker_user_id),
    INDEX idx_blocked_users_blocked (blocked_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Blocked user relationships. Blocker cannot see blocked user activities';

-- =====================================================
-- PART 2: PARTY SYSTEM
-- =====================================================

-- =====================================================
-- 4. PARTIES TABLE
-- =====================================================
-- Stores pre-match party information (PUBG-style squads)
CREATE TABLE parties (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    host_user_id BIGINT NOT NULL COMMENT 'Party host (creator), can kick members',
    party_status VARCHAR(20) NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE, IN_QUEUE, IN_ROOM, IN_GAME, DISBANDED',
    current_room_id BIGINT NULL COMMENT 'If party is in a custom lobby',
    current_matchmaking_id BIGINT NULL COMMENT 'If party is in queue (reserved for future use)',
    max_members INT NOT NULL DEFAULT 4 COMMENT 'Maximum party size (configurable)',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    -- Foreign keys
    CONSTRAINT fk_parties_host_user_id FOREIGN KEY (host_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_parties_current_room_id FOREIGN KEY (current_room_id) REFERENCES rooms(id) ON DELETE SET NULL,
    
    -- Indexes
    INDEX idx_parties_status (party_status),
    INDEX idx_parties_host_user_id (host_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Pre-match parties (squads). status: IDLE=in menu, IN_QUEUE=searching, IN_ROOM=in lobby, IN_GAME=playing, DISBANDED=deleted';

-- =====================================================
-- 5. PARTY MEMBERS TABLE
-- =====================================================
-- Stores party membership information
CREATE TABLE party_members (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    party_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    join_order INT NOT NULL COMMENT '0=host, 1,2,3...=guests',
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Foreign keys
    CONSTRAINT fk_party_members_party_id FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE,
    CONSTRAINT fk_party_members_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    
    -- Unique constraint: User cannot join same party twice
    CONSTRAINT uk_party_user UNIQUE (party_id, user_id),
    
    -- Indexes
    INDEX idx_party_members_user_id (user_id),
    INDEX idx_party_members_party_id (party_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Party membership. join_order: 0=host, 1,2,3=guests';

-- =====================================================
-- 6. PARTY INVITATIONS TABLE
-- =====================================================
-- Stores party invitations (30 second timeout)
CREATE TABLE party_invitations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    party_id BIGINT NOT NULL,
    inviter_user_id BIGINT NOT NULL COMMENT 'Party member who sent invite',
    invitee_user_id BIGINT NOT NULL COMMENT 'User who receives invite',
    invitation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, ACCEPTED, DECLINED, EXPIRED, CANCELLED',
    expires_at TIMESTAMP NOT NULL COMMENT '30 seconds timeout',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    -- Foreign keys
    CONSTRAINT fk_party_invitations_party_id FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE,
    CONSTRAINT fk_party_invitations_inviter FOREIGN KEY (inviter_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_party_invitations_invitee FOREIGN KEY (invitee_user_id) REFERENCES users(id) ON DELETE CASCADE,
    
    -- Indexes
    INDEX idx_party_invitations_invitee (invitee_user_id, invitation_status),
    INDEX idx_party_invitations_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Party invitations. status: PENDING=waiting, ACCEPTED=joined, DECLINED=rejected, EXPIRED=timeout, CANCELLED=inviter cancelled';

-- =====================================================
-- PART 3: GAME MODE CONFIG
-- =====================================================

-- =====================================================
-- 7. GAME MODES TABLE
-- =====================================================
-- Configurable game modes (2v2, 3v3, 4v4, 5v5, etc.)
-- Allows server to enable/disable modes dynamically
CREATE TABLE game_modes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    mode_key VARCHAR(20) NOT NULL UNIQUE COMMENT 'Unique key: 2v2, 3v3, 4v4, 5v5',
    display_name VARCHAR(50) NOT NULL COMMENT 'Display name: "2 vs 2", "3 vs 3"',
    players_per_team INT NOT NULL COMMENT 'Number of players per team: 2, 3, 4, 5',
    total_players INT NOT NULL COMMENT 'Total players in match: 4, 6, 8, 10',
    mode_status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE, LOCKED, COMING_SOON, DISABLED',
    display_order INT NOT NULL DEFAULT 0 COMMENT 'Sort order in UI',
    is_active BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'Soft delete flag',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    -- Indexes
    INDEX idx_game_modes_status (mode_status, is_active),
    INDEX idx_game_modes_order (display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Configurable game modes. status: AVAILABLE=can play, LOCKED=not accessible, COMING_SOON=future, DISABLED=removed';

-- =====================================================
-- Seed data for game modes (2v2, 3v3, 4v4, 5v5)
-- =====================================================
INSERT INTO game_modes (mode_key, display_name, players_per_team, total_players, mode_status, display_order) VALUES
('2v2', '2 vs 2', 2, 4, 'AVAILABLE', 1),
('3v3', '3 vs 3', 3, 6, 'AVAILABLE', 2),
('4v4', '4 vs 4', 4, 8, 'COMING_SOON', 3),
('5v5', '5 vs 5', 5, 10, 'AVAILABLE', 4);

-- =====================================================
-- Migration complete
-- =====================================================
-- Tables created:
-- 1. friends (bidirectional friendships)
-- 2. friend_requests (pending/accepted/declined requests)
-- 3. blocked_users (block relationships)
-- 4. parties (pre-match squads)
-- 5. party_members (party membership)
-- 6. party_invitations (party invites with 30s timeout)
-- 7. game_modes (configurable game modes)
-- =====================================================
