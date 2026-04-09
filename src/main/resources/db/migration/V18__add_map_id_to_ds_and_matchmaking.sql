-- ══════════════════════════════════════════════════════════════════════════════
-- Migration V18: Add map_id support to DS and matchmaking tables
-- Each DS instance is now map-specific (MAP_ID env var tells the DS which
-- scene to load). Matchmaking groups players by mapId so they land on the
-- correct map.
-- ══════════════════════════════════════════════════════════════════════════════

-- Add map_id to dedicated_servers so findAvailable() can filter by map
ALTER TABLE dedicated_servers
    ADD COLUMN map_id VARCHAR(50) NULL
        COMMENT 'MapEntry.mapId this DS instance loaded (e.g. map_01). NULL = any/unset.';

CREATE INDEX idx_ds_map ON dedicated_servers(map_id, status, region);

-- Add map_id to matchmaking_queue so players queuing for different maps
-- are not accidentally matched together.
ALTER TABLE matchmaking_queue
    ADD COLUMN map_id VARCHAR(50) NULL
        COMMENT 'MapEntry.mapId the player requested (e.g. map_01). NULL = any map.';
