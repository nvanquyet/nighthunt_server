-- ======================================================================
-- V20 — Add is_dev_mode flag to game_modes; seed 1v1 dev/test mode.
--
-- Purpose:
--   Dev modes are excluded from the client-facing GET /api/gamemodes
--   response so they never appear in production builds.  They ARE still
--   accepted by the matchmaking queue validator, so a developer can
--   queue with "1v1" for solo DS testing (--expectedPlayers 1).
-- ======================================================================

-- ── 1. Add is_dev_mode column ──────────────────────────────────────────
ALTER TABLE game_modes
    ADD COLUMN is_dev_mode TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'Dev/test mode — excluded from client API; accessible to matchmaking queue only.'
        AFTER is_active;

-- ── 2. Seed 1v1 dev test mode ──────────────────────────────────────────
INSERT INTO game_modes (
    mode_key, display_name, description,
    players_per_team, total_players,
    mode_status, allow_fill, matchmaking_enabled,
    min_elo, max_elo,
    display_order, is_active, is_dev_mode
) VALUES (
    '1v1', '1 vs 1 (Dev)',
    'Solo DS test — 1 player per team. Launch DS with --expectedPlayers 1.',
    1, 2,
    'AVAILABLE', 1, 1,
    0, 9999,
    0, 1, 1
);

-- ── 3. Include 1v1 in game_maps supported_modes ────────────────────────
-- Allows MapConfig.GetByMode("1v1") on the client to return valid maps.
UPDATE game_maps
    SET supported_modes = JSON_ARRAY_APPEND(supported_modes, '$', '1v1')
    WHERE map_id = 'map_01';

UPDATE game_maps
    SET supported_modes = JSON_ARRAY_APPEND(supported_modes, '$', '1v1')
    WHERE map_id = 'map_02';
