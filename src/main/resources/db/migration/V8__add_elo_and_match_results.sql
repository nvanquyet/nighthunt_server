-- V8: Add ELO rating, tier, and match history columns
-- Used by BE-29 (ELO service), linked from match_player_results

-- ── users: ELO + tier ────────────────────────────────────────────────────────
ALTER TABLE users
    ADD COLUMN elo          INT          NOT NULL DEFAULT 1000 COMMENT 'Current ELO rating',
    ADD COLUMN tier         VARCHAR(20)  NOT NULL DEFAULT 'BRONZE' COMMENT 'BRONZE/SILVER/GOLD/PLATINUM/DIAMOND/MASTER',
    ADD COLUMN total_wins   INT          NOT NULL DEFAULT 0,
    ADD COLUMN total_losses INT          NOT NULL DEFAULT 0,
    ADD COLUMN total_draws  INT          NOT NULL DEFAULT 0;

-- ── matches: winner team and end reason ──────────────────────────────────────
ALTER TABLE matches
    ADD COLUMN winner_team_id INT         NULL COMMENT '-1 = DRAW',
    ADD COLUMN end_reason     VARCHAR(30) NULL COMMENT 'TEAM_ELIMINATED | TIMER_EXPIRED | DRAW',
    ADD COLUMN game_mode      VARCHAR(20) NULL COMMENT '2v2 | 3v3 | 5v5';

-- ── match_player_results: per-player stats for each finished match ───────────
CREATE TABLE IF NOT EXISTS match_player_results (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    match_id        VARCHAR(50)  NOT NULL,
    user_id         BIGINT       NOT NULL,
    team_id         INT          NOT NULL,
    display_name    VARCHAR(100) NOT NULL,
    kills           INT          NOT NULL DEFAULT 0,
    deaths          INT          NOT NULL DEFAULT 0,
    score           INT          NOT NULL DEFAULT 0,
    elo_before      INT          NOT NULL DEFAULT 1000,
    elo_after       INT          NOT NULL DEFAULT 1000,
    elo_change      INT          NOT NULL DEFAULT 0,
    placement       INT          NOT NULL DEFAULT 0 COMMENT '1=winner, 2=loser, 0=draw',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_mpr_match_id (match_id),
    INDEX idx_mpr_user_id  (user_id),
    FOREIGN KEY (user_id)  REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── matchmaking_queue: ranked queue entries ──────────────────────────────────
CREATE TABLE IF NOT EXISTS matchmaking_queue (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL UNIQUE,
    elo             INT          NOT NULL DEFAULT 1000,
    game_mode       VARCHAR(20)  NOT NULL,
    queued_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    search_min_elo  INT          NOT NULL DEFAULT 0,
    search_max_elo  INT          NOT NULL DEFAULT 9999,
    status          VARCHAR(20)  NOT NULL DEFAULT 'SEARCHING' COMMENT 'SEARCHING | MATCHED | CANCELLED',
    PRIMARY KEY (id),
    INDEX idx_mmq_user_id   (user_id),
    INDEX idx_mmq_game_mode (game_mode),
    INDEX idx_mmq_status    (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
