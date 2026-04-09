-- V7: Dedicated Server Instance table
-- Lưu trạng thái từng DS container đang chạy
-- Backend dùng bảng này để:
--   1. Tìm server available cho client (matchmaking)
--   2. Track server lifecycle (starting → ready → in_game → stopped)
--   3. Cleanup containers khi game kết thúc hoặc server crash

CREATE TABLE IF NOT EXISTS dedicated_servers (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    server_id            VARCHAR(36)  NOT NULL UNIQUE COMMENT 'UUID - trùng với SERVER_ID env trong container',
    docker_container_id  VARCHAR(64)  COMMENT 'Docker container ID để stop/remove',
    ip                   VARCHAR(45)  NOT NULL COMMENT 'IP public của VPS',
    port                 INT          NOT NULL COMMENT 'UDP port (7777-7900)',
    status               VARCHAR(20)  NOT NULL DEFAULT 'starting' COMMENT 'starting|ready|in_game|stopped',
    region               VARCHAR(20)  NOT NULL DEFAULT 'vn',
    current_players      INT          NOT NULL DEFAULT 0,
    max_players          INT          NOT NULL DEFAULT 16,
    image_tag            VARCHAR(100) COMMENT 'Docker image tag dùng để spawn container',
    server_secret_hash   VARCHAR(255) NOT NULL COMMENT 'BCrypt hash của SERVER_SECRET',
    last_heartbeat_at    DATETIME     COMMENT 'Thời gian heartbeat cuối (NULL = chưa register)',
    started_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    stopped_at           DATETIME     COMMENT 'NULL = đang chạy',

    INDEX idx_ds_status  (status),
    INDEX idx_ds_port    (port),
    INDEX idx_ds_region  (region, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
