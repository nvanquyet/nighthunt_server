-- V11: Create refresh_tokens table for long-lived session management
-- Refresh tokens survive past the short-lived JWT access token (15 min) expiry.
-- Access  token lifetime: controlled by jwt.expiration property (e.g. 900_000 ms = 15 min)
-- Refresh token lifetime: REFRESH_TOKEN_EXPIRY_DAYS (default 30 days, stored in expiry_date)

CREATE TABLE refresh_tokens (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    token       VARCHAR(512) NOT NULL UNIQUE  COMMENT 'Opaque UUID-based token stored as-is',
    expiry_date DATETIME     NOT NULL         COMMENT 'Hard expiry; reject if NOW() > expiry_date',
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE COMMENT 'TRUE after logout or token rotation',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_rt_user_id    (user_id),
    INDEX idx_rt_token      (token),
    INDEX idx_rt_expiry     (expiry_date),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Stores long-lived refresh tokens. One active token per user (old tokens revoked on rotate).';
