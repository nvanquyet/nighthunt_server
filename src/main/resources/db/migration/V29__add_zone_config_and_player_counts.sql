-- ======================================================================
-- V29 — Add zone_config and supported_player_counts to game_maps
--       zone_config: JSON blob of SafeZoneMatchConfig (fetched by DS on boot)
--       supported_player_counts: JSON array of total-player counts, e.g. [4,6,10]
-- ======================================================================

ALTER TABLE game_maps
    ADD COLUMN zone_config               JSON NULL
        COMMENT 'SafeZoneMatchConfig JSON blob. NULL = server uses SafeZoneMatchConfig.Default()'
        AFTER supported_modes,
    ADD COLUMN supported_player_counts   JSON NULL
        COMMENT 'JSON int array of total-player counts, e.g. [4,6,10]. NULL = no player-count filter'
        AFTER zone_config;

-- ── Seed default zone configs for existing maps ──────────────────────
-- Default: 4 shrinking zones, 400m → 200m → 100m → 25m, 90s shrink each
UPDATE game_maps
SET zone_config = JSON_OBJECT(
    'initialRadius', 400.0,
    'finalZoneMinRadius', 25.0,
    'centerMode', 'PureRandom',
    'maxCenterShiftPercent', 0.6,
    'minCenterShiftPercent', 0.1,
    'beaconAllowedInFinalZone', FALSE,
    'baseSurvivalPtsPerSecond', 1.0,
    'captureZoneScorePerSecond', 20.0,
    'killScore', 100.0,
    'bossKillScore', 300.0,
    'killScoreStealPercent', 0.15,
    'phases', JSON_ARRAY(
        JSON_OBJECT('zoneIndex',0,'startRadius',400.0,'endRadius',200.0,'waitBeforeShrink',60.0,'shrinkDuration',90.0,'damagePerSecond',3.0,'damageTick',1.0,'isScoreBonusZone',FALSE,'zoneBonusMultiplier',1.0,'minRadiusOverride',0.0),
        JSON_OBJECT('zoneIndex',1,'startRadius',200.0,'endRadius',100.0,'waitBeforeShrink',60.0,'shrinkDuration',90.0,'damagePerSecond',5.0,'damageTick',1.0,'isScoreBonusZone',FALSE,'zoneBonusMultiplier',1.0,'minRadiusOverride',0.0),
        JSON_OBJECT('zoneIndex',2,'startRadius',100.0,'endRadius',50.0,'waitBeforeShrink',45.0,'shrinkDuration',60.0,'damagePerSecond',8.0,'damageTick',1.0,'isScoreBonusZone',TRUE,'zoneBonusMultiplier',1.5,'minRadiusOverride',0.0),
        JSON_OBJECT('zoneIndex',3,'startRadius',50.0,'endRadius',25.0,'waitBeforeShrink',30.0,'shrinkDuration',60.0,'damagePerSecond',15.0,'damageTick',1.0,'isScoreBonusZone',FALSE,'zoneBonusMultiplier',1.0,'minRadiusOverride',25.0)
    )
)
WHERE map_id IN ('map_01', 'map_02');

-- Seed supported_player_counts (4-player and 6-player total)
UPDATE game_maps SET supported_player_counts = '[4,6]' WHERE map_id = 'map_01';
UPDATE game_maps SET supported_player_counts = '[4]'   WHERE map_id = 'map_02';
