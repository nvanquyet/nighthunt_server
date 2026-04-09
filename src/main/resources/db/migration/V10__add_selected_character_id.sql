-- V10: Add selected_character_id to users table
-- Used to persist the player's chosen character model (string ID, e.g. "character_02")
-- Client: ClientNetworkHandler reads PlayerPrefs "SelectedCharacterId" → resolves to array index

ALTER TABLE users
    ADD COLUMN selected_character_id VARCHAR(64) NULL
        COMMENT 'Backend string ID of the player selected character model (e.g. character_01)';
