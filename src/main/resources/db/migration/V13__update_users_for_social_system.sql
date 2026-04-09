-- =====================================================
-- Update Users Table for Social System
-- =====================================================
-- Flyway Migration: V13
-- Description: Add online status, party tracking, and room tracking to users table
-- Author: Social System Implementation
-- Date: March 15, 2026
-- Compatible with: MySQL 8.0+
-- =====================================================

-- =====================================================
-- Add new columns to users table
-- =====================================================

-- 1. Online status tracking (ONLINE, OFFLINE, AWAY, IN_GAME)
ALTER TABLE users 
ADD COLUMN online_status VARCHAR(20) NOT NULL DEFAULT 'OFFLINE' 
COMMENT 'Online status: ONLINE=active, OFFLINE=logged out, AWAY=idle, IN_GAME=playing match'
AFTER email;

-- 2. Current party tracking (NULL if not in party)
ALTER TABLE users 
ADD COLUMN current_party_id BIGINT NULL 
COMMENT 'Party ID if user is in a party, NULL otherwise'
AFTER online_status;

-- 3. Current room tracking (NULL if not in room)
ALTER TABLE users 
ADD COLUMN current_room_id BIGINT NULL 
COMMENT 'Room ID if user is in custom lobby, NULL otherwise'
AFTER current_party_id;

-- 4. Last seen timestamp (for offline users)
ALTER TABLE users 
ADD COLUMN last_seen_at TIMESTAMP NULL 
COMMENT 'Last activity timestamp when user went offline'
AFTER current_room_id;

-- =====================================================
-- Add foreign keys
-- =====================================================

ALTER TABLE users 
ADD CONSTRAINT fk_users_current_party_id 
FOREIGN KEY (current_party_id) REFERENCES parties(id) ON DELETE SET NULL;

ALTER TABLE users 
ADD CONSTRAINT fk_users_current_room_id 
FOREIGN KEY (current_room_id) REFERENCES rooms(id) ON DELETE SET NULL;

-- =====================================================
-- Add indexes for efficient queries
-- =====================================================

-- Index for querying users by online status
CREATE INDEX idx_users_online_status ON users(online_status);

-- Index for querying users in a specific party
CREATE INDEX idx_users_current_party_id ON users(current_party_id);

-- Index for querying users in a specific room
CREATE INDEX idx_users_current_room_id ON users(current_room_id);

-- Index for sorting users by last seen (friend list sorting)
CREATE INDEX idx_users_last_seen_at ON users(last_seen_at);

-- =====================================================
-- Migration complete
-- =====================================================
-- Columns added:
-- 1. online_status (VARCHAR(20)) - ONLINE, OFFLINE, AWAY, IN_GAME
-- 2. current_party_id (BIGINT, FK to parties) - NULL if not in party
-- 3. current_room_id (BIGINT, FK to rooms) - NULL if not in room
-- 4. last_seen_at (TIMESTAMP) - Last activity timestamp
--
-- Indexes added:
-- - idx_users_online_status
-- - idx_users_current_party_id
-- - idx_users_current_room_id
-- - idx_users_last_seen_at
-- =====================================================
