-- ======================================================================
-- V30 — Per-map safe zone configs (standard 4v4 + small 1v1)
--
-- Design rules:
--   • All maps share 3 "core inner phases" (R: 100 → 50 → 25 → 10).
--     Damage doubles each step as the area quarters — forces endgame fights.
--   • Larger maps prepend extra outer phases (R: 400 → 200 → 100).
--   • 1v1 maps use only the 3 core inner phases + Fixed center + shorter timers.
--
-- Phase damage scaling (damage per second outside zone):
--   R > 200 →  3 dmg/s   (early game, just a nudge)
--   R 100-200 → 5 dmg/s
--   R 50-100 →  8 dmg/s  (midgame pressure)
--   R 25-50  → 15 dmg/s  (score bonus zone — reward holding center)
--   R 10-25  → 25 dmg/s  (endgame / final zone)
-- ======================================================================

-- ── map_01: Standard 4v4 — 5 phases, initialRadius=400 ─────────────────────
UPDATE game_maps
SET zone_config = JSON_OBJECT(
    'initialRadius',             400.0,
    'finalZoneMinRadius',         10.0,
    'centerMode',           'PureRandom',
    'maxCenterShiftPercent',       0.6,
    'minCenterShiftPercent',       0.1,
    'beaconAllowedInFinalZone', FALSE,
    'baseSurvivalPtsPerSecond',    1.0,
    'captureZoneScorePerSecond',  20.0,
    'killScore',                 100.0,
    'bossKillScore',             300.0,
    'killScoreStealPercent',      0.15,
    'phases', JSON_ARRAY(
        -- Phase 0: outer ring — early game, long wait, low damage
        JSON_OBJECT('zoneIndex',0, 'startRadius',400.0, 'endRadius',200.0,
                    'waitBeforeShrink',120.0, 'shrinkDuration',90.0,
                    'damagePerSecond',3.0,  'damageTick',1.0,
                    'isScoreBonusZone',FALSE, 'zoneBonusMultiplier',1.0, 'minRadiusOverride',0.0),
        -- Phase 1: midgame start
        JSON_OBJECT('zoneIndex',1, 'startRadius',200.0, 'endRadius',100.0,
                    'waitBeforeShrink',90.0,  'shrinkDuration',75.0,
                    'damagePerSecond',5.0,  'damageTick',1.0,
                    'isScoreBonusZone',FALSE, 'zoneBonusMultiplier',1.0, 'minRadiusOverride',0.0),
        -- Phase 2: core inner — midgame pressure  [SHARED with all maps]
        JSON_OBJECT('zoneIndex',2, 'startRadius',100.0, 'endRadius',50.0,
                    'waitBeforeShrink',60.0,  'shrinkDuration',60.0,
                    'damagePerSecond',8.0,  'damageTick',1.0,
                    'isScoreBonusZone',FALSE, 'zoneBonusMultiplier',1.0, 'minRadiusOverride',0.0),
        -- Phase 3: bonus zone — hold center for 2× score multiplier  [SHARED]
        JSON_OBJECT('zoneIndex',3, 'startRadius',50.0,  'endRadius',25.0,
                    'waitBeforeShrink',45.0,  'shrinkDuration',45.0,
                    'damagePerSecond',15.0, 'damageTick',1.0,
                    'isScoreBonusZone',TRUE,  'zoneBonusMultiplier',2.0, 'minRadiusOverride',0.0),
        -- Phase 4: endgame / final zone — heavy damage, zone stops shrinking  [SHARED]
        JSON_OBJECT('zoneIndex',4, 'startRadius',25.0,  'endRadius',10.0,
                    'waitBeforeShrink',30.0,  'shrinkDuration',30.0,
                    'damagePerSecond',25.0, 'damageTick',1.0,
                    'isScoreBonusZone',FALSE, 'zoneBonusMultiplier',1.0, 'minRadiusOverride',10.0)
    )
)
WHERE map_id = 'map_01';

-- ── map_02: Small 1v1 arena — 3 phases, initialRadius=100 ──────────────────
-- Uses only the 3 core inner phases (R: 100 → 50 → 25 → 10).
-- Fixed center: zone never drifts on a small arena.
-- Shorter wait/shrink timers: 1v1 matches resolve faster.
UPDATE game_maps
SET zone_config = JSON_OBJECT(
    'initialRadius',              100.0,
    'finalZoneMinRadius',          10.0,
    'centerMode',              'Fixed',
    'maxCenterShiftPercent',        0.0,
    'minCenterShiftPercent',        0.0,
    'beaconAllowedInFinalZone',  FALSE,
    'baseSurvivalPtsPerSecond',     1.0,
    'captureZoneScorePerSecond',   20.0,
    'killScore',                  100.0,
    'bossKillScore',              300.0,
    'killScoreStealPercent',       0.15,
    'phases', JSON_ARRAY(
        -- Phase 0: midgame start — same damage as standard phase 2
        JSON_OBJECT('zoneIndex',0, 'startRadius',100.0, 'endRadius',50.0,
                    'waitBeforeShrink',45.0,  'shrinkDuration',45.0,
                    'damagePerSecond',8.0,  'damageTick',1.0,
                    'isScoreBonusZone',FALSE, 'zoneBonusMultiplier',1.0, 'minRadiusOverride',0.0),
        -- Phase 1: bonus zone — same as standard phase 3
        JSON_OBJECT('zoneIndex',1, 'startRadius',50.0,  'endRadius',25.0,
                    'waitBeforeShrink',30.0,  'shrinkDuration',30.0,
                    'damagePerSecond',15.0, 'damageTick',1.0,
                    'isScoreBonusZone',TRUE,  'zoneBonusMultiplier',2.0, 'minRadiusOverride',0.0),
        -- Phase 2: endgame — same as standard phase 4
        JSON_OBJECT('zoneIndex',2, 'startRadius',25.0,  'endRadius',10.0,
                    'waitBeforeShrink',20.0,  'shrinkDuration',20.0,
                    'damagePerSecond',25.0, 'damageTick',1.0,
                    'isScoreBonusZone',FALSE, 'zoneBonusMultiplier',1.0, 'minRadiusOverride',10.0)
    )
)
WHERE map_id = 'map_02';

-- ── Template for future large maps (6 phases, initialRadius=800) ─────────────
-- Copy this template and change 'map_large' to the new mapId, then INSERT a row
-- into game_maps with the appropriate scene_name, display_name, etc.
--
-- INSERT INTO game_maps (map_id, display_name, scene_name, zone_config, ...)
-- VALUES ('map_large', 'Large Map', 'GameMap_Large', JSON_OBJECT(
--     'initialRadius', 800.0, 'finalZoneMinRadius', 10.0, 'centerMode', 'PureRandom',
--     'maxCenterShiftPercent', 0.6, 'minCenterShiftPercent', 0.1,
--     'beaconAllowedInFinalZone', FALSE, 'baseSurvivalPtsPerSecond', 1.0,
--     'captureZoneScorePerSecond', 20.0, 'killScore', 100.0, 'bossKillScore', 300.0,
--     'killScoreStealPercent', 0.15,
--     'phases', JSON_ARRAY(
--         JSON_OBJECT('zoneIndex',0,'startRadius',800.0,'endRadius',400.0,'waitBeforeShrink',150.0,'shrinkDuration',120.0,'damagePerSecond',2.0,'damageTick',1.0,'isScoreBonusZone',FALSE,'zoneBonusMultiplier',1.0,'minRadiusOverride',0.0),
--         JSON_OBJECT('zoneIndex',1,'startRadius',400.0,'endRadius',200.0,'waitBeforeShrink',120.0,'shrinkDuration', 90.0,'damagePerSecond',3.0,'damageTick',1.0,'isScoreBonusZone',FALSE,'zoneBonusMultiplier',1.0,'minRadiusOverride',0.0),
--         JSON_OBJECT('zoneIndex',2,'startRadius',200.0,'endRadius',100.0,'waitBeforeShrink', 90.0,'shrinkDuration', 75.0,'damagePerSecond',5.0,'damageTick',1.0,'isScoreBonusZone',FALSE,'zoneBonusMultiplier',1.0,'minRadiusOverride',0.0),
--         JSON_OBJECT('zoneIndex',3,'startRadius',100.0,'endRadius', 50.0,'waitBeforeShrink', 60.0,'shrinkDuration', 60.0,'damagePerSecond',8.0,'damageTick',1.0,'isScoreBonusZone',FALSE,'zoneBonusMultiplier',1.0,'minRadiusOverride',0.0),
--         JSON_OBJECT('zoneIndex',4,'startRadius', 50.0,'endRadius', 25.0,'waitBeforeShrink', 45.0,'shrinkDuration', 45.0,'damagePerSecond',15.0,'damageTick',1.0,'isScoreBonusZone',TRUE,'zoneBonusMultiplier',2.0,'minRadiusOverride',0.0),
--         JSON_OBJECT('zoneIndex',5,'startRadius', 25.0,'endRadius', 10.0,'waitBeforeShrink', 30.0,'shrinkDuration', 30.0,'damagePerSecond',25.0,'damageTick',1.0,'isScoreBonusZone',FALSE,'zoneBonusMultiplier',1.0,'minRadiusOverride',10.0)
--     )
-- ), ...);
