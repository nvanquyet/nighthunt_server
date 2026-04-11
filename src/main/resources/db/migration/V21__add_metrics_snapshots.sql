-- V21: Add server_metrics_snapshots table for time-series analytics
-- Written every minute by MetricsCollectorService (@Scheduled)

CREATE TABLE IF NOT EXISTS server_metrics_snapshots (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_at       DATETIME(3) NOT NULL,
    online_users      INT         NOT NULL DEFAULT 0,
    queue_depth       INT         NOT NULL DEFAULT 0,
    active_rooms      INT         NOT NULL DEFAULT 0,
    in_game_rooms     INT         NOT NULL DEFAULT 0,
    waiting_rooms     INT         NOT NULL DEFAULT 0,
    active_ds         INT         NOT NULL DEFAULT 0,
    in_game_ds        INT         NOT NULL DEFAULT 0,
    players_in_ds     INT         NOT NULL DEFAULT 0,
    matches_last_hour INT         NOT NULL DEFAULT 0,
    INDEX idx_snapshot_at (snapshot_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
