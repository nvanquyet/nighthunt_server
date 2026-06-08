-- BCrypt hashes are about 60 characters. The original VARCHAR(50) column can
-- truncate/reject room password hashes, breaking locked custom lobby joins.
ALTER TABLE rooms
    MODIFY COLUMN password VARCHAR(255);
