CREATE TABLE realtime_outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(36) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    last_error VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_realtime_outbox_event_id (event_id),
    KEY idx_realtime_outbox_ready (status, available_at, id)
) ENGINE=InnoDB;
