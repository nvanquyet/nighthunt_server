-- ============================================================
-- V28 — Create user_abandon_records table
-- Records every mid-match AFK/abandon event for:
--   • ELO penalty tracking
--   • Repeat-offender detection
--   • Admin audit
-- ============================================================
CREATE TABLE user_abandon_records (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL COMMENT 'FK → users.id',
    match_id      VARCHAR(64)  NOT NULL COMMENT 'Room.matchId string',
    room_id       BIGINT       NOT NULL COMMENT 'FK → rooms.id',
    reason        VARCHAR(50)  NOT NULL COMMENT 'AFK_TIMEOUT | INTENTIONAL_LEAVE | LOGOUT | SESSION_EXPIRED | FORCE_LOGOUT',
    elo_before    INT          NOT NULL DEFAULT 0,
    elo_change    INT          NOT NULL DEFAULT 0 COMMENT 'Negative = penalty, 0 = no penalty applied',
    recorded_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_abandon_user_id       (user_id),
    INDEX idx_abandon_match_id      (match_id),
    INDEX idx_abandon_recorded_at   (recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Mid-match AFK / abandon event log with ELO penalty history';
