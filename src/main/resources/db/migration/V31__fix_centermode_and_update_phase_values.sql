-- ======================================================================
-- V31 — Fix centerMode encoding + update phase values to match production
--
-- FIXES:
--   • centerMode was stored as string ("PureRandom"/"Fixed").
--     Unity's JsonUtility deserializes enums as integer only.
--     Convert to integer: PureRandom=0, CenterBiased=1, Fixed=2
--
-- PHASE CHANGES (map_01 Standard 4v4):
--   Phase 0: shrinkDuration 90s → 180s, damagePerSecond 3 → 0 (early game grace period)
--   Phase 1: shrinkDuration 75s → 120s, damagePerSecond 5 → 3
--   Phase 2: shrinkDuration 60s → 90s,  isScoreBonusZone false → true, multiplier → 2.0
--   Phase 3: shrinkDuration 45s → 60s,  isScoreBonusZone true  → false
--   Phase 4: unchanged
--
-- PHASE CHANGES (map_02 Small 1v1):
--   Phase 0: isScoreBonusZone false → true, multiplier → 2.0
--   Phase 1: shrinkDuration 30s → 30s (no change), isScoreBonusZone true → false
--   Phase 2: unchanged
-- ======================================================================

-- ── map_01: Standard 4v4 — 5 phases, initialRadius=400 ──────────────────────
UPDATE game_maps
SET zone_config = JSON_OBJECT(
    'initialRadius',             400.0,
    'finalZoneMinRadius',         10.0,
    'centerMode',                    0,    -- PureRandom (integer, was string "PureRandom")
    'maxCenterShiftPercent',       0.6,
    'minCenterShiftPercent',       0.1,
    'beaconAllowedInFinalZone', FALSE,
    'baseSurvivalPtsPerSecond',    1.0,
    'captureZoneScorePerSecond',  20.0,
    'killScore',                 100.0,
    'bossKillScore',             300.0,
    'killScoreStealPercent',      0.15,
    'phases', JSON_ARRAY(
        -- Phase 0: early game — long shrink, no damage (grace period to reposition)
        JSON_OBJECT('zoneIndex',0, 'startRadius',400.0, 'endRadius',200.0,
                    'waitBeforeShrink',120.0, 'shrinkDuration',180.0,
                    'damagePerSecond',0.0,  'damageTick',1.0,
                    'isScoreBonusZone',FALSE, 'zoneBonusMultiplier',1.5, 'minRadiusOverride',0.0),
        -- Phase 1: midgame start
        JSON_OBJECT('zoneIndex',1, 'startRadius',200.0, 'endRadius',100.0,
                    'waitBeforeShrink',90.0,  'shrinkDuration',120.0,
                    'damagePerSecond',3.0,  'damageTick',1.0,
                    'isScoreBonusZone',FALSE, 'zoneBonusMultiplier',1.5, 'minRadiusOverride',0.0),
        -- Phase 2: bonus zone — hold center for 2× score multiplier
        JSON_OBJECT('zoneIndex',2, 'startRadius',100.0, 'endRadius',50.0,
                    'waitBeforeShrink',60.0,  'shrinkDuration',90.0,
                    'damagePerSecond',8.0,  'damageTick',1.0,
                    'isScoreBonusZone',TRUE,  'zoneBonusMultiplier',2.0, 'minRadiusOverride',0.0),
        -- Phase 3: late game pressure
        JSON_OBJECT('zoneIndex',3, 'startRadius',50.0,  'endRadius',25.0,
                    'waitBeforeShrink',45.0,  'shrinkDuration',60.0,
                    'damagePerSecond',15.0, 'damageTick',1.0,
                    'isScoreBonusZone',FALSE, 'zoneBonusMultiplier',1.5, 'minRadiusOverride',0.0),
        -- Phase 4: endgame / final zone
        JSON_OBJECT('zoneIndex',4, 'startRadius',25.0,  'endRadius',10.0,
                    'waitBeforeShrink',30.0,  'shrinkDuration',30.0,
                    'damagePerSecond',25.0, 'damageTick',1.0,
                    'isScoreBonusZone',FALSE, 'zoneBonusMultiplier',1.5, 'minRadiusOverride',10.0)
    )
)
WHERE map_id = 'map_01';

-- ── map_02: Small 1v1 arena — 3 phases, initialRadius=100 ───────────────────
UPDATE game_maps
SET zone_config = JSON_OBJECT(
    'initialRadius',              100.0,
    'finalZoneMinRadius',          10.0,
    'centerMode',                    2,    -- Fixed (integer, was string "Fixed")
    'maxCenterShiftPercent',        0.0,
    'minCenterShiftPercent',        0.0,
    'beaconAllowedInFinalZone',  FALSE,
    'baseSurvivalPtsPerSecond',     1.0,
    'captureZoneScorePerSecond',   20.0,
    'killScore',                  100.0,
    'bossKillScore',              300.0,
    'killScoreStealPercent',       0.15,
    'phases', JSON_ARRAY(
        -- Phase 0: bonus zone
        JSON_OBJECT('zoneIndex',0, 'startRadius',100.0, 'endRadius',50.0,
                    'waitBeforeShrink',45.0,  'shrinkDuration',45.0,
                    'damagePerSecond',8.0,  'damageTick',1.0,
                    'isScoreBonusZone',TRUE,  'zoneBonusMultiplier',2.0, 'minRadiusOverride',0.0),
        -- Phase 1: late game
        JSON_OBJECT('zoneIndex',1, 'startRadius',50.0,  'endRadius',25.0,
                    'waitBeforeShrink',30.0,  'shrinkDuration',30.0,
                    'damagePerSecond',15.0, 'damageTick',1.0,
                    'isScoreBonusZone',FALSE, 'zoneBonusMultiplier',1.5, 'minRadiusOverride',0.0),
        -- Phase 2: endgame
        JSON_OBJECT('zoneIndex',2, 'startRadius',25.0,  'endRadius',10.0,
                    'waitBeforeShrink',20.0,  'shrinkDuration',20.0,
                    'damagePerSecond',25.0, 'damageTick',1.0,
                    'isScoreBonusZone',FALSE, 'zoneBonusMultiplier',1.5, 'minRadiusOverride',10.0)
    )
)
WHERE map_id = 'map_02';
