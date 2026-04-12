-- ======================================================================
-- V22 — Re-activate game maps that were inadvertently set inactive.
--
-- The game_maps rows for map_01 and map_02 were found with is_active = 0,
-- causing GET /api/maps to return an empty list and the client MapConfig
-- to fall back to local defaults (showing "No maps available" errors).
-- ======================================================================

UPDATE game_maps SET is_active = 1 WHERE map_id IN ('map_01', 'map_02');
