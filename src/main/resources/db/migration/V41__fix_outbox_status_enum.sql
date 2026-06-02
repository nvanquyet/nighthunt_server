-- Fix realtime_outbox_event.status column type.
-- Hibernate 6 (Spring Boot 3.x) maps @Enumerated(EnumType.STRING) to MySQL ENUM by default.
-- V39 created the column as VARCHAR(20); this migration aligns the schema.
ALTER TABLE realtime_outbox_event
    MODIFY COLUMN status ENUM('pending','published') NOT NULL;
