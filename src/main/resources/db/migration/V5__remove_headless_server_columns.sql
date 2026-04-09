-- V5: Remove headless server columns and tables
-- The headless server functionality has been removed from the application.
-- This migration drops the now-unused columns, foreign keys, indexes, and tables.

-- Step 1: Drop foreign key and column from matches table
-- Look up the FK constraint name dynamically (may vary across environments)
SET @fk_name_matches = (
    SELECT kcu.CONSTRAINT_NAME
    FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
    JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
        ON kcu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
        AND kcu.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
        AND kcu.TABLE_NAME = tc.TABLE_NAME
    WHERE kcu.TABLE_SCHEMA = DATABASE()
    AND kcu.TABLE_NAME = 'matches'
    AND kcu.COLUMN_NAME = 'headless_server_id'
    AND tc.CONSTRAINT_TYPE = 'FOREIGN KEY'
    LIMIT 1
);

SET @sql = IF(@fk_name_matches IS NOT NULL,
    CONCAT('ALTER TABLE matches DROP FOREIGN KEY `', @fk_name_matches, '`'),
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Drop index if exists
SET @idx_exists = (SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'matches'
    AND INDEX_NAME = 'idx_headless_server_id');

SET @sql = IF(@idx_exists > 0,
    'ALTER TABLE matches DROP INDEX idx_headless_server_id',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Drop column if exists
SET @col_exists = (SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'matches'
    AND COLUMN_NAME = 'headless_server_id');

SET @sql = IF(@col_exists > 0,
    'ALTER TABLE matches DROP COLUMN headless_server_id',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Step 2: Drop foreign key and column from rooms table
-- Look up the FK constraint name dynamically
SET @fk_name_rooms = (
    SELECT kcu.CONSTRAINT_NAME
    FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
    JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
        ON kcu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
        AND kcu.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
        AND kcu.TABLE_NAME = tc.TABLE_NAME
    WHERE kcu.TABLE_SCHEMA = DATABASE()
    AND kcu.TABLE_NAME = 'rooms'
    AND kcu.COLUMN_NAME = 'headless_server_id'
    AND tc.CONSTRAINT_TYPE = 'FOREIGN KEY'
    LIMIT 1
);

SET @sql = IF(@fk_name_rooms IS NOT NULL,
    CONCAT('ALTER TABLE rooms DROP FOREIGN KEY `', @fk_name_rooms, '`'),
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'rooms'
    AND INDEX_NAME = 'idx_headless_server_id');

SET @sql = IF(@idx_exists > 0,
    'ALTER TABLE rooms DROP INDEX idx_headless_server_id',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'rooms'
    AND COLUMN_NAME = 'headless_server_id');

SET @sql = IF(@col_exists > 0,
    'ALTER TABLE rooms DROP COLUMN headless_server_id',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Step 3: Drop headless_servers table if exists
DROP TABLE IF EXISTS headless_servers;

-- Step 4: Drop headless_server_configs table if exists
DROP TABLE IF EXISTS headless_server_configs;
