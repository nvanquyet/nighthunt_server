-- =====================================================
-- Fix matches table - make headless_server_id nullable
-- =====================================================
-- Flyway Migration: V3
-- Description: Allow headless_server_id to be NULL in matches table
-- Reason: Match can be created before headless server is allocated
-- =====================================================

-- Find and drop existing foreign key constraint dynamically
SET @constraint_name = (
    SELECT CONSTRAINT_NAME 
    FROM information_schema.KEY_COLUMN_USAGE 
    WHERE TABLE_SCHEMA = DATABASE() 
    AND TABLE_NAME = 'matches' 
    AND COLUMN_NAME = 'headless_server_id' 
    AND REFERENCED_TABLE_NAME = 'headless_servers'
    LIMIT 1
);

-- Drop foreign key if exists
SET @drop_fk_sql = IF(@constraint_name IS NOT NULL, 
    CONCAT('ALTER TABLE matches DROP FOREIGN KEY ', @constraint_name), 
    'SELECT 1');
PREPARE stmt FROM @drop_fk_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Alter matches table to allow NULL headless_server_id
ALTER TABLE matches 
MODIFY COLUMN headless_server_id BIGINT NULL;

-- Recreate foreign key constraint with nullable support
ALTER TABLE matches 
ADD CONSTRAINT fk_matches_headless_server 
FOREIGN KEY (headless_server_id) REFERENCES headless_servers(id) ON DELETE CASCADE;

