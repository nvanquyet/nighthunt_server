-- Hibernate stores RealtimeOutboxStatus with EnumType.STRING, which writes the
-- Java enum names: PENDING and PUBLISHED. Keep the MySQL enum values aligned.

ALTER TABLE realtime_outbox_event
    MODIFY COLUMN status VARCHAR(20) NOT NULL;

UPDATE realtime_outbox_event
SET status = UPPER(status)
WHERE status IS NOT NULL;

ALTER TABLE realtime_outbox_event
    MODIFY COLUMN status ENUM('PENDING','PUBLISHED') NOT NULL;
