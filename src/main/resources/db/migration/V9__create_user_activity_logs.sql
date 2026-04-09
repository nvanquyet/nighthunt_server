-- V9: User activity logs for admin dashboard monitoring
CREATE TABLE user_activity_logs (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT,
    username    VARCHAR(50),
    event_type  VARCHAR(50)  NOT NULL,
    event_data  TEXT,
    ip_address  VARCHAR(50),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ual_user_id    (user_id),
    INDEX idx_ual_event_type (event_type),
    INDEX idx_ual_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
