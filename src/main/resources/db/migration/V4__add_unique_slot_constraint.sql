-- =====================================================
-- Add unique constraint on (room_id, team, slot) to prevent race conditions
-- =====================================================
-- Flyway Migration: V4
-- Description: Prevent multiple players from occupying the same slot in the same room
-- Reason: Race condition when multiple users try to move to the same empty slot simultaneously
-- =====================================================

-- Add unique constraint on (room_id, team, slot)
-- This ensures only one player can occupy a specific slot in a room at a time
ALTER TABLE room_players 
ADD CONSTRAINT uk_room_team_slot UNIQUE (room_id, team, slot);

