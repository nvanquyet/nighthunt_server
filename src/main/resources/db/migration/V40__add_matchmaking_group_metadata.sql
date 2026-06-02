-- V40 - Queue-unit metadata for party fill/no-fill matchmaking.
-- Premade party members share queue_group_id so the matcher keeps them on the
-- same team. allow_fill=false means the premade team must not receive random
-- temporary teammates for that match.

ALTER TABLE matchmaking_queue
    ADD COLUMN queue_group_id VARCHAR(64) NULL
        COMMENT 'solo:<userId> or party:<partyId>; entries with same group stay together',
    ADD COLUMN party_id BIGINT NULL
        COMMENT 'Original premade party id; NULL for solo/fill players',
    ADD COLUMN party_size INT NOT NULL DEFAULT 1
        COMMENT 'Premade unit size at enqueue time',
    ADD COLUMN allow_fill TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '1 = may receive fill teammates; 0 = keep the unit alone on its team';

UPDATE matchmaking_queue
SET queue_group_id = CONCAT('solo:', user_id),
    party_size = 1,
    allow_fill = 1
WHERE queue_group_id IS NULL;

CREATE INDEX idx_mmq_queue_group ON matchmaking_queue(queue_group_id);
CREATE INDEX idx_mmq_party_id ON matchmaking_queue(party_id);
