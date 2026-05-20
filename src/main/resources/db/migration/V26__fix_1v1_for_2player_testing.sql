-- ======================================================================
-- V26 — Change 1v1 from devMode to a proper non-devMode 2-player test mode.
--
-- Problem:
--   With is_dev_mode=1, MatchmakingQueueService sets minRequired=1 for
--   the 1v1 mode.  This means each queued player is matched ALONE into
--   their own match.  Two developers testing together can never land in
--   the same match.
--
-- Fix:
--   Set is_dev_mode=0 so minRequired = total_players = 2.
--   Two players queuing "1v1" are now matched TOGETHER.
--
-- Client impact:
--   With is_dev_mode=0, the server's GET /api/game-modes now RETURNS
--   this mode (findDisplayableGameModes() no longer hides it).
--   The client's GameModeConfig.LoadFromRemote() receives it and the
--   entry appears in the matchmaking dropdown for all builds.
--
-- Note: mode_status stays 'AVAILABLE', matchmaking_enabled stays 1.
-- ======================================================================

UPDATE game_modes
    SET is_dev_mode  = 0,
        display_name = '1 vs 1',
        description  = '1v1 ranked test mode \u2014 2 players, 1 per team.',
        updated_at   = NOW()
    WHERE mode_key = '1v1';

-- Ensure it is active and selectable just in case a previous migration
-- accidentally set it to a non-available state.
UPDATE game_modes
    SET mode_status          = 'AVAILABLE',
        matchmaking_enabled  = 1,
        is_active            = 1
    WHERE mode_key = '1v1';
