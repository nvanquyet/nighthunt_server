-- ============================================================
-- NightHunt Server — Consolidated Database Init Script
-- ============================================================
-- Schema baseline: V37 (all Flyway migrations V1–V37 merged)
-- Engine: MySQL 8.0+  Charset: utf8mb4
--
-- USAGE — two modes:
--
--   A) Standalone (no Flyway):
--      Run this file once on a fresh MySQL schema.
--      mysql -u root -p nighthunt < scripts/init-db.sql
--
--   B) With Flyway (baseline an existing DB):
--      After running this file add to application.yml:
--        spring.flyway.baseline-on-migrate: true
--        spring.flyway.baseline-version: 37
--      Flyway will then only run migrations AFTER V37.
--
-- The file is idempotent:
--   • Tables use CREATE TABLE IF NOT EXISTS
--   • Seed data uses INSERT ... ON DUPLICATE KEY UPDATE
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;
SET UNIQUE_CHECKS     = 0;
SET SQL_MODE          = 'NO_AUTO_VALUE_ON_ZERO';

-- ============================================================
-- TABLE: ban_config
-- ============================================================
CREATE TABLE IF NOT EXISTS `ban_config` (
  `id`           bigint       NOT NULL AUTO_INCREMENT,
  `config_key`   varchar(100) NOT NULL,
  `config_value` varchar(500) NOT NULL,
  `description`  varchar(500)     DEFAULT NULL,
  `updated_at`   timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `config_key` (`config_key`),
  KEY `idx_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- TABLE: headless_server_config
-- ============================================================
CREATE TABLE IF NOT EXISTS `headless_server_config` (
  `id`           bigint       NOT NULL AUTO_INCREMENT,
  `config_key`   varchar(100) NOT NULL,
  `config_value` varchar(500) NOT NULL,
  `description`  varchar(500)     DEFAULT NULL,
  `created_at`   timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `config_key` (`config_key`),
  KEY `idx_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- TABLE: rate_limit_rules
-- ============================================================
CREATE TABLE IF NOT EXISTS `rate_limit_rules` (
  `id`               bigint       NOT NULL AUTO_INCREMENT,
  `endpoint_pattern` varchar(255) NOT NULL,
  `method`           varchar(10)      DEFAULT NULL,
  `limit_type`       varchar(20)  NOT NULL,
  `max_requests`     int          NOT NULL,
  `window_seconds`   int          NOT NULL,
  `refill_rate`      int              DEFAULT NULL,
  `bucket_size`      int              DEFAULT NULL,
  `scope`            varchar(20)  NOT NULL DEFAULT 'USER',
  `is_active`        tinyint(1)   NOT NULL DEFAULT '1',
  `description`      varchar(500)     DEFAULT NULL,
  `created_at`       timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `endpoint_pattern` (`endpoint_pattern`),
  KEY `idx_endpoint_pattern` (`endpoint_pattern`),
  KEY `idx_is_active` (`is_active`),
  KEY `idx_scope` (`scope`),
  KEY `idx_rate_limit_rule_active` (`endpoint_pattern`, `method`, `is_active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- TABLE: rate_limit_tracking
-- ============================================================
CREATE TABLE IF NOT EXISTS `rate_limit_tracking` (
  `id`              bigint       NOT NULL AUTO_INCREMENT,
  `rule_id`         bigint       NOT NULL,
  `identifier`      varchar(255) NOT NULL,
  `request_count`   int          NOT NULL DEFAULT '1',
  `window_start`    timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `window_end`      timestamp    NOT NULL,
  `last_request_at` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_rule_identifier` (`rule_id`, `identifier`),
  KEY `idx_window_end` (`window_end`),
  KEY `idx_identifier` (`identifier`),
  CONSTRAINT `rate_limit_tracking_ibfk_1`
    FOREIGN KEY (`rule_id`) REFERENCES `rate_limit_rules` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- TABLE: rate_limit_token_buckets
-- ============================================================
CREATE TABLE IF NOT EXISTS `rate_limit_token_buckets` (
  `id`            bigint       NOT NULL AUTO_INCREMENT,
  `rule_id`       bigint       NOT NULL,
  `identifier`    varchar(255) NOT NULL,
  `tokens`        int          NOT NULL,
  `last_refill_at` timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_identifier` (`rule_id`, `identifier`),
  KEY `idx_identifier` (`identifier`),
  CONSTRAINT `rate_limit_token_buckets_ibfk_1`
    FOREIGN KEY (`rule_id`) REFERENCES `rate_limit_rules` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- TABLE: bans
-- (user_id is nullable — IP/device bans may have no user)
-- ============================================================
CREATE TABLE IF NOT EXISTS `bans` (
  `id`                   bigint       NOT NULL AUTO_INCREMENT,
  `user_id`              bigint           DEFAULT NULL,
  `ban_type`             varchar(20)  NOT NULL,
  `ip_address`           varchar(45)      DEFAULT NULL,
  `device_fingerprint`   varchar(255)     DEFAULT NULL,
  `reason`               varchar(255) NOT NULL,
  `ban_duration_minutes` int          NOT NULL,
  `banned_at`            timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `expires_at`           timestamp        NULL DEFAULT NULL,
  `banned_by`            bigint           DEFAULT NULL,
  `is_active`            tinyint(1)   NOT NULL DEFAULT '1',
  `auto_unbanned`        tinyint(1)   NOT NULL DEFAULT '0',
  `created_at`           timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`           timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id`          (`user_id`),
  KEY `idx_ip_address`       (`ip_address`),
  KEY `idx_device_fingerprint` (`device_fingerprint`),
  KEY `idx_expires_at`       (`expires_at`),
  KEY `idx_is_active`        (`is_active`),
  KEY `idx_ban_type`         (`ban_type`),
  KEY `idx_bans_user_active` (`user_id`, `is_active`, `expires_at`),
  KEY `idx_bans_ip_active`   (`ip_address`, `is_active`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- TABLE: failed_login_attempts
-- ============================================================
CREATE TABLE IF NOT EXISTS `failed_login_attempts` (
  `id`                bigint       NOT NULL AUTO_INCREMENT,
  `identifier`        varchar(255) NOT NULL,
  `ip_address`        varchar(45)  NOT NULL,
  `device_fingerprint` varchar(255)    DEFAULT NULL,
  `attempt_count`     int          NOT NULL DEFAULT '1',
  `first_attempt_at`  timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_attempt_at`   timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `is_banned`         tinyint(1)   NOT NULL DEFAULT '0',
  `ban_id`            bigint           DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_identifier`              (`identifier`),
  KEY `idx_ip_address`              (`ip_address`),
  KEY `idx_device_fingerprint`      (`device_fingerprint`),
  KEY `idx_last_attempt_at`         (`last_attempt_at`),
  KEY `idx_is_banned`               (`is_banned`),
  KEY `ban_id`                      (`ban_id`),
  KEY `idx_failed_login_identifier_time` (`identifier`, `last_attempt_at`),
  CONSTRAINT `failed_login_attempts_ibfk_1`
    FOREIGN KEY (`ban_id`) REFERENCES `bans` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- TABLE: concurrent_login_attempts
-- ============================================================
CREATE TABLE IF NOT EXISTS `concurrent_login_attempts` (
  `id`                 bigint      NOT NULL AUTO_INCREMENT,
  `ip_address`         varchar(45) NOT NULL,
  `device_fingerprint` varchar(255)    DEFAULT NULL,
  `attempt_count`      int         NOT NULL DEFAULT '1',
  `window_start`       timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `window_end`         timestamp   NOT NULL,
  `is_banned`          tinyint(1)  NOT NULL DEFAULT '0',
  `ban_id`             bigint          DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ip_address`       (`ip_address`),
  KEY `idx_device_fingerprint` (`device_fingerprint`),
  KEY `idx_window_end`       (`window_end`),
  KEY `idx_is_banned`        (`is_banned`),
  KEY `ban_id`               (`ban_id`),
  CONSTRAINT `concurrent_login_attempts_ibfk_1`
    FOREIGN KEY (`ban_id`) REFERENCES `bans` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- TABLE: game_config
-- ============================================================
CREATE TABLE IF NOT EXISTS `game_config` (
  `config_key`   varchar(80)  COLLATE utf8mb4_unicode_ci NOT NULL
                   COMMENT 'Dot-notation key, e.g. matchmaking.elo.initialRange',
  `config_value` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `value_type`   varchar(20)  COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'STRING'
                   COMMENT 'STRING, INT, FLOAT, BOOL, JSON',
  `description`  varchar(200) COLLATE utf8mb4_unicode_ci     DEFAULT NULL,
  `updated_at`   timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Runtime-editable game configuration.';

-- ============================================================
-- TABLE: game_maps
-- ============================================================
CREATE TABLE IF NOT EXISTS `game_maps` (
  `id`                     bigint      NOT NULL AUTO_INCREMENT,
  `map_id`                 varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL
                             COMMENT 'Matches MapEntry.mapId on client: map_01, map_02 …',
  `display_name`           varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description`            varchar(200) COLLATE utf8mb4_unicode_ci     DEFAULT NULL,
  `scene_name`             varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL
                             COMMENT 'Unity scene file name without .unity',
  `supported_modes`        json            DEFAULT NULL
                             COMMENT 'JSON array of modeKey strings. NULL = all modes',
  `zone_config`            json            DEFAULT NULL
                             COMMENT 'SafeZoneMatchConfig JSON blob. NULL = use default',
  `supported_player_counts` json           DEFAULT NULL
                             COMMENT 'JSON int array of total-player counts. NULL = no filter',
  `is_locked`              tinyint(1)  NOT NULL DEFAULT '0'
                             COMMENT '1 = Coming Soon, cannot be queued into',
  `is_active`              tinyint(1)  NOT NULL DEFAULT '1'
                             COMMENT '0 = soft-deleted / hidden',
  `display_order`          int         NOT NULL DEFAULT '0',
  `created_at`             timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`             timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `map_id` (`map_id`),
  KEY `idx_maps_active` (`is_active`, `is_locked`),
  KEY `idx_maps_order` (`display_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Playable maps. Synced to client at startup via GET /api/maps';

-- ============================================================
-- TABLE: game_modes
-- ============================================================
CREATE TABLE IF NOT EXISTS `game_modes` (
  `id`                   bigint      NOT NULL AUTO_INCREMENT,
  `mode_key`             varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL
                           COMMENT 'Unique key: 2v2, 3v3, 4v4, 5v5, 1v1',
  `display_name`         varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description`          varchar(120) COLLATE utf8mb4_unicode_ci    DEFAULT NULL,
  `players_per_team`     int         NOT NULL,
  `total_players`        int         NOT NULL,
  `allow_fill`           tinyint(1)  NOT NULL DEFAULT '0',
  `matchmaking_enabled`  tinyint(1)  NOT NULL DEFAULT '1',
  `min_elo`              int         NOT NULL DEFAULT '0',
  `max_elo`              int         NOT NULL DEFAULT '9999',
  `mode_status`          varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'AVAILABLE'
                           COMMENT 'AVAILABLE, LOCKED, COMING_SOON, DISABLED',
  `display_order`        int         NOT NULL DEFAULT '0',
  `is_active`            tinyint(1)  NOT NULL DEFAULT '1',
  `is_dev_mode`          tinyint(1)  NOT NULL DEFAULT '0'
                           COMMENT 'Dev/test mode — excluded from public API',
  `platform_filter`      varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ALL',
  `created_at`           timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`           timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mode_key` (`mode_key`),
  KEY `idx_game_modes_status` (`mode_status`, `is_active`),
  KEY `idx_game_modes_order` (`display_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Configurable game modes.';

-- ============================================================
-- TABLE: role_permissions
-- ============================================================
CREATE TABLE IF NOT EXISTS `role_permissions` (
  `id`         bigint      NOT NULL AUTO_INCREMENT,
  `role`       varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL
                 COMMENT 'USER, SUPPORT, MODERATOR, ADMIN',
  `permission` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_role_permission` (`role`, `permission`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Role-to-permission mapping for fine-grained access control';

-- ============================================================
-- TABLE: dedicated_servers
-- ============================================================
CREATE TABLE IF NOT EXISTS `dedicated_servers` (
  `id`                  bigint      NOT NULL AUTO_INCREMENT,
  `server_id`           varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL
                          COMMENT 'UUID — matches SERVER_ID env inside container',
  `docker_container_id` varchar(64) COLLATE utf8mb4_unicode_ci     DEFAULT NULL,
  `ip`                  varchar(45) COLLATE utf8mb4_unicode_ci NOT NULL,
  `port`                int         NOT NULL COMMENT 'UDP port (7777–7900)',
  `status`              varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'starting'
                          COMMENT 'starting|ready|in_game|stopped',
  `region`              varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'vn',
  `current_players`     int         NOT NULL DEFAULT '0',
  `max_players`         int         NOT NULL DEFAULT '16',
  `image_tag`           varchar(100) COLLATE utf8mb4_unicode_ci     DEFAULT NULL,
  `server_secret_hash`  varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `last_heartbeat_at`   datetime        DEFAULT NULL,
  `started_at`          datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `stopped_at`          datetime        DEFAULT NULL,
  `map_id`              varchar(50) COLLATE utf8mb4_unicode_ci     DEFAULT NULL,
  `match_id`            varchar(36) COLLATE utf8mb4_unicode_ci     DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `server_id` (`server_id`),
  KEY `idx_ds_status`  (`status`),
  KEY `idx_ds_port`    (`port`),
  KEY `idx_ds_region`  (`region`, `status`),
  KEY `idx_ds_map`     (`map_id`, `status`, `region`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- TABLE: server_metrics_snapshots
-- ============================================================
CREATE TABLE IF NOT EXISTS `server_metrics_snapshots` (
  `id`                bigint    NOT NULL AUTO_INCREMENT,
  `snapshot_at`       datetime(3) NOT NULL,
  `online_users`      int       NOT NULL DEFAULT '0',
  `queue_depth`       int       NOT NULL DEFAULT '0',
  `active_rooms`      int       NOT NULL DEFAULT '0',
  `in_game_rooms`     int       NOT NULL DEFAULT '0',
  `waiting_rooms`     int       NOT NULL DEFAULT '0',
  `active_ds`         int       NOT NULL DEFAULT '0',
  `in_game_ds`        int       NOT NULL DEFAULT '0',
  `players_in_ds`     int       NOT NULL DEFAULT '0',
  `matches_last_hour` int       NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_snapshot_at` (`snapshot_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- TABLE: user_activity_logs
-- ============================================================
CREATE TABLE IF NOT EXISTS `user_activity_logs` (
  `id`         bigint      NOT NULL AUTO_INCREMENT,
  `user_id`    bigint          DEFAULT NULL,
  `username`   varchar(50) COLLATE utf8mb4_unicode_ci     DEFAULT NULL,
  `event_type` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `event_data` text        COLLATE utf8mb4_unicode_ci,
  `ip_address` varchar(50) COLLATE utf8mb4_unicode_ci     DEFAULT NULL,
  `created_at` timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ual_user_id`    (`user_id`),
  KEY `idx_ual_event_type` (`event_type`),
  KEY `idx_ual_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- Circular dependency group: rooms ↔ users ↔ parties
-- Created with FK_CHECKS=0 (already set above).
-- FKs referencing each other will be validated at end.
-- ============================================================

-- TABLE: rooms (owner_id → users, but users hasn't been created yet)
CREATE TABLE IF NOT EXISTS `rooms` (
  `id`         bigint      NOT NULL AUTO_INCREMENT,
  `room_code`  varchar(8)  NOT NULL,
  `mode`       varchar(20) NOT NULL,
  `map_id`     varchar(50) NOT NULL DEFAULT 'map_01',
  `status`     varchar(20) NOT NULL DEFAULT 'WAITING'
                 COMMENT 'WAITING, IN_GAME, CLOSED, FINISHED',
  `is_public`  tinyint(1)  NOT NULL DEFAULT '1',
  `is_locked`  tinyint(1)  NOT NULL DEFAULT '0',
  `password`   varchar(50)     DEFAULT NULL,
  `owner_id`   bigint      NOT NULL,
  `match_id`   varchar(50)     DEFAULT NULL,
  `created_at` timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `room_code` (`room_code`),
  KEY `idx_room_code` (`room_code`),
  KEY `idx_status`    (`status`),
  KEY `idx_owner_id`  (`owner_id`),
  CONSTRAINT `rooms_ibfk_1` FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- TABLE: parties (host_user_id → users, current_room_id → rooms)
CREATE TABLE IF NOT EXISTS `parties` (
  `id`                     bigint      NOT NULL AUTO_INCREMENT,
  `host_user_id`           bigint      NOT NULL,
  `party_status`           varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'IDLE'
                             COMMENT 'IDLE, IN_QUEUE, IN_ROOM, IN_GAME, DISBANDED',
  `current_room_id`        bigint          DEFAULT NULL,
  `current_matchmaking_id` bigint          DEFAULT NULL,
  `max_members`            int         NOT NULL DEFAULT '4',
  `party_mode`             varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NONE'
                             COMMENT 'NONE | RANKED | CUSTOM',
  `created_at`             timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`             timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_parties_current_room_id` (`current_room_id`),
  KEY `idx_parties_status`         (`party_status`),
  KEY `idx_parties_host_user_id`   (`host_user_id`),
  KEY `idx_parties_party_mode`     (`party_mode`),
  CONSTRAINT `fk_parties_current_room_id` FOREIGN KEY (`current_room_id`) REFERENCES `rooms`  (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_parties_host_user_id`    FOREIGN KEY (`host_user_id`)    REFERENCES `users`  (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Pre-match parties (squads).';

-- TABLE: users (current_party_id → parties, current_room_id → rooms)
CREATE TABLE IF NOT EXISTS `users` (
  `id`               bigint       NOT NULL AUTO_INCREMENT,
  `username`         varchar(50)  NOT NULL,
  `email`            varchar(100) NOT NULL,
  `online_status`    varchar(20)  NOT NULL DEFAULT 'OFFLINE'
                       COMMENT 'ONLINE, OFFLINE, AWAY, IN_GAME',
  `current_party_id` bigint           DEFAULT NULL,
  `current_room_id`  bigint           DEFAULT NULL,
  `last_seen_at`     timestamp        NULL DEFAULT NULL,
  `password_hash`    varchar(255) NOT NULL,
  `created_at`       timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `elo`              int          NOT NULL DEFAULT '1000',
  `tier`             varchar(20)  NOT NULL DEFAULT 'BRONZE'
                       COMMENT 'BRONZE/SILVER/GOLD/PLATINUM/DIAMOND/MASTER',
  `total_wins`       int          NOT NULL DEFAULT '0',
  `total_losses`     int          NOT NULL DEFAULT '0',
  `total_draws`      int          NOT NULL DEFAULT '0',
  `selected_character_id` varchar(64)  DEFAULT NULL,
  `role`             varchar(20)  NOT NULL DEFAULT 'USER'
                       COMMENT 'USER, SUPPORT, MODERATOR, ADMIN',
  `role_assigned_at`  timestamp       NULL DEFAULT NULL,
  `role_assigned_by`  bigint          DEFAULT NULL,
  `is_banned`        tinyint(1)   NOT NULL DEFAULT '0',
  `ban_reason`       varchar(500)     DEFAULT NULL,
  `coins`            bigint       NOT NULL DEFAULT '0',
  `platform`         varchar(20)      DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `username` (`username`),
  UNIQUE KEY `email`    (`email`),
  KEY `idx_username`             (`username`),
  KEY `idx_email`                (`email`),
  KEY `idx_users_online_status`  (`online_status`),
  KEY `idx_users_current_party_id` (`current_party_id`),
  KEY `idx_users_current_room_id`  (`current_room_id`),
  KEY `idx_users_last_seen_at`   (`last_seen_at`),
  KEY `idx_users_role`           (`role`),
  KEY `idx_users_is_banned`      (`is_banned`),
  KEY `idx_users_role_assigned_at` (`role_assigned_at`),
  CONSTRAINT `fk_users_current_party_id` FOREIGN KEY (`current_party_id`) REFERENCES `parties` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_users_current_room_id`  FOREIGN KEY (`current_room_id`)  REFERENCES `rooms`   (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- Tables depending on users
-- ============================================================

CREATE TABLE IF NOT EXISTS `sessions` (
  `id`              bigint       NOT NULL AUTO_INCREMENT,
  `user_id`         bigint       NOT NULL,
  `session_id`      varchar(100) NOT NULL,
  `access_token`    varchar(500)     DEFAULT NULL,
  `created_at`      timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `expires_at`      timestamp    NOT NULL,
  `last_activity_at` timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `session_id` (`session_id`),
  KEY `idx_user_id`   (`user_id`),
  KEY `idx_session_id` (`session_id`),
  KEY `idx_expires_at` (`expires_at`),
  CONSTRAINT `sessions_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `refresh_tokens` (
  `id`          bigint       NOT NULL AUTO_INCREMENT,
  `user_id`     bigint       NOT NULL,
  `token`       varchar(512) NOT NULL,
  `expiry_date` datetime     NOT NULL,
  `revoked`     tinyint(1)   NOT NULL DEFAULT '0',
  `created_at`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `token` (`token`),
  KEY `idx_rt_user_id` (`user_id`),
  KEY `idx_rt_token`   (`token`),
  KEY `idx_rt_expiry`  (`expiry_date`),
  CONSTRAINT `refresh_tokens_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Long-lived refresh tokens. One active token per user.';

CREATE TABLE IF NOT EXISTS `friends` (
  `id`                bigint      NOT NULL AUTO_INCREMENT,
  `user_id`           bigint      NOT NULL,
  `friend_user_id`    bigint      NOT NULL,
  `friendship_status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE'
                        COMMENT 'ACTIVE or BLOCKED',
  `created_at`        timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_friend` (`user_id`, `friend_user_id`),
  KEY `idx_friends_user_id`        (`user_id`),
  KEY `idx_friends_friend_user_id` (`friend_user_id`),
  KEY `idx_friends_status`         (`friendship_status`),
  CONSTRAINT `fk_friends_user_id`        FOREIGN KEY (`user_id`)        REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_friends_friend_user_id` FOREIGN KEY (`friend_user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Bidirectional friendships.';

CREATE TABLE IF NOT EXISTS `friend_requests` (
  `id`                  bigint      NOT NULL AUTO_INCREMENT,
  `requester_user_id`   bigint      NOT NULL,
  `addressee_user_id`   bigint      NOT NULL,
  `request_status`      varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING'
                          COMMENT 'PENDING, ACCEPTED, DECLINED, CANCELLED',
  `expires_at`          timestamp       NULL DEFAULT NULL,
  `created_at`          timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_friend_request` (`requester_user_id`, `addressee_user_id`),
  KEY `idx_friend_requests_addressee` (`addressee_user_id`, `request_status`),
  KEY `idx_friend_requests_requester` (`requester_user_id`),
  KEY `idx_friend_requests_expires`   (`expires_at`),
  CONSTRAINT `fk_friend_requests_requester` FOREIGN KEY (`requester_user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_friend_requests_addressee` FOREIGN KEY (`addressee_user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Friend requests.';

CREATE TABLE IF NOT EXISTS `blocked_users` (
  `id`              bigint  NOT NULL AUTO_INCREMENT,
  `blocker_user_id` bigint  NOT NULL,
  `blocked_user_id` bigint  NOT NULL,
  `created_at`      timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_blocker_blocked`       (`blocker_user_id`, `blocked_user_id`),
  KEY `idx_blocked_users_blocker` (`blocker_user_id`),
  KEY `idx_blocked_users_blocked` (`blocked_user_id`),
  CONSTRAINT `fk_blocked_users_blocker` FOREIGN KEY (`blocker_user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_blocked_users_blocked` FOREIGN KEY (`blocked_user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `party_members` (
  `id`         bigint    NOT NULL AUTO_INCREMENT,
  `party_id`   bigint    NOT NULL,
  `user_id`    bigint    NOT NULL,
  `join_order` int       NOT NULL COMMENT '0=host, 1,2,3...=guests',
  `joined_at`  timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_party_user` (`party_id`, `user_id`),
  KEY `idx_party_members_user_id`  (`user_id`),
  KEY `idx_party_members_party_id` (`party_id`),
  CONSTRAINT `fk_party_members_party_id` FOREIGN KEY (`party_id`) REFERENCES `parties` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_party_members_user_id`  FOREIGN KEY (`user_id`)  REFERENCES `users`   (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `party_invitations` (
  `id`                bigint      NOT NULL AUTO_INCREMENT,
  `party_id`          bigint      NOT NULL,
  `inviter_user_id`   bigint      NOT NULL,
  `invitee_user_id`   bigint      NOT NULL,
  `invitation_status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING'
                        COMMENT 'PENDING, ACCEPTED, DECLINED, EXPIRED, CANCELLED',
  `expires_at`        timestamp   NOT NULL COMMENT '30-second timeout',
  `created_at`        timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `fk_party_invitations_party_id` (`party_id`),
  KEY `fk_party_invitations_inviter`  (`inviter_user_id`),
  KEY `idx_party_invitations_invitee` (`invitee_user_id`, `invitation_status`),
  KEY `idx_party_invitations_expires` (`expires_at`),
  CONSTRAINT `fk_party_invitations_party_id` FOREIGN KEY (`party_id`)        REFERENCES `parties` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_party_invitations_inviter`  FOREIGN KEY (`inviter_user_id`) REFERENCES `users`   (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_party_invitations_invitee`  FOREIGN KEY (`invitee_user_id`) REFERENCES `users`   (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `room_players` (
  `id`           bigint    NOT NULL AUTO_INCREMENT,
  `room_id`      bigint    NOT NULL,
  `user_id`      bigint    NOT NULL,
  `team`         int       NOT NULL,
  `slot`         int       NOT NULL,
  `is_ready`     tinyint(1) NOT NULL DEFAULT '0',
  `joined_at`    timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_seen_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_room_user`      (`room_id`, `user_id`),
  UNIQUE KEY `uk_room_team_slot` (`room_id`, `team`, `slot`),
  KEY `idx_room_id`   (`room_id`),
  KEY `idx_user_id`   (`user_id`),
  KEY `idx_room_user` (`room_id`, `user_id`),
  CONSTRAINT `room_players_ibfk_1` FOREIGN KEY (`room_id`) REFERENCES `rooms` (`id`) ON DELETE CASCADE,
  CONSTRAINT `room_players_ibfk_2` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `swap_requests` (
  `id`              bigint      NOT NULL AUTO_INCREMENT,
  `room_id`         bigint      NOT NULL,
  `requester_id`    bigint      NOT NULL,
  `target_id`       bigint      NOT NULL,
  `requester_team`  int         NOT NULL,
  `requester_slot`  int         NOT NULL,
  `target_team`     int         NOT NULL,
  `target_slot`     int         NOT NULL,
  `status`          varchar(20) NOT NULL DEFAULT 'PENDING'
                      COMMENT 'PENDING, ACCEPTED, REJECTED, EXPIRED',
  `created_at`      timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `expires_at`      timestamp   NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_room_id`      (`room_id`),
  KEY `idx_requester_id` (`requester_id`),
  KEY `idx_target_id`    (`target_id`),
  KEY `idx_status`       (`status`),
  KEY `idx_expires_at`   (`expires_at`),
  CONSTRAINT `swap_requests_ibfk_1` FOREIGN KEY (`room_id`)      REFERENCES `rooms` (`id`) ON DELETE CASCADE,
  CONSTRAINT `swap_requests_ibfk_2` FOREIGN KEY (`requester_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `swap_requests_ibfk_3` FOREIGN KEY (`target_id`)    REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `matches` (
  `id`             bigint      NOT NULL AUTO_INCREMENT,
  `match_id`       varchar(50) NOT NULL,
  `room_id`        bigint      NOT NULL,
  `status`         varchar(20) NOT NULL DEFAULT 'LOBBY'
                     COMMENT 'LOBBY, IN_GAME, FINISHED',
  `winner_team_id` int             DEFAULT NULL COMMENT '-1 = DRAW',
  `end_reason`     varchar(30)     DEFAULT NULL
                     COMMENT 'TEAM_ELIMINATED | TIMER_EXPIRED | DRAW',
  `game_mode`      varchar(20)     DEFAULT NULL,
  `started_at`     timestamp       NULL DEFAULT NULL,
  `finished_at`    timestamp       NULL DEFAULT NULL,
  `created_at`     timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `match_id` (`match_id`),
  KEY `idx_match_id` (`match_id`),
  KEY `idx_room_id`  (`room_id`),
  KEY `idx_status`   (`status`),
  CONSTRAINT `matches_ibfk_1` FOREIGN KEY (`room_id`) REFERENCES `rooms` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `match_player_results` (
  `id`           bigint       NOT NULL AUTO_INCREMENT,
  `match_id`     varchar(50)  NOT NULL,
  `user_id`      bigint       NOT NULL,
  `team_id`      int          NOT NULL,
  `display_name` varchar(100) NOT NULL,
  `kills`        int          NOT NULL DEFAULT '0',
  `deaths`       int          NOT NULL DEFAULT '0',
  `score`        int          NOT NULL DEFAULT '0',
  `elo_before`   int          NOT NULL DEFAULT '1000',
  `elo_after`    int          NOT NULL DEFAULT '1000',
  `elo_change`   int          NOT NULL DEFAULT '0',
  `placement`    int          NOT NULL DEFAULT '0'
                   COMMENT '1=winner, 2=loser, 0=draw',
  `created_at`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mpr_match_id` (`match_id`),
  KEY `idx_mpr_user_id`  (`user_id`),
  CONSTRAINT `match_player_results_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `matchmaking_queue` (
  `id`             bigint      NOT NULL AUTO_INCREMENT,
  `user_id`        bigint      NOT NULL,
  `elo`            int         NOT NULL DEFAULT '1000',
  `game_mode`      varchar(20) NOT NULL,
  `queued_at`      datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `search_min_elo` int         NOT NULL DEFAULT '0',
  `search_max_elo` int         NOT NULL DEFAULT '9999',
  `status`         varchar(20) NOT NULL DEFAULT 'SEARCHING'
                     COMMENT 'SEARCHING | MATCHED | CANCELLED',
  `lobby_token`    varchar(64)     DEFAULT NULL,
  `accept_status`  varchar(20) NOT NULL DEFAULT 'PENDING'
                     COMMENT 'PENDING | ACCEPTED | DECLINED',
  `map_id`         varchar(50)     DEFAULT NULL,
  `platform`       varchar(20)     DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `user_id` (`user_id`),
  KEY `idx_mmq_user_id`      (`user_id`),
  KEY `idx_mmq_game_mode`    (`game_mode`),
  KEY `idx_mmq_status`       (`status`),
  KEY `idx_mmq_lobby_token`  (`lobby_token`),
  KEY `idx_mmq_accept_status` (`accept_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `user_abandon_records` (
  `id`          bigint      NOT NULL AUTO_INCREMENT,
  `user_id`     bigint      NOT NULL,
  `match_id`    varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `room_id`     bigint      NOT NULL,
  `reason`      varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL
                  COMMENT 'AFK_TIMEOUT | INTENTIONAL_LEAVE | LOGOUT | SESSION_EXPIRED | FORCE_LOGOUT',
  `elo_before`  int         NOT NULL DEFAULT '0',
  `elo_change`  int         NOT NULL DEFAULT '0',
  `recorded_at` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_abandon_user_id`     (`user_id`),
  KEY `idx_abandon_match_id`    (`match_id`),
  KEY `idx_abandon_recorded_at` (`recorded_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Mid-match AFK / abandon event log with ELO penalty history';

CREATE TABLE IF NOT EXISTS `admin_actions` (
  `id`               bigint      NOT NULL AUTO_INCREMENT,
  `admin_user_id`    bigint      NOT NULL,
  `admin_username`   varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `action_type`      varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_user_id`   bigint          DEFAULT NULL,
  `target_username`  varchar(50) COLLATE utf8mb4_unicode_ci     DEFAULT NULL,
  `details`          text        COLLATE utf8mb4_unicode_ci,
  `ip_address`       varchar(50) COLLATE utf8mb4_unicode_ci     DEFAULT NULL,
  `created_at`       timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_admin_actions_admin_user_id`  (`admin_user_id`),
  KEY `idx_admin_actions_target_user_id` (`target_user_id`),
  KEY `idx_admin_actions_action_type`    (`action_type`),
  KEY `idx_admin_actions_created_at`     (`created_at`),
  CONSTRAINT `admin_actions_ibfk_1` FOREIGN KEY (`admin_user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Audit trail for all admin actions on the dashboard';

SET FOREIGN_KEY_CHECKS = 1;
SET UNIQUE_CHECKS      = 1;

-- ============================================================
-- SEED DATA
-- All INSERT ... ON DUPLICATE KEY UPDATE for full idempotency
-- ============================================================

-- ── ban_config ───────────────────────────────────────────────
INSERT INTO `ban_config` (`config_key`, `config_value`, `description`) VALUES
('MAX_FAILED_LOGIN_ATTEMPTS',      '10000', 'Maximum failed login attempts before auto-ban'),
('FAILED_LOGIN_WINDOW_MINUTES',    '15',    'Time window for counting failed login attempts (minutes)'),
('FAILED_LOGIN_BAN_DURATION_MINUTES', '30', 'Auto-ban duration for failed login attempts (minutes)'),
('MAX_CONCURRENT_LOGIN_ATTEMPTS',  '10000', 'Maximum concurrent login attempts from same IP/device'),
('CONCURRENT_LOGIN_WINDOW_SECONDS','600',   'Time window for counting concurrent login attempts (seconds)'),
('CONCURRENT_LOGIN_BAN_DURATION_MINUTES', '15', 'Auto-ban duration for concurrent login attempts (minutes)'),
('AUTO_UNBAN_ENABLED',             'true',  'Enable automatic unban after ban duration expires'),
('AUTO_UNBAN_CHECK_INTERVAL_SECONDS', '60', 'Interval to check for expired bans (seconds)')
ON DUPLICATE KEY UPDATE config_value = VALUES(config_value);

-- ── headless_server_config ───────────────────────────────────
INSERT INTO `headless_server_config` (`config_key`, `config_value`, `description`) VALUES
('build.path',           '../Build/Server',   'Path to headless server build directory'),
('default.version',      'Ver0.0.1',          'Default headless server version'),
('log.path',             './logs/headless-servers', 'Path to headless server logs'),
('auto-scaling.enabled', 'true',              'Enable automatic server scaling'),
('scale-up.threshold',   '0.8',               'Scale up when server capacity reaches 80%'),
('scale-down.threshold', '0.2',               'Scale down when server capacity drops below 20%'),
('idle-timeout.minutes', '5',                 'Idle timeout in minutes before shutting down server'),
('max-servers',          '10',                'Maximum number of headless servers'),
('default-ip',           '127.0.0.1',         'Default server IP address'),
('base-port',            '7777',              'Base port for headless servers'),
('max-matches',          '10',                'Maximum matches per server'),
('server.version',       'Ver0.0.1',          'Server version')
ON DUPLICATE KEY UPDATE config_value = VALUES(config_value);

-- ── rate_limit_rules ─────────────────────────────────────────
INSERT INTO `rate_limit_rules` (`endpoint_pattern`, `method`, `limit_type`, `max_requests`, `window_seconds`, `scope`, `description`) VALUES
('/auth/login',         'POST', 'FIXED_WINDOW', 10000, 60,   'IP',   'Login: 10000 attempts/min per IP (stress-test headroom)'),
('/auth/register',      'POST', 'FIXED_WINDOW', 600,   60,   'IP',   'Registration: 600 attempts/min per IP'),
('/auth/auto-login',    'POST', 'FIXED_WINDOW', 120,   60,   'USER', 'Auto-login/session-check: 120 req/min per user'),
('/auth/change-password','POST','FIXED_WINDOW', 10,    300,  'USER', 'Password change: 10 attempts per 5 min per user'),
('/auth/refresh-token', 'POST', 'FIXED_WINDOW', 120,   60,   'USER', 'Token refresh: 120/min per user'),
('/rooms/create',       'POST', 'FIXED_WINDOW', 60,    60,   'USER', 'Room create: 60/min per user'),
('/rooms/join-by-code', 'POST', 'FIXED_WINDOW', 120,   60,   'USER', 'Join room by code: 120/min per user'),
('/rooms/quick-play',   'POST', 'FIXED_WINDOW', 120,   60,   'USER', 'Quick play: 120/min per user'),
('/rooms/*/ready',      'POST', 'FIXED_WINDOW', 300,   60,   'USER', 'Set ready: 300/min per user'),
('/rooms/*/change-team','POST', 'FIXED_WINDOW', 200,   60,   'USER', 'Change team: 200/min per user'),
('/rooms/*/start',      'POST', 'FIXED_WINDOW', 30,    60,   'USER', 'Start game: 30/min per user'),
('/friends',            'GET',  'FIXED_WINDOW', 10000, 60,   'USER', 'Get friend list: 10000/min per user'),
('/friends/requests',   'POST', 'FIXED_WINDOW', 60,    60,   'USER', 'Send friend request: 60/min per user'),
('/friends/requests/*', 'GET',  'FIXED_WINDOW', 120,   60,   'USER', 'Get friend requests: 120/min per user'),
('/friends/*',          '*',    'FIXED_WINDOW', 10000, 60,   'USER', 'Friend misc actions: 10000/min per user'),
('/party/current',      'GET',  'FIXED_WINDOW', 300,   60,   'USER', 'Get party state: 300/min per user'),
('/party/create',       'POST', 'FIXED_WINDOW', 60,    60,   'USER', 'Create party: 60/min per user'),
('/party/invite',       'POST', 'FIXED_WINDOW', 300,   60,   'USER', 'Party invite: 300/min per user'),
('/party/invitations/*','*',    'FIXED_WINDOW', 120,   60,   'USER', 'Party invitation response: 120/min per user'),
('/party/*',            '*',    'FIXED_WINDOW', 300,   60,   'USER', 'Party operations catch-all: 300/min per user'),
('/game-modes',         'GET',  'FIXED_WINDOW', 30,    60,   'IP',   'Game modes config: 30 req/min per IP'),
('/maps',               'GET',  'FIXED_WINDOW', 30,    60,   'IP',   'Maps config: 30 req/min per IP'),
('/profile',            'GET',  'FIXED_WINDOW', 10000, 60,   'USER', 'User profile: 10000/min per user'),
('/matchmaking/queue',  '*',    'FIXED_WINDOW', 20,    60,   'USER', 'Matchmaking queue (join + cancel): 20 req/min per user'),
('/matchmaking/*',      '*',    'FIXED_WINDOW', 120,   60,   'USER', 'Matchmaking status poll: 120 req/min per user'),
('/*',                  '*',    'FIXED_WINDOW', 50000, 60,   'USER', 'General API catch-all: 50000 req/min per user')
ON DUPLICATE KEY UPDATE
  max_requests   = VALUES(max_requests),
  window_seconds = VALUES(window_seconds),
  description    = VALUES(description);

-- ── role_permissions ─────────────────────────────────────────
INSERT IGNORE INTO `role_permissions` (`role`, `permission`) VALUES
('USER',      'PLAY_GAME'),
('USER',      'CREATE_PARTY'),
('USER',      'CREATE_ROOM'),
('USER',      'SEND_FRIEND_REQUEST'),
('USER',      'VIEW_PROFILE'),
('SUPPORT',   'PLAY_GAME'),
('SUPPORT',   'CREATE_PARTY'),
('SUPPORT',   'CREATE_ROOM'),
('SUPPORT',   'SEND_FRIEND_REQUEST'),
('SUPPORT',   'VIEW_PROFILE'),
('SUPPORT',   'VIEW_DASHBOARD'),
('SUPPORT',   'VIEW_USER_DETAILS'),
('SUPPORT',   'VIEW_ACTIVITY_LOGS'),
('SUPPORT',   'VIEW_BAN_LIST'),
('SUPPORT',   'VIEW_MATCH_HISTORY'),
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
('MODERATOR', 'VIEW_REPORTS'),
('ADMIN',     'PLAY_GAME'),
('ADMIN',     'CREATE_PARTY'),
('ADMIN',     'CREATE_ROOM'),
('ADMIN',     'SEND_FRIEND_REQUEST'),
('ADMIN',     'VIEW_PROFILE'),
('ADMIN',     'VIEW_DASHBOARD'),
('ADMIN',     'VIEW_USER_DETAILS'),
('ADMIN',     'VIEW_ACTIVITY_LOGS'),
('ADMIN',     'VIEW_BAN_LIST'),
('ADMIN',     'VIEW_MATCH_HISTORY'),
('ADMIN',     'BAN_USER'),
('ADMIN',     'UNBAN_USER'),
('ADMIN',     'KICK_USER_FROM_ROOM'),
('ADMIN',     'DELETE_OFFENSIVE_CONTENT'),
('ADMIN',     'VIEW_REPORTS'),
('ADMIN',     'MANAGE_ROLES'),
('ADMIN',     'DELETE_USER'),
('ADMIN',     'RESET_PASSWORD'),
('ADMIN',     'VIEW_SYSTEM_METRICS'),
('ADMIN',     'MANAGE_DEDICATED_SERVERS'),
('ADMIN',     'EDIT_ELO'),
('ADMIN',     'VIEW_REDIS_DATA'),
('ADMIN',     'FORCE_LOGOUT_USER');

-- ── game_modes ───────────────────────────────────────────────
INSERT INTO `game_modes` (`mode_key`, `display_name`, `description`, `players_per_team`, `total_players`, `allow_fill`, `matchmaking_enabled`, `min_elo`, `max_elo`, `mode_status`, `display_order`, `is_active`, `is_dev_mode`, `platform_filter`) VALUES
('2v2', '2 vs 2', 'Duo match — team of two',                2, 4,  1, 1, 0, 9999, 'AVAILABLE',   1, 1, 0, 'ALL'),
('3v3', '3 vs 3', 'Squad match — team of three',            3, 6,  1, 1, 0, 9999, 'AVAILABLE',   2, 1, 0, 'ALL'),
('4v4', '4 vs 4', 'Full squad — team of four',              4, 8,  0, 0, 0, 9999, 'COMING_SOON', 3, 1, 0, 'ALL'),
('5v5', '5 vs 5', 'Large battle — five per side',           5, 10, 1, 0, 0, 9999, 'AVAILABLE',   4, 1, 0, 'ALL'),
('1v1', '1 vs 1', '1v1 ranked test mode — 2 players, 1 per team.', 1, 2, 1, 1, 0, 9999, 'AVAILABLE', 0, 1, 1, 'ALL')
ON DUPLICATE KEY UPDATE
  display_name        = VALUES(display_name),
  description         = VALUES(description),
  players_per_team    = VALUES(players_per_team),
  total_players       = VALUES(total_players),
  allow_fill          = VALUES(allow_fill),
  matchmaking_enabled = VALUES(matchmaking_enabled),
  mode_status         = VALUES(mode_status),
  display_order       = VALUES(display_order),
  is_active           = VALUES(is_active),
  is_dev_mode         = VALUES(is_dev_mode),
  platform_filter     = VALUES(platform_filter);

-- ── game_maps ────────────────────────────────────────────────
INSERT INTO `game_maps` (`map_id`, `display_name`, `description`, `scene_name`, `supported_modes`, `zone_config`, `supported_player_counts`, `is_locked`, `is_active`, `display_order`) VALUES
(
  'map_01',
  'Industrial Zone',
  'Urban combat in a derelict factory.',
  '02_Map_01',
  '["2v2", "3v3", "4v4", "5v5", "1v1"]',
  '{"phases": [{"endRadius": 200.0, "zoneIndex": 0, "damageTick": 1.0, "startRadius": 400.0, "shrinkDuration": 180.0, "damagePerSecond": 0.0, "isScoreBonusZone": false, "waitBeforeShrink": 120.0, "minRadiusOverride": 0.0, "zoneBonusMultiplier": 1.5}, {"endRadius": 100.0, "zoneIndex": 1, "damageTick": 1.0, "startRadius": 200.0, "shrinkDuration": 120.0, "damagePerSecond": 3.0, "isScoreBonusZone": false, "waitBeforeShrink": 90.0, "minRadiusOverride": 0.0, "zoneBonusMultiplier": 1.5}, {"endRadius": 50.0, "zoneIndex": 2, "damageTick": 1.0, "startRadius": 100.0, "shrinkDuration": 90.0, "damagePerSecond": 8.0, "isScoreBonusZone": true, "waitBeforeShrink": 60.0, "minRadiusOverride": 0.0, "zoneBonusMultiplier": 2.0}, {"endRadius": 25.0, "zoneIndex": 3, "damageTick": 1.0, "startRadius": 50.0, "shrinkDuration": 60.0, "damagePerSecond": 15.0, "isScoreBonusZone": false, "waitBeforeShrink": 45.0, "minRadiusOverride": 0.0, "zoneBonusMultiplier": 1.5}, {"endRadius": 10.0, "zoneIndex": 4, "damageTick": 1.0, "startRadius": 25.0, "shrinkDuration": 30.0, "damagePerSecond": 25.0, "isScoreBonusZone": false, "waitBeforeShrink": 30.0, "minRadiusOverride": 10.0, "zoneBonusMultiplier": 1.5}], "killScore": 100.0, "centerMode": 0, "bossKillScore": 300.0, "initialRadius": 400.0, "finalZoneMinRadius": 10.0, "killScoreStealPercent": 0.15, "maxCenterShiftPercent": 0.6, "minCenterShiftPercent": 0.1, "baseSurvivalPtsPerSecond": 1.0, "beaconAllowedInFinalZone": false, "captureZoneScorePerSecond": 20.0}',
  '[4, 6]',
  0, 1, 1
),
(
  'map_02',
  'Arctic Base',
  'Close-quarters in a frozen research facility.',
  '02_Map_02',
  '["2v2", "3v3", "1v1"]',
  '{"phases": [{"endRadius": 50.0, "zoneIndex": 0, "damageTick": 1.0, "startRadius": 100.0, "shrinkDuration": 45.0, "damagePerSecond": 8.0, "isScoreBonusZone": true, "waitBeforeShrink": 45.0, "minRadiusOverride": 0.0, "zoneBonusMultiplier": 2.0}, {"endRadius": 25.0, "zoneIndex": 1, "damageTick": 1.0, "startRadius": 50.0, "shrinkDuration": 30.0, "damagePerSecond": 15.0, "isScoreBonusZone": false, "waitBeforeShrink": 30.0, "minRadiusOverride": 0.0, "zoneBonusMultiplier": 1.5}, {"endRadius": 10.0, "zoneIndex": 2, "damageTick": 1.0, "startRadius": 25.0, "shrinkDuration": 20.0, "damagePerSecond": 25.0, "isScoreBonusZone": false, "waitBeforeShrink": 20.0, "minRadiusOverride": 10.0, "zoneBonusMultiplier": 1.5}], "killScore": 100.0, "centerMode": 2, "bossKillScore": 300.0, "initialRadius": 100.0, "finalZoneMinRadius": 10.0, "killScoreStealPercent": 0.15, "maxCenterShiftPercent": 0.0, "minCenterShiftPercent": 0.0, "baseSurvivalPtsPerSecond": 1.0, "beaconAllowedInFinalZone": false, "captureZoneScorePerSecond": 20.0}',
  '[4]',
  0, 1, 2
)
ON DUPLICATE KEY UPDATE
  display_name            = VALUES(display_name),
  description             = VALUES(description),
  scene_name              = VALUES(scene_name),
  supported_modes         = VALUES(supported_modes),
  zone_config             = VALUES(zone_config),
  supported_player_counts = VALUES(supported_player_counts),
  is_locked               = VALUES(is_locked),
  is_active               = VALUES(is_active),
  display_order           = VALUES(display_order);

-- ── game_config ──────────────────────────────────────────────
INSERT INTO `game_config` (`config_key`, `config_value`, `value_type`, `description`) VALUES
('ds.defaultMaxPlayers',          '16',   'INT', 'Max players per dedicated server instance'),
('ds.portEnd',                    '7900', 'INT', 'Last UDP port in DS port range'),
('ds.portStart',                  '7777', 'INT', 'First UDP port in DS port range'),
('matchmaking.acceptTimeout.sec', '30',   'INT', 'Seconds for player to accept before auto-decline'),
('matchmaking.elo.expandIntervalSec', '15', 'INT', 'Seconds between ELO window expansions'),
('matchmaking.elo.expandStep',    '50',   'INT', 'ELO expand per side every expandInterval seconds'),
('matchmaking.elo.initialRange',  '100',  'INT', 'Initial ELO window half-width (±X) on queue entry'),
('matchmaking.elo.maxRange',      '500',  'INT', 'Maximum ELO window half-width'),
('matchmaking.tick.intervalMs',   '5000', 'INT', 'How often the matchmaking scheduler runs (ms)'),
('room.maxPlayersPerTeam',        '5',    'INT', 'Hard cap on players per team in any room'),
('room.minPlayersToStart',        '2',    'INT', 'Minimum total players required to start a match')
ON DUPLICATE KEY UPDATE
  config_value = VALUES(config_value),
  value_type   = VALUES(value_type),
  description  = VALUES(description);

-- ── default admin user ───────────────────────────────────────
-- Password: AdminPassword123  (BCrypt hash — CHANGE IN PRODUCTION)
INSERT INTO `users` (`username`, `email`, `password_hash`, `role`, `role_assigned_at`, `elo`, `tier`, `online_status`)
VALUES (
  'admin',
  'admin@nighthunt.local',
  '$2a$10$dXJ3SW6G7P2lh/Y9sB5k5.3OEiXlYhZzGnUMUNHrGpnVJmXc7P4H6',
  'ADMIN',
  NOW(),
  1000,
  'BRONZE',
  'OFFLINE'
) ON DUPLICATE KEY UPDATE role = 'ADMIN', role_assigned_at = NOW();

-- ============================================================
-- FLYWAY BASELINE (optional — uncomment if you want Flyway
-- to manage future migrations starting from V38+)
-- ============================================================
-- CREATE TABLE IF NOT EXISTS `flyway_schema_history` (
--   `installed_rank` int NOT NULL,
--   `version`        varchar(50)   DEFAULT NULL,
--   `description`    varchar(200)  NOT NULL,
--   `type`           varchar(20)   NOT NULL,
--   `script`         varchar(1000) NOT NULL,
--   `checksum`       int           DEFAULT NULL,
--   `installed_by`   varchar(100)  NOT NULL,
--   `installed_on`   timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
--   `execution_time` int NOT NULL,
--   `success`        tinyint(1) NOT NULL,
--   PRIMARY KEY (`installed_rank`),
--   KEY `flyway_schema_history_s_idx` (`success`)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
--
-- INSERT IGNORE INTO `flyway_schema_history`
--   (`installed_rank`,`version`,`description`,`type`,`script`,`checksum`,`installed_by`,`execution_time`,`success`)
-- VALUES
--   (1,'37','<< Flyway Baseline >>','BASELINE','<< Flyway Baseline >>',NULL,'root',0,1);
