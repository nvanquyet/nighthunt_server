-- ============================================================
-- V27 — Add party_mode to parties table
-- Distinguishes RANKED queue context from CUSTOM room context.
-- Enforces mutual exclusivity: a party cannot be in both.
--
-- partyMode values:
--   NONE    — party is IDLE, no active context
--   RANKED  — party is in matchmaking queue (IN_QUEUE)
--   CUSTOM  — party is in a custom lobby room (IN_ROOM / IN_GAME via custom)
-- ============================================================
ALTER TABLE parties
    ADD COLUMN party_mode VARCHAR(10) NOT NULL DEFAULT 'NONE'
        COMMENT 'NONE | RANKED | CUSTOM — context of current party activity';

-- Backfill existing rows based on partyStatus
UPDATE parties SET party_mode = 'RANKED' WHERE party_status = 'IN_QUEUE';
UPDATE parties SET party_mode = 'CUSTOM' WHERE party_status = 'IN_ROOM';
-- IN_GAME rows: keep NONE (can't safely determine origin without extra data)

-- Index for fast guard lookup: "is this user's party in RANKED/CUSTOM mode?"
CREATE INDEX idx_parties_party_mode ON parties(party_mode);
