-- V23: Add match_id to dedicated_servers so backend can broadcast ds_ready to correct players
ALTER TABLE dedicated_servers ADD COLUMN match_id VARCHAR(36) NULL;
