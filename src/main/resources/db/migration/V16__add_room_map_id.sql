-- Flyway Migration: V16
-- Add room map selection support for custom rooms.

ALTER TABLE rooms
    ADD COLUMN map_id VARCHAR(50) NOT NULL DEFAULT 'map_01' AFTER mode;

UPDATE rooms
SET map_id = 'map_01'
WHERE map_id IS NULL OR TRIM(map_id) = '';