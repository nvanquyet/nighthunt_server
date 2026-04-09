-- V15: Add Role-Based Access Control (RBAC) System
-- Author: GitHub Copilot
-- Date: 2026-03-15
-- Description: Add role and permissions columns to users table for admin dashboard access control

-- ═══════════════════════════════════════════════════════════════════════════
-- 1. Add role column to users table
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE users 
ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER' 
COMMENT 'USER, SUPPORT, MODERATOR, ADMIN';

-- ═══════════════════════════════════════════════════════════════════════════
-- 2. Add role-related columns for auditing
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE users 
ADD COLUMN role_assigned_at TIMESTAMP NULL 
COMMENT 'When the current role was assigned';

ALTER TABLE users 
ADD COLUMN role_assigned_by BIGINT NULL 
COMMENT 'User ID of admin who assigned the role';

ALTER TABLE users 
ADD COLUMN is_banned BOOLEAN NOT NULL DEFAULT FALSE 
COMMENT 'Quick flag for dashboard filtering';

ALTER TABLE users 
ADD COLUMN ban_reason VARCHAR(500) NULL 
COMMENT 'Reason for ban (if is_banned=true)';

-- ═══════════════════════════════════════════════════════════════════════════
-- 3. Create index for role queries (dashboard filtering)
-- ═══════════════════════════════════════════════════════════════════════════

CREATE INDEX idx_users_role ON users(role);
CREATE INDEX idx_users_is_banned ON users(is_banned);
CREATE INDEX idx_users_role_assigned_at ON users(role_assigned_at);

-- ═══════════════════════════════════════════════════════════════════════════
-- 4. Create first admin user (optional - for initial setup)
-- ═══════════════════════════════════════════════════════════════════════════

-- IMPORTANT: Change password in production!
-- Password: "AdminPassword123" (bcrypt hash)
INSERT INTO users (username, email, password_hash, role, role_assigned_at, elo, tier, online_status) 
VALUES (
    'admin',
    'admin@nighthunt.local',
    '$2a$10$dXJ3SW6G7P2lh/Y9sB5k5.3OEiXlYhZzGnUMUNHrGpnVJmXc7P4H6',
    'ADMIN',
    NOW(),
    1000,
    'BRONZE',
    'OFFLINE'
) ON DUPLICATE KEY UPDATE role = 'ADMIN';

-- ═══════════════════════════════════════════════════════════════════════════
-- 5. Create role_permissions table for fine-grained access control
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE role_permissions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    role VARCHAR(20) NOT NULL COMMENT 'USER, SUPPORT, MODERATOR, ADMIN',
    permission VARCHAR(100) NOT NULL COMMENT 'e.g., VIEW_DASHBOARD, BAN_USER, MANAGE_ROOMS',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE INDEX idx_role_permission (role, permission)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Role-to-permission mapping for fine-grained access control';

-- ═══════════════════════════════════════════════════════════════════════════
-- 6. Insert default permissions for each role
-- ═══════════════════════════════════════════════════════════════════════════

-- USER (default role) - basic permissions
INSERT INTO role_permissions (role, permission) VALUES
('USER', 'PLAY_GAME'),
('USER', 'CREATE_PARTY'),
('USER', 'CREATE_ROOM'),
('USER', 'SEND_FRIEND_REQUEST'),
('USER', 'VIEW_PROFILE');

-- SUPPORT - user management assistance
INSERT INTO role_permissions (role, permission) VALUES
('SUPPORT', 'PLAY_GAME'),
('SUPPORT', 'CREATE_PARTY'),
('SUPPORT', 'CREATE_ROOM'),
('SUPPORT', 'SEND_FRIEND_REQUEST'),
('SUPPORT', 'VIEW_PROFILE'),
('SUPPORT', 'VIEW_DASHBOARD'),
('SUPPORT', 'VIEW_USER_DETAILS'),
('SUPPORT', 'VIEW_ACTIVITY_LOGS'),
('SUPPORT', 'VIEW_BAN_LIST'),
('SUPPORT', 'VIEW_MATCH_HISTORY');

-- MODERATOR - moderation powers
INSERT INTO role_permissions (role, permission) VALUES
('MODERATOR', 'PLAY_GAME'),
('MODERATOR', 'CREATE_PARTY'),
('MODERATOR', 'CREATE_ROOM'),
('MODERATOR', 'SEND_FRIEND_REQUEST'),
('MODERATOR', 'VIEW_PROFILE'),
('MODERATOR', 'VIEW_DASHBOARD'),
('MODERATOR', 'VIEW_USER_DETAILS'),
('MODERATOR', 'VIEW_ACTIVITY_LOGS'),
('MODERATOR', 'VIEW_BAN_LIST'),
('MODERATOR', 'VIEW_MATCH_HISTORY'),
('MODERATOR', 'BAN_USER'),
('MODERATOR', 'UNBAN_USER'),
('MODERATOR', 'KICK_USER_FROM_ROOM'),
('MODERATOR', 'DELETE_OFFENSIVE_CONTENT'),
('MODERATOR', 'VIEW_REPORTS');

-- ADMIN - full control
INSERT INTO role_permissions (role, permission) VALUES
('ADMIN', 'PLAY_GAME'),
('ADMIN', 'CREATE_PARTY'),
('ADMIN', 'CREATE_ROOM'),
('ADMIN', 'SEND_FRIEND_REQUEST'),
('ADMIN', 'VIEW_PROFILE'),
('ADMIN', 'VIEW_DASHBOARD'),
('ADMIN', 'VIEW_USER_DETAILS'),
('ADMIN', 'VIEW_ACTIVITY_LOGS'),
('ADMIN', 'VIEW_BAN_LIST'),
('ADMIN', 'VIEW_MATCH_HISTORY'),
('ADMIN', 'BAN_USER'),
('ADMIN', 'UNBAN_USER'),
('ADMIN', 'KICK_USER_FROM_ROOM'),
('ADMIN', 'DELETE_OFFENSIVE_CONTENT'),
('ADMIN', 'VIEW_REPORTS'),
('ADMIN', 'MANAGE_ROLES'),
('ADMIN', 'DELETE_USER'),
('ADMIN', 'RESET_PASSWORD'),
('ADMIN', 'VIEW_SYSTEM_METRICS'),
('ADMIN', 'MANAGE_DEDICATED_SERVERS'),
('ADMIN', 'EDIT_ELO'),
('ADMIN', 'VIEW_REDIS_DATA'),
('ADMIN', 'FORCE_LOGOUT_USER');

-- ═══════════════════════════════════════════════════════════════════════════
-- 7. Create admin_actions table for audit trail
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE admin_actions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    admin_user_id BIGINT NOT NULL COMMENT 'Admin who performed the action',
    admin_username VARCHAR(50) NOT NULL,
    action_type VARCHAR(50) NOT NULL COMMENT 'BAN_USER, UNBAN_USER, CHANGE_ROLE, etc.',
    target_user_id BIGINT NULL COMMENT 'User who was affected (if applicable)',
    target_username VARCHAR(50) NULL,
    details TEXT NULL COMMENT 'JSON details of the action',
    ip_address VARCHAR(50) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_admin_actions_admin_user_id (admin_user_id),
    INDEX idx_admin_actions_target_user_id (target_user_id),
    INDEX idx_admin_actions_action_type (action_type),
    INDEX idx_admin_actions_created_at (created_at),
    FOREIGN KEY (admin_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Audit trail for all admin actions on the dashboard';

-- ═══════════════════════════════════════════════════════════════════════════
-- 8. Update existing admin user if exists (from docker-compose.yml)
-- ═══════════════════════════════════════════════════════════════════════════

UPDATE users 
SET 
    role = 'ADMIN',
    role_assigned_at = NOW()
WHERE username = 'admin' OR email LIKE '%admin%';

-- ═══════════════════════════════════════════════════════════════════════════
-- VERIFICATION QUERIES (Run after migration)
-- ═══════════════════════════════════════════════════════════════════════════

-- Check role distribution
-- SELECT role, COUNT(*) as user_count FROM users GROUP BY role;

-- Check admin users
-- SELECT id, username, email, role, role_assigned_at FROM users WHERE role IN ('ADMIN', 'MODERATOR', 'SUPPORT');

-- Check permissions for a role
-- SELECT permission FROM role_permissions WHERE role = 'MODERATOR' ORDER BY permission;
