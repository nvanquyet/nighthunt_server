-- ══════════════════════════════════════════════════════════════════════════════
-- Migration V14: Add matchmaking accept/lobby columns
-- Date: 2026-03-15
-- Description: Add missing columns for matchmaking accept flow
-- ══════════════════════════════════════════════════════════════════════════════

-- Add lobby_token column for matched groups
ALTER TABLE matchmaking_queue 
ADD COLUMN lobby_token VARCHAR(64) NULL COMMENT 'Shared token for matched group';

-- Add accept_status column for per-player accept state
ALTER TABLE matchmaking_queue 
ADD COLUMN accept_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' 
COMMENT 'PENDING | ACCEPTED | DECLINED';

-- Add index for lobby_token lookups
CREATE INDEX idx_mmq_lobby_token ON matchmaking_queue(lobby_token);

-- Add index for accept_status filtering
CREATE INDEX idx_mmq_accept_status ON matchmaking_queue(accept_status);
