-- V24: Add coin wallet, platform tag, and platform-based matchmaking filter
--
-- coins       : in-game currency earned from match rewards
-- platform    : device platform of each user/queue entry (MOBILE / PC)
-- platformFilter: per-game-mode restriction on which platforms may queue

-- ── users ────────────────────────────────────────────────────────────────────
ALTER TABLE users
    ADD COLUMN coins         BIGINT       NOT NULL DEFAULT 0,
    ADD COLUMN platform      VARCHAR(20)  NULL;     -- MOBILE | PC | NULL = unknown

-- ── matchmaking_queue ────────────────────────────────────────────────────────
ALTER TABLE matchmaking_queue
    ADD COLUMN platform      VARCHAR(20)  NULL;     -- mirrors the user's platform at queue time

-- ── game_modes ───────────────────────────────────────────────────────────────
ALTER TABLE game_modes
    ADD COLUMN platform_filter VARCHAR(20) NOT NULL DEFAULT 'ALL'; -- ALL | MOBILE | PC
