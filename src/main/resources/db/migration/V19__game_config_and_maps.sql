-- ======================================================================
-- V19 — Unified game config: extend game_modes, add game_maps,
--        add game_config singleton table
-- ======================================================================

-- ── 1. Extend game_modes ───────────────────────────────────────────────
ALTER TABLE game_modes
    ADD COLUMN description       VARCHAR(120)   NULL     COMMENT 'Short description shown in UI'      AFTER display_name,
    ADD COLUMN allow_fill        TINYINT(1) NOT NULL DEFAULT 0  COMMENT 'Server may fill empty slots with solos' AFTER total_players,
    ADD COLUMN matchmaking_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '0 = disable ranked queue for this mode' AFTER allow_fill,
    ADD COLUMN min_elo           INT NOT NULL DEFAULT 0    COMMENT 'Minimum ELO to enter this mode'  AFTER matchmaking_enabled,
    ADD COLUMN max_elo           INT NOT NULL DEFAULT 9999 COMMENT 'Maximum ELO cap (0 = unlimited)' AFTER min_elo;

-- Update seed data with descriptions + fill flags
UPDATE game_modes SET description = 'Duo match — team of two', allow_fill = 1, matchmaking_enabled = 1 WHERE mode_key = '2v2';
UPDATE game_modes SET description = 'Squad match — team of three', allow_fill = 1, matchmaking_enabled = 1 WHERE mode_key = '3v3';
UPDATE game_modes SET description = 'Full squad — team of four', allow_fill = 0, matchmaking_enabled = 0 WHERE mode_key = '4v4';
UPDATE game_modes SET description = 'Large battle — five per side', allow_fill = 1, matchmaking_enabled = 0 WHERE mode_key = '5v5';

-- ── 2. Create game_maps table ──────────────────────────────────────────
CREATE TABLE game_maps (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    map_id         VARCHAR(50)  NOT NULL UNIQUE COMMENT 'Matches MapEntry.mapId on client: map_01, map_02 …',
    display_name   VARCHAR(80)  NOT NULL,
    description    VARCHAR(200) NULL,
    scene_name     VARCHAR(80)  NOT NULL COMMENT 'Unity scene file name without .unity, e.g. GameMap_01',
    supported_modes JSON         NULL     COMMENT 'JSON array of modeKey strings, e.g. ["2v2","3v3"]. NULL = all modes',
    is_locked      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1 = Coming Soon, cannot be queued into',
    is_active      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '0 = soft-deleted / hidden',
    display_order  INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_maps_active   (is_active, is_locked),
    INDEX idx_maps_order    (display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Playable maps. Synced to client at startup via GET /api/maps';

INSERT INTO game_maps (map_id, display_name, description, scene_name, supported_modes, is_locked, display_order) VALUES
('map_01', 'Industrial Zone',   'Urban combat in a derelict factory.',           'GameMap_01', '["2v2","3v3","4v4","5v5"]', 0, 1),
('map_02', 'Arctic Base',       'Close-quarters in a frozen research facility.', 'GameMap_02', '["2v2","3v3"]',             0, 2);

-- ── 3. Create game_config table ────────────────────────────────────────
-- Single-row key/value store for runtime-tunable matchmaking parameters.
-- Keys are documented here; admin can UPDATE them at any time.
CREATE TABLE game_config (
    config_key   VARCHAR(80)   NOT NULL PRIMARY KEY COMMENT 'Dot-notation key, e.g. matchmaking.elo.initialRange',
    config_value VARCHAR(255)  NOT NULL,
    value_type   VARCHAR(20)   NOT NULL DEFAULT 'STRING' COMMENT 'STRING, INT, FLOAT, BOOL, JSON',
    description  VARCHAR(200)  NULL,
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Runtime-editable game configuration. Replaces hard-coded @Value and env-var params.';

INSERT INTO game_config (config_key, config_value, value_type, description) VALUES
-- Matchmaking ELO
('matchmaking.elo.initialRange',        '100',  'INT',  'Initial ELO window half-width (±X) on queue entry'),
('matchmaking.elo.expandStep',          '50',   'INT',  'ELO expand per side every expandInterval seconds'),
('matchmaking.elo.expandIntervalSec',   '15',   'INT',  'Seconds between ELO window expansions'),
('matchmaking.elo.maxRange',            '500',  'INT',  'Maximum ELO window half-width'),
-- Matchmaking timing
('matchmaking.tick.intervalMs',         '5000', 'INT',  'How often the matchmaking scheduler runs (ms)'),
('matchmaking.acceptTimeout.sec',       '30',   'INT',  'Seconds for player to accept before auto-decline'),
-- DS defaults
('ds.defaultMaxPlayers',                '16',   'INT',  'Max players per dedicated server instance'),
('ds.portStart',                        '7777', 'INT',  'First UDP port in DS port range'),
('ds.portEnd',                          '7900', 'INT',  'Last UDP port in DS port range'),
-- Room defaults
('room.maxPlayersPerTeam',              '5',    'INT',  'Hard cap on players per team in any room'),
('room.minPlayersToStart',              '2',    'INT',  'Minimum total players required to start a match');
