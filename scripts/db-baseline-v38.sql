-- =============================================================================
-- NightHunt Database – Complete Baseline Schema (equivalent to V1 → V38)
-- =============================================================================
-- Usage (fresh install on any machine):
--   mysql -u root -p nighthunt_db < scripts/db-baseline-v38.sql
--
-- This file represents the net result of all 38 Flyway migrations.
-- For incremental upgrades on an existing DB use Flyway (V1–V38 remain intact).
-- For a brand-new empty DB you can use EITHER this file OR let Flyway run.
--   They produce the same schema/seed state.
--
-- IMPORTANT: Change the admin password in the users INSERT before production!
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;
SET SQL_MODE = 'NO_AUTO_VALUE_ON_ZERO';

-- =============================================================================
-- 1. BAN SYSTEM
-- =============================================================================

CREATE TABLE IF NOT EXISTS bans (
    id                   BIGINT       PRIMARY KEY AUTO_INCREMENT,
    user_id              BIGINT       NULL,                          -- nullable (V6)
    ban_type             VARCHAR(20)  NOT NULL,                     -- USER | IP | DEVICE
    ip_address           VARCHAR(45)  NULL,
    device_fingerprint   VARCHAR(255) NULL,
    reason               VARCHAR(255) NOT NULL,
    ban_duration_minutes INT          NOT NULL,                     -- 0 = permanent
    banned_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at           TIMESTAMP    NULL,
    banned_by            BIGINT       NULL,
    is_active            BOOLEAN      NOT NULL DEFAULT TRUE,
    auto_unbanned        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_user_id           (user_id),
    INDEX idx_ip_address        (ip_address),
    INDEX idx_device_fingerprint(device_fingerprint),
    INDEX idx_expires_at        (expires_at),
    INDEX idx_is_active         (is_active),
    INDEX idx_ban_type          (ban_type),
    INDEX idx_bans_user_active  (user_id, is_active, expires_at),
    INDEX idx_bans_ip_active    (ip_address, is_active, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS failed_login_attempts (
    id                   BIGINT       PRIMARY KEY AUTO_INCREMENT,
    identifier           VARCHAR(255) NOT NULL,
    ip_address           VARCHAR(45)  NOT NULL,
    device_fingerprint   VARCHAR(255) NULL,
    attempt_count        INT          NOT NULL DEFAULT 1,
    first_attempt_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_attempt_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_banned            BOOLEAN      NOT NULL DEFAULT FALSE,
    ban_id               BIGINT       NULL,

    INDEX idx_identifier                  (identifier),
    INDEX idx_ip_address                  (ip_address),
    INDEX idx_device_fingerprint          (device_fingerprint),
    INDEX idx_last_attempt_at             (last_attempt_at),
    INDEX idx_is_banned                   (is_banned),
    INDEX idx_failed_login_identifier_time(identifier, last_attempt_at),
    INDEX idx_failed_login_identifier_ip_time(identifier, ip_address, last_attempt_at),  -- V38
    FOREIGN KEY (ban_id) REFERENCES bans(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS concurrent_login_attempts (
    id                 BIGINT      PRIMARY KEY AUTO_INCREMENT,
    ip_address         VARCHAR(45) NOT NULL,
    device_fingerprint VARCHAR(255) NULL,
    attempt_count      INT         NOT NULL DEFAULT 1,
    window_start       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    window_end         TIMESTAMP   NOT NULL,
    is_banned          BOOLEAN     NOT NULL DEFAULT FALSE,
    ban_id             BIGINT      NULL,

    INDEX idx_ip_address        (ip_address),
    INDEX idx_device_fingerprint(device_fingerprint),
    INDEX idx_window_end        (window_end),
    INDEX idx_is_banned         (is_banned),
    INDEX idx_concurrent_login_ip_window(ip_address, window_end),  -- V38
    FOREIGN KEY (ban_id) REFERENCES bans(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ban_config (
    id           BIGINT       PRIMARY KEY AUTO_INCREMENT,
    config_key   VARCHAR(100) NOT NULL UNIQUE,
    config_value VARCHAR(500) NOT NULL,
    description  VARCHAR(500) NULL,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =============================================================================
-- 2. RATE LIMITING
-- =============================================================================

CREATE TABLE IF NOT EXISTS rate_limit_rules (
    id               BIGINT       PRIMARY KEY AUTO_INCREMENT,
    endpoint_pattern VARCHAR(255) NOT NULL UNIQUE,
    method           VARCHAR(10)  NULL,
    limit_type       VARCHAR(20)  NOT NULL,
    max_requests     INT          NOT NULL,
    window_seconds   INT          NOT NULL,
    refill_rate      INT          NULL,
    bucket_size      INT          NULL,
    scope            VARCHAR(20)  NOT NULL DEFAULT 'USER',
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    description      VARCHAR(500) NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_endpoint_pattern  (endpoint_pattern),
    INDEX idx_is_active         (is_active),
    INDEX idx_scope             (scope),
    INDEX idx_rate_limit_rule_active(endpoint_pattern, method, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rate_limit_tracking (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
    rule_id         BIGINT       NOT NULL,
    identifier      VARCHAR(255) NOT NULL,
    request_count   INT          NOT NULL DEFAULT 1,
    window_start    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    window_end      TIMESTAMP    NOT NULL,
    last_request_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_rule_identifier(rule_id, identifier),
    INDEX idx_window_end     (window_end),
    INDEX idx_identifier     (identifier),
    FOREIGN KEY (rule_id) REFERENCES rate_limit_rules(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rate_limit_token_buckets (
    id             BIGINT       PRIMARY KEY AUTO_INCREMENT,
    rule_id        BIGINT       NOT NULL,
    identifier     VARCHAR(255) NOT NULL,
    tokens         INT          NOT NULL,
    last_refill_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_rule_identifier(rule_id, identifier),
    INDEX idx_identifier(identifier),
    FOREIGN KEY (rule_id) REFERENCES rate_limit_rules(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =============================================================================
-- 3. USERS (created before parties/rooms for FK purposes)
-- =============================================================================

CREATE TABLE IF NOT EXISTS users (
    id                   BIGINT       PRIMARY KEY AUTO_INCREMENT,
    username             VARCHAR(50)  NOT NULL UNIQUE,
    email                VARCHAR(100) NOT NULL UNIQUE,
    password_hash        VARCHAR(255) NOT NULL,
    -- V13: social
    online_status        VARCHAR(20)  NOT NULL DEFAULT 'OFFLINE'   COMMENT 'ONLINE|OFFLINE|AWAY|IN_GAME',
    current_party_id     BIGINT       NULL,
    current_room_id      BIGINT       NULL,
    last_seen_at         TIMESTAMP    NULL,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- V8: ELO
    elo                  INT          NOT NULL DEFAULT 1000,
    tier                 VARCHAR(20)  NOT NULL DEFAULT 'BRONZE',
    total_wins           INT          NOT NULL DEFAULT 0,
    total_losses         INT          NOT NULL DEFAULT 0,
    total_draws          INT          NOT NULL DEFAULT 0,
    -- V10
    selected_character_id VARCHAR(64) NULL,
    -- V15: RBAC
    role                 VARCHAR(20)  NOT NULL DEFAULT 'USER'      COMMENT 'USER|SUPPORT|MODERATOR|ADMIN',
    role_assigned_at     TIMESTAMP    NULL,
    role_assigned_by     BIGINT       NULL,
    is_banned            BOOLEAN      NOT NULL DEFAULT FALSE,
    ban_reason           VARCHAR(500) NULL,
    -- V24
    coins                BIGINT       NOT NULL DEFAULT 0,
    platform             VARCHAR(20)  NULL                          COMMENT 'MOBILE|PC|NULL=unknown',

    INDEX idx_username                (username),
    INDEX idx_email                   (email),
    INDEX idx_users_online_status     (online_status),
    INDEX idx_users_current_party_id  (current_party_id),
    INDEX idx_users_current_room_id   (current_room_id),
    INDEX idx_users_last_seen_at      (last_seen_at),
    INDEX idx_users_role              (role),
    INDEX idx_users_is_banned         (is_banned),
    INDEX idx_users_role_assigned_at  (role_assigned_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =============================================================================
-- 4. SESSIONS & REFRESH TOKENS
-- =============================================================================

CREATE TABLE IF NOT EXISTS sessions (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    session_id      VARCHAR(100) NOT NULL UNIQUE,
    access_token    VARCHAR(500) NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP    NOT NULL,
    last_activity_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_user_id   (user_id),
    INDEX idx_session_id(session_id),
    INDEX idx_expires_at(expires_at),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    token       VARCHAR(512) NOT NULL UNIQUE,
    expiry_date DATETIME     NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_rt_user_id(user_id),
    INDEX idx_rt_token  (token),
    INDEX idx_rt_expiry (expiry_date),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =============================================================================
-- 5. ROOMS (headless_server_id removed in V5)
-- =============================================================================

CREATE TABLE IF NOT EXISTS rooms (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    room_code   VARCHAR(8)   NOT NULL UNIQUE,
    mode        VARCHAR(20)  NOT NULL,
    map_id      VARCHAR(50)  NOT NULL DEFAULT 'map_01',  -- V16
    status      VARCHAR(20)  NOT NULL DEFAULT 'WAITING',
    is_public   BOOLEAN      NOT NULL DEFAULT TRUE,
    is_locked   BOOLEAN      NOT NULL DEFAULT FALSE,
    password    VARCHAR(50)  NULL,
    owner_id    BIGINT       NOT NULL,
    match_id    VARCHAR(50)  NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_room_code(room_code),
    INDEX idx_status   (status),
    INDEX idx_owner_id (owner_id),
    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS room_players (
    id           BIGINT    PRIMARY KEY AUTO_INCREMENT,
    room_id      BIGINT    NOT NULL,
    user_id      BIGINT    NOT NULL,
    team         INT       NOT NULL,
    slot         INT       NOT NULL,
    is_ready     BOOLEAN   NOT NULL DEFAULT FALSE,
    joined_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_room_id       (room_id),
    INDEX idx_user_id       (user_id),
    INDEX idx_room_user     (room_id, user_id),
    UNIQUE KEY uk_room_user (room_id, user_id),
    UNIQUE KEY uk_room_team_slot(room_id, team, slot),  -- V4
    FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS swap_requests (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
    room_id         BIGINT       NOT NULL,
    requester_id    BIGINT       NOT NULL,
    target_id       BIGINT       NOT NULL,
    requester_team  INT          NOT NULL,
    requester_slot  INT          NOT NULL,
    target_team     INT          NOT NULL,
    target_slot     INT          NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP    NOT NULL,

    INDEX idx_room_id     (room_id),
    INDEX idx_requester_id(requester_id),
    INDEX idx_target_id   (target_id),
    INDEX idx_status      (status),
    INDEX idx_expires_at  (expires_at),
    FOREIGN KEY (room_id)      REFERENCES rooms(id) ON DELETE CASCADE,
    FOREIGN KEY (requester_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (target_id)    REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =============================================================================
-- 6. MATCHES (headless_server_id removed in V5, ELO fields added V8)
-- =============================================================================

CREATE TABLE IF NOT EXISTS matches (
    id             BIGINT       PRIMARY KEY AUTO_INCREMENT,
    match_id       VARCHAR(50)  NOT NULL UNIQUE,
    room_id        BIGINT       NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'LOBBY',
    winner_team_id INT          NULL     COMMENT '-1=DRAW',          -- V8
    end_reason     VARCHAR(30)  NULL     COMMENT 'TEAM_ELIMINATED|TIMER_EXPIRED|DRAW', -- V8
    game_mode      VARCHAR(20)  NULL     COMMENT '2v2|3v3|5v5',      -- V8
    started_at     TIMESTAMP    NULL,
    finished_at    TIMESTAMP    NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_match_id(match_id),
    INDEX idx_room_id (room_id),
    INDEX idx_status  (status),
    FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS match_player_results (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    match_id     VARCHAR(50)   NOT NULL,
    user_id      BIGINT        NOT NULL,
    team_id      INT           NOT NULL,
    display_name VARCHAR(100)  NOT NULL,
    kills        INT           NOT NULL DEFAULT 0,
    deaths       INT           NOT NULL DEFAULT 0,
    score        INT           NOT NULL DEFAULT 0,
    elo_before   INT           NOT NULL DEFAULT 1000,
    elo_after    INT           NOT NULL DEFAULT 1000,
    elo_change   INT           NOT NULL DEFAULT 0,
    placement    INT           NOT NULL DEFAULT 0   COMMENT '1=winner,2=loser,0=draw',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_mpr_match_id(match_id),
    INDEX idx_mpr_user_id (user_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_abandon_records (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    match_id    VARCHAR(64)  NOT NULL,
    room_id     BIGINT       NOT NULL,
    reason      VARCHAR(50)  NOT NULL COMMENT 'AFK_TIMEOUT|INTENTIONAL_LEAVE|LOGOUT|SESSION_EXPIRED|FORCE_LOGOUT',
    elo_before  INT          NOT NULL DEFAULT 0,
    elo_change  INT          NOT NULL DEFAULT 0,
    recorded_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_abandon_user_id    (user_id),
    INDEX idx_abandon_match_id   (match_id),
    INDEX idx_abandon_recorded_at(recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =============================================================================
-- 7. DEDICATED SERVERS (V7 + V18: map_id + V23: match_id)
-- =============================================================================

CREATE TABLE IF NOT EXISTS dedicated_servers (
    id                  BIGINT        AUTO_INCREMENT PRIMARY KEY,
    server_id           VARCHAR(36)   NOT NULL UNIQUE,
    docker_container_id VARCHAR(64)   NULL,
    ip                  VARCHAR(45)   NOT NULL,
    port                INT           NOT NULL,
    status              VARCHAR(20)   NOT NULL DEFAULT 'starting'  COMMENT 'starting|ready|in_game|stopped',
    region              VARCHAR(20)   NOT NULL DEFAULT 'vn',
    current_players     INT           NOT NULL DEFAULT 0,
    max_players         INT           NOT NULL DEFAULT 16,
    image_tag           VARCHAR(100)  NULL,
    server_secret_hash  VARCHAR(255)  NOT NULL,
    last_heartbeat_at   DATETIME      NULL,
    started_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    stopped_at          DATETIME      NULL,
    map_id              VARCHAR(50)   NULL     COMMENT 'MapEntry.mapId this DS instance loaded', -- V18
    match_id            VARCHAR(36)   NULL,                                                      -- V23

    INDEX idx_ds_status(status),
    INDEX idx_ds_port  (port),
    INDEX idx_ds_region(region, status),
    INDEX idx_ds_map   (map_id, status, region)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =============================================================================
-- 8. MATCHMAKING
-- =============================================================================

CREATE TABLE IF NOT EXISTS matchmaking_queue (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL UNIQUE,
    elo            INT          NOT NULL DEFAULT 1000,
    game_mode      VARCHAR(20)  NOT NULL,
    queued_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    search_min_elo INT          NOT NULL DEFAULT 0,
    search_max_elo INT          NOT NULL DEFAULT 9999,
    status         VARCHAR(20)  NOT NULL DEFAULT 'SEARCHING'  COMMENT 'SEARCHING|MATCHED|CANCELLED',
    map_id         VARCHAR(50)  NULL,                                -- V18
    lobby_token    VARCHAR(64)  NULL,                                -- V14
    accept_status  VARCHAR(20)  NOT NULL DEFAULT 'PENDING'    COMMENT 'PENDING|ACCEPTED|DECLINED', -- V14
    platform       VARCHAR(20)  NULL,                                -- V24

    PRIMARY KEY (id),
    INDEX idx_mmq_user_id     (user_id),
    INDEX idx_mmq_game_mode   (game_mode),
    INDEX idx_mmq_status      (status),
    INDEX idx_mmq_lobby_token (lobby_token),
    INDEX idx_mmq_accept_status(accept_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =============================================================================
-- 9. USER ACTIVITY LOGS
-- =============================================================================

CREATE TABLE IF NOT EXISTS user_activity_logs (
    id          BIGINT      AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT      NULL,
    username    VARCHAR(50) NULL,
    event_type  VARCHAR(50) NOT NULL,
    event_data  TEXT        NULL,
    ip_address  VARCHAR(50) NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_ual_user_id   (user_id),
    INDEX idx_ual_event_type(event_type),
    INDEX idx_ual_created_at(created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 10. SOCIAL SYSTEM: FRIENDS, REQUESTS, BLOCKS
-- =============================================================================

CREATE TABLE IF NOT EXISTS friends (
    id                BIGINT       PRIMARY KEY AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    friend_user_id    BIGINT       NOT NULL,
    friendship_status VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'  COMMENT 'ACTIVE|BLOCKED',
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT uk_user_friend UNIQUE (user_id, friend_user_id),
    INDEX idx_friends_user_id       (user_id),
    INDEX idx_friends_friend_user_id(friend_user_id),
    INDEX idx_friends_status        (friendship_status),
    CONSTRAINT fk_friends_user_id        FOREIGN KEY (user_id)        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_friends_friend_user_id FOREIGN KEY (friend_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS friend_requests (
    id                BIGINT       PRIMARY KEY AUTO_INCREMENT,
    requester_user_id BIGINT       NOT NULL,
    addressee_user_id BIGINT       NOT NULL,
    request_status    VARCHAR(20)  NOT NULL DEFAULT 'PENDING'  COMMENT 'PENDING|ACCEPTED|DECLINED|CANCELLED',
    expires_at        TIMESTAMP    NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT uk_friend_request UNIQUE (requester_user_id, addressee_user_id),
    INDEX idx_friend_requests_addressee(addressee_user_id, request_status),
    INDEX idx_friend_requests_requester(requester_user_id),
    INDEX idx_friend_requests_expires  (expires_at),
    CONSTRAINT fk_friend_requests_requester FOREIGN KEY (requester_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_friend_requests_addressee FOREIGN KEY (addressee_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS blocked_users (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
    blocker_user_id BIGINT       NOT NULL,
    blocked_user_id BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_blocker_blocked UNIQUE (blocker_user_id, blocked_user_id),
    INDEX idx_blocked_users_blocker(blocker_user_id),
    INDEX idx_blocked_users_blocked(blocked_user_id),
    CONSTRAINT fk_blocked_users_blocker FOREIGN KEY (blocker_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_blocked_users_blocked FOREIGN KEY (blocked_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 11. PARTY SYSTEM
-- =============================================================================

CREATE TABLE IF NOT EXISTS parties (
    id                      BIGINT       PRIMARY KEY AUTO_INCREMENT,
    host_user_id            BIGINT       NOT NULL,
    party_status            VARCHAR(20)  NOT NULL DEFAULT 'IDLE'  COMMENT 'IDLE|IN_QUEUE|IN_ROOM|IN_GAME|DISBANDED',
    party_mode              VARCHAR(10)  NOT NULL DEFAULT 'NONE'  COMMENT 'NONE|RANKED|CUSTOM', -- V27
    current_room_id         BIGINT       NULL,
    current_matchmaking_id  BIGINT       NULL,
    max_members             INT          NOT NULL DEFAULT 4,
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_parties_status       (party_status),
    INDEX idx_parties_host_user_id (host_user_id),
    INDEX idx_parties_party_mode   (party_mode),
    CONSTRAINT fk_parties_host_user_id    FOREIGN KEY (host_user_id)    REFERENCES users(id)  ON DELETE CASCADE,
    CONSTRAINT fk_parties_current_room_id FOREIGN KEY (current_room_id) REFERENCES rooms(id)  ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS party_members (
    id         BIGINT    PRIMARY KEY AUTO_INCREMENT,
    party_id   BIGINT    NOT NULL,
    user_id    BIGINT    NOT NULL,
    join_order INT       NOT NULL  COMMENT '0=host, 1,2,3=guests',
    joined_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_party_user UNIQUE (party_id, user_id),
    INDEX idx_party_members_user_id (user_id),
    INDEX idx_party_members_party_id(party_id),
    CONSTRAINT fk_party_members_party_id FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE,
    CONSTRAINT fk_party_members_user_id  FOREIGN KEY (user_id)  REFERENCES users(id)   ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS party_invitations (
    id                BIGINT       PRIMARY KEY AUTO_INCREMENT,
    party_id          BIGINT       NOT NULL,
    inviter_user_id   BIGINT       NOT NULL,
    invitee_user_id   BIGINT       NOT NULL,
    invitation_status VARCHAR(20)  NOT NULL DEFAULT 'PENDING'  COMMENT 'PENDING|ACCEPTED|DECLINED|EXPIRED|CANCELLED',
    expires_at        TIMESTAMP    NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_party_invitations_invitee(invitee_user_id, invitation_status),
    INDEX idx_party_invitations_expires(expires_at),
    CONSTRAINT fk_party_invitations_party_id FOREIGN KEY (party_id)        REFERENCES parties(id) ON DELETE CASCADE,
    CONSTRAINT fk_party_invitations_inviter  FOREIGN KEY (inviter_user_id) REFERENCES users(id)   ON DELETE CASCADE,
    CONSTRAINT fk_party_invitations_invitee  FOREIGN KEY (invitee_user_id) REFERENCES users(id)   ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 12. CIRCULAR FKs (users ↔ parties/rooms) — added after both tables exist
-- =============================================================================

ALTER TABLE users
    ADD CONSTRAINT fk_users_current_party_id
        FOREIGN KEY (current_party_id) REFERENCES parties(id) ON DELETE SET NULL;

ALTER TABLE users
    ADD CONSTRAINT fk_users_current_room_id
        FOREIGN KEY (current_room_id) REFERENCES rooms(id) ON DELETE SET NULL;

-- =============================================================================
-- 13. RBAC
-- =============================================================================

CREATE TABLE IF NOT EXISTS role_permissions (
    id         BIGINT       PRIMARY KEY AUTO_INCREMENT,
    role       VARCHAR(20)  NOT NULL,
    permission VARCHAR(100) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE INDEX idx_role_permission(role, permission)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS admin_actions (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
    admin_user_id   BIGINT       NOT NULL,
    admin_username  VARCHAR(50)  NOT NULL,
    action_type     VARCHAR(50)  NOT NULL,
    target_user_id  BIGINT       NULL,
    target_username VARCHAR(50)  NULL,
    details         TEXT         NULL,
    ip_address      VARCHAR(50)  NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_admin_actions_admin_user_id (admin_user_id),
    INDEX idx_admin_actions_target_user_id(target_user_id),
    INDEX idx_admin_actions_action_type   (action_type),
    INDEX idx_admin_actions_created_at    (created_at),
    FOREIGN KEY (admin_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 14. GAME MODES (V12 create + V19/V20/V24/V26 additions)
-- =============================================================================

CREATE TABLE IF NOT EXISTS game_modes (
    id                   BIGINT        PRIMARY KEY AUTO_INCREMENT,
    mode_key             VARCHAR(20)   NOT NULL UNIQUE,
    display_name         VARCHAR(50)   NOT NULL,
    players_per_team     INT           NOT NULL,
    total_players        INT           NOT NULL,
    mode_status          VARCHAR(20)   NOT NULL DEFAULT 'AVAILABLE'  COMMENT 'AVAILABLE|LOCKED|COMING_SOON|DISABLED',
    display_order        INT           NOT NULL DEFAULT 0,
    is_active            BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- V19
    description          VARCHAR(120)  NULL,
    allow_fill           TINYINT(1)    NOT NULL DEFAULT 0,
    matchmaking_enabled  TINYINT(1)    NOT NULL DEFAULT 1,
    min_elo              INT           NOT NULL DEFAULT 0,
    max_elo              INT           NOT NULL DEFAULT 9999,
    -- V20
    is_dev_mode          TINYINT(1)    NOT NULL DEFAULT 0             COMMENT 'Excluded from client API',
    -- V24
    platform_filter      VARCHAR(20)   NOT NULL DEFAULT 'ALL'         COMMENT 'ALL|MOBILE|PC',

    INDEX idx_game_modes_status(mode_status, is_active),
    INDEX idx_game_modes_order (display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 15. GAME MAPS & CONFIG (V19 + V29 zone_config)
-- =============================================================================

CREATE TABLE IF NOT EXISTS game_maps (
    id                      BIGINT        PRIMARY KEY AUTO_INCREMENT,
    map_id                  VARCHAR(50)   NOT NULL UNIQUE,
    display_name            VARCHAR(80)   NOT NULL,
    description             VARCHAR(200)  NULL,
    scene_name              VARCHAR(80)   NOT NULL,
    supported_modes         JSON          NULL,
    zone_config             JSON          NULL,             -- V29
    supported_player_counts JSON          NULL,             -- V29
    is_locked               TINYINT(1)    NOT NULL DEFAULT 0,
    is_active               TINYINT(1)    NOT NULL DEFAULT 1,
    display_order           INT           NOT NULL DEFAULT 0,
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_maps_active(is_active, is_locked),
    INDEX idx_maps_order (display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS game_config (
    config_key   VARCHAR(80)  NOT NULL PRIMARY KEY,
    config_value VARCHAR(255) NOT NULL,
    value_type   VARCHAR(20)  NOT NULL DEFAULT 'STRING',
    description  VARCHAR(200) NULL,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 16. SERVER METRICS
-- =============================================================================

CREATE TABLE IF NOT EXISTS server_metrics_snapshots (
    id                BIGINT      AUTO_INCREMENT PRIMARY KEY,
    snapshot_at       DATETIME(3) NOT NULL,
    online_users      INT         NOT NULL DEFAULT 0,
    queue_depth       INT         NOT NULL DEFAULT 0,
    active_rooms      INT         NOT NULL DEFAULT 0,
    in_game_rooms     INT         NOT NULL DEFAULT 0,
    waiting_rooms     INT         NOT NULL DEFAULT 0,
    active_ds         INT         NOT NULL DEFAULT 0,
    in_game_ds        INT         NOT NULL DEFAULT 0,
    players_in_ds     INT         NOT NULL DEFAULT 0,
    matches_last_hour INT         NOT NULL DEFAULT 0,

    INDEX idx_snapshot_at(snapshot_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =============================================================================
-- 17. HEADLESS SERVER CONFIG  (headless_servers was dropped in V5;
--     headless_server_config survived due to a typo in V5's DROP statement)
-- =============================================================================

CREATE TABLE IF NOT EXISTS headless_server_config (
    id           BIGINT       PRIMARY KEY AUTO_INCREMENT,
    config_key   VARCHAR(100) NOT NULL UNIQUE,
    config_value VARCHAR(500) NOT NULL,
    description  VARCHAR(500) NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_config_key(config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =============================================================================
-- SEED DATA
-- =============================================================================

-- ── ban_config (final: V35 raised stress-test thresholds) ────────────────────
INSERT INTO ban_config (config_key, config_value, description) VALUES
('MAX_FAILED_LOGIN_ATTEMPTS',          '10000', 'Raised for stress-test (effective limit)'),
('FAILED_LOGIN_WINDOW_MINUTES',        '15',    'Time window for counting failed login attempts (minutes)'),
('FAILED_LOGIN_BAN_DURATION_MINUTES',  '30',    'Auto-ban duration for failed login attempts (minutes)'),
('MAX_CONCURRENT_LOGIN_ATTEMPTS',      '10000', 'Raised for stress-test (effective limit)'),
('CONCURRENT_LOGIN_WINDOW_SECONDS',    '600',   'Raised for stress-test (seconds)'),
('CONCURRENT_LOGIN_BAN_DURATION_MINUTES','15',  'Auto-ban duration for concurrent login attempts (minutes)'),
('AUTO_UNBAN_ENABLED',                 'true',  'Enable automatic unban after ban duration expires'),
('AUTO_UNBAN_CHECK_INTERVAL_SECONDS',  '60',    'Interval to check for expired bans (seconds)')
ON DUPLICATE KEY UPDATE config_value = VALUES(config_value);

-- ── rate_limit_rules (final values after V17/V25/V32/V33) ────────────────────
INSERT INTO rate_limit_rules (endpoint_pattern, method, limit_type, max_requests, window_seconds, scope, description) VALUES
('/auth/login',          'POST', 'FIXED_WINDOW', 10000, 60,   'IP',   'Login: 10000/min per IP (stress-test headroom)'),
('/auth/register',       'POST', 'FIXED_WINDOW', 600,   60,   'IP',   'Registration: 600/min per IP (stress-test headroom)'),
('/auth/auto-login',     'POST', 'FIXED_WINDOW', 120,   60,   'USER', 'Auto-login/session-check: 120/min per user'),
('/auth/change-password','POST', 'FIXED_WINDOW', 10,    300,  'USER', 'Password change: 10 per 5 min per user'),
('/auth/refresh-token',  'POST', 'FIXED_WINDOW', 120,   60,   'USER', 'Token refresh: 120/min per user'),
('/rooms/create',        'POST', 'FIXED_WINDOW', 60,    60,   'USER', 'Room create: 60/min per user'),
('/rooms/join-by-code',  'POST', 'FIXED_WINDOW', 120,   60,   'USER', 'Join room by code: 120/min per user'),
('/rooms/quick-play',    'POST', 'FIXED_WINDOW', 120,   60,   'USER', 'Quick play: 120/min per user'),
('/rooms/*/ready',       'POST', 'FIXED_WINDOW', 300,   60,   'USER', 'Set ready: 300/min per user'),
('/rooms/*/change-team', 'POST', 'FIXED_WINDOW', 200,   60,   'USER', 'Change team: 200/min per user'),
('/rooms/*/start',       'POST', 'FIXED_WINDOW', 30,    60,   'USER', 'Start game: 30/min per user'),
('/friends',             'GET',  'FIXED_WINDOW', 10000, 60,   'USER', 'Get friend list: 10000/min per user'),
('/friends/requests',    'POST', 'FIXED_WINDOW', 60,    60,   'USER', 'Send friend request: 60/min per user'),
('/friends/requests/*',  'GET',  'FIXED_WINDOW', 120,   60,   'USER', 'Get friend requests: 120/min per user'),
('/friends/requests/*',  'POST', 'FIXED_WINDOW', 120,   60,   'USER', 'Accept/decline friend request: 120/min per user'),
('/friends/*',           '*',    'FIXED_WINDOW', 10000, 60,   'USER', 'Friend misc actions: 10000/min per user'),
('/party/current',       'GET',  'FIXED_WINDOW', 300,   60,   'USER', 'Get party state: 300/min per user'),
('/party/create',        'POST', 'FIXED_WINDOW', 60,    60,   'USER', 'Create party: 60/min per user'),
('/party/invite',        'POST', 'FIXED_WINDOW', 300,   60,   'USER', 'Party invite: 300/min per user'),
('/party/invitations/*', '*',    'FIXED_WINDOW', 120,   60,   'USER', 'Party invitation response: 120/min per user'),
('/party/*',             '*',    'FIXED_WINDOW', 300,   60,   'USER', 'Party operations catch-all: 300/min per user'),
('/game-modes',          'GET',  'FIXED_WINDOW', 30,    60,   'IP',   'Game modes config: 30/min per IP'),
('/maps',                'GET',  'FIXED_WINDOW', 30,    60,   'IP',   'Maps config: 30/min per IP'),
('/profile',             'GET',  'FIXED_WINDOW', 10000, 60,   'USER', 'User profile: 10000/min per user'),
('/matchmaking/queue',   '*',    'FIXED_WINDOW', 20,    60,   'USER', 'Matchmaking queue (join + cancel): 20/min per user'),
('/matchmaking/*',       '*',    'FIXED_WINDOW', 120,   60,   'USER', 'Matchmaking status poll: 120/min per user'),
('/*',                   '*',    'FIXED_WINDOW', 50000, 60,   'USER', 'General API catch-all: 50000/min per user')
ON DUPLICATE KEY UPDATE
    max_requests   = VALUES(max_requests),
    window_seconds = VALUES(window_seconds),
    method         = VALUES(method),
    description    = VALUES(description),
    updated_at     = CURRENT_TIMESTAMP;

-- ── game_modes (final after V12, V19, V20, V24, V26) ─────────────────────────
INSERT INTO game_modes (mode_key, display_name, players_per_team, total_players, mode_status, display_order, description, allow_fill, matchmaking_enabled, min_elo, max_elo, is_active, is_dev_mode, platform_filter) VALUES
('2v2', '2 vs 2', 2,  4,  'AVAILABLE',   1, 'Duo match — team of two',         1, 1, 0, 9999, 1, 0, 'ALL'),
('3v3', '3 vs 3', 3,  6,  'AVAILABLE',   2, 'Squad match — team of three',     1, 1, 0, 9999, 1, 0, 'ALL'),
('4v4', '4 vs 4', 4,  8,  'AVAILABLE',   3, 'Full squad — team of four',       1, 1, 0, 9999, 1, 0, 'ALL'),
('5v5', '5 vs 5', 5,  10, 'AVAILABLE',   4, 'Large battle — five per side',    1, 0, 0, 9999, 1, 0, 'ALL'),
('1v1', '1 vs 1', 1,  2,  'AVAILABLE',   0, '1v1 ranked test mode — 2 players, 1 per team.', 1, 1, 0, 9999, 1, 0, 'ALL')
ON DUPLICATE KEY UPDATE
    display_name        = VALUES(display_name),
    description         = VALUES(description),
    mode_status         = VALUES(mode_status),
    allow_fill          = VALUES(allow_fill),
    matchmaking_enabled = VALUES(matchmaking_enabled),
    is_dev_mode         = VALUES(is_dev_mode),
    platform_filter     = VALUES(platform_filter),
    updated_at          = CURRENT_TIMESTAMP;

-- ── game_maps (final zone_config from V31) ────────────────────────────────────
INSERT INTO game_maps (map_id, display_name, description, scene_name, supported_modes, is_locked, is_active, display_order,
    zone_config, supported_player_counts) VALUES
('map_01', 'Industrial Zone', 'Urban combat in a derelict factory.', 'GameMap_01',
    '["2v2","3v3","4v4","5v5","1v1"]', 0, 1, 1,
    JSON_OBJECT(
        'initialRadius', 400.0, 'finalZoneMinRadius', 10.0,
        'centerMode', 0,
        'maxCenterShiftPercent', 0.6, 'minCenterShiftPercent', 0.1,
        'beaconAllowedInFinalZone', FALSE,
        'baseSurvivalPtsPerSecond', 1.0, 'captureZoneScorePerSecond', 20.0,
        'killScore', 100.0, 'bossKillScore', 300.0, 'killScoreStealPercent', 0.15,
        'phases', JSON_ARRAY(
            JSON_OBJECT('zoneIndex',0,'startRadius',400.0,'endRadius',200.0,'waitBeforeShrink',120.0,'shrinkDuration',180.0,'damagePerSecond',0.0,'damageTick',1.0,'isScoreBonusZone',FALSE,'zoneBonusMultiplier',1.5,'minRadiusOverride',0.0),
            JSON_OBJECT('zoneIndex',1,'startRadius',200.0,'endRadius',100.0,'waitBeforeShrink',90.0,'shrinkDuration',120.0,'damagePerSecond',3.0,'damageTick',1.0,'isScoreBonusZone',FALSE,'zoneBonusMultiplier',1.5,'minRadiusOverride',0.0),
            JSON_OBJECT('zoneIndex',2,'startRadius',100.0,'endRadius',50.0,'waitBeforeShrink',60.0,'shrinkDuration',90.0,'damagePerSecond',8.0,'damageTick',1.0,'isScoreBonusZone',TRUE,'zoneBonusMultiplier',2.0,'minRadiusOverride',0.0),
            JSON_OBJECT('zoneIndex',3,'startRadius',50.0,'endRadius',25.0,'waitBeforeShrink',45.0,'shrinkDuration',60.0,'damagePerSecond',15.0,'damageTick',1.0,'isScoreBonusZone',FALSE,'zoneBonusMultiplier',1.5,'minRadiusOverride',0.0),
            JSON_OBJECT('zoneIndex',4,'startRadius',25.0,'endRadius',10.0,'waitBeforeShrink',30.0,'shrinkDuration',30.0,'damagePerSecond',25.0,'damageTick',1.0,'isScoreBonusZone',FALSE,'zoneBonusMultiplier',1.5,'minRadiusOverride',10.0)
        )
    ),
    '[2,4,6,8,10]'),
('map_02', 'Arctic Base', 'Close-quarters in a frozen research facility.', 'GameMap_02',
    '["2v2","3v3","1v1"]', 0, 1, 2,
    JSON_OBJECT(
        'initialRadius', 100.0, 'finalZoneMinRadius', 10.0,
        'centerMode', 2,
        'maxCenterShiftPercent', 0.0, 'minCenterShiftPercent', 0.0,
        'beaconAllowedInFinalZone', FALSE,
        'baseSurvivalPtsPerSecond', 1.0, 'captureZoneScorePerSecond', 20.0,
        'killScore', 100.0, 'bossKillScore', 300.0, 'killScoreStealPercent', 0.15,
        'phases', JSON_ARRAY(
            JSON_OBJECT('zoneIndex',0,'startRadius',100.0,'endRadius',50.0,'waitBeforeShrink',45.0,'shrinkDuration',45.0,'damagePerSecond',8.0,'damageTick',1.0,'isScoreBonusZone',TRUE,'zoneBonusMultiplier',2.0,'minRadiusOverride',0.0),
            JSON_OBJECT('zoneIndex',1,'startRadius',50.0,'endRadius',25.0,'waitBeforeShrink',30.0,'shrinkDuration',30.0,'damagePerSecond',15.0,'damageTick',1.0,'isScoreBonusZone',FALSE,'zoneBonusMultiplier',1.5,'minRadiusOverride',0.0),
            JSON_OBJECT('zoneIndex',2,'startRadius',25.0,'endRadius',10.0,'waitBeforeShrink',20.0,'shrinkDuration',20.0,'damagePerSecond',25.0,'damageTick',1.0,'isScoreBonusZone',FALSE,'zoneBonusMultiplier',1.5,'minRadiusOverride',10.0)
        )
    ),
    '[2,4,6]')
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    supported_modes = VALUES(supported_modes),
    zone_config = VALUES(zone_config),
    supported_player_counts = VALUES(supported_player_counts),
    is_active = VALUES(is_active),
    updated_at = CURRENT_TIMESTAMP;

-- ── game_config ───────────────────────────────────────────────────────────────
INSERT INTO game_config (config_key, config_value, value_type, description) VALUES
('matchmaking.elo.initialRange',      '100',  'INT',  'Initial ELO window half-width (±X) on queue entry'),
('matchmaking.elo.expandStep',        '50',   'INT',  'ELO expand per side every expandInterval seconds'),
('matchmaking.elo.expandIntervalSec', '15',   'INT',  'Seconds between ELO window expansions'),
('matchmaking.elo.maxRange',          '500',  'INT',  'Maximum ELO window half-width'),
('matchmaking.tick.intervalMs',       '5000', 'INT',  'How often the matchmaking scheduler runs (ms)'),
('matchmaking.acceptTimeout.sec',     '30',   'INT',  'Seconds for player to accept before auto-decline'),
('ds.defaultMaxPlayers',              '16',   'INT',  'Max players per dedicated server instance'),
('ds.portStart',                      '7777', 'INT',  'First UDP port in DS port range'),
('ds.portEnd',                        '7900', 'INT',  'Last UDP port in DS port range'),
('room.maxPlayersPerTeam',            '5',    'INT',  'Hard cap on players per team in any room'),
('room.minPlayersToStart',            '2',    'INT',  'Minimum total players required to start a match')
ON DUPLICATE KEY UPDATE config_value = VALUES(config_value);

-- ── headless_server_config ────────────────────────────────────────────────────
INSERT INTO headless_server_config (config_key, config_value, description) VALUES
('build.path',                '../Build/Server',          'Path to headless server build directory'),
('default.version',           'Ver0.0.1',                 'Default headless server version'),
('log.path',                  './logs/headless-servers',  'Path to headless server logs'),
('auto-scaling.enabled',      'true',                     'Enable automatic server scaling'),
('scale-up.threshold',        '0.8',                      'Scale up when server capacity reaches 80%'),
('scale-down.threshold',      '0.2',                      'Scale down when server capacity drops below 20%'),
('idle-timeout.minutes',      '5',                        'Idle timeout in minutes before shutting down server'),
('max-servers',               '10',                       'Maximum number of headless servers'),
('default-ip',                '127.0.0.1',                'Default server IP address'),
('base-port',                 '7777',                     'Base port for headless servers'),
('max-matches',               '10',                       'Maximum matches per server'),
('server.version',            'Ver0.0.1',                 'Server version')
ON DUPLICATE KEY UPDATE config_value = VALUES(config_value);

-- ── admin user (CHANGE PASSWORD before production!) ───────────────────────────
-- Default password: "AdminPassword123"   hash: $2a$10$dXJ3SW6G7P2lh/Y9sB5k5....
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
) ON DUPLICATE KEY UPDATE role = 'ADMIN', role_assigned_at = NOW();

-- ── role_permissions ──────────────────────────────────────────────────────────
INSERT INTO role_permissions (role, permission) VALUES
('USER',      'PLAY_GAME'),
('USER',      'CREATE_PARTY'),
('USER',      'CREATE_ROOM'),
('USER',      'SEND_FRIEND_REQUEST'),
('USER',      'VIEW_PROFILE'),
('SUPPORT',   'PLAY_GAME'),('SUPPORT',   'CREATE_PARTY'),('SUPPORT','CREATE_ROOM'),
('SUPPORT',   'SEND_FRIEND_REQUEST'),('SUPPORT','VIEW_PROFILE'),
('SUPPORT',   'VIEW_DASHBOARD'),('SUPPORT','VIEW_USER_DETAILS'),
('SUPPORT',   'VIEW_ACTIVITY_LOGS'),('SUPPORT','VIEW_BAN_LIST'),('SUPPORT','VIEW_MATCH_HISTORY'),
('MODERATOR', 'PLAY_GAME'),('MODERATOR','CREATE_PARTY'),('MODERATOR','CREATE_ROOM'),
('MODERATOR', 'SEND_FRIEND_REQUEST'),('MODERATOR','VIEW_PROFILE'),
('MODERATOR', 'VIEW_DASHBOARD'),('MODERATOR','VIEW_USER_DETAILS'),
('MODERATOR', 'VIEW_ACTIVITY_LOGS'),('MODERATOR','VIEW_BAN_LIST'),
('MODERATOR', 'VIEW_MATCH_HISTORY'),('MODERATOR','BAN_USER'),('MODERATOR','UNBAN_USER'),
('MODERATOR', 'KICK_USER_FROM_ROOM'),('MODERATOR','DELETE_OFFENSIVE_CONTENT'),('MODERATOR','VIEW_REPORTS'),
('ADMIN',     'PLAY_GAME'),('ADMIN','CREATE_PARTY'),('ADMIN','CREATE_ROOM'),
('ADMIN',     'SEND_FRIEND_REQUEST'),('ADMIN','VIEW_PROFILE'),
('ADMIN',     'VIEW_DASHBOARD'),('ADMIN','VIEW_USER_DETAILS'),
('ADMIN',     'VIEW_ACTIVITY_LOGS'),('ADMIN','VIEW_BAN_LIST'),
('ADMIN',     'VIEW_MATCH_HISTORY'),('ADMIN','BAN_USER'),('ADMIN','UNBAN_USER'),
('ADMIN',     'KICK_USER_FROM_ROOM'),('ADMIN','DELETE_OFFENSIVE_CONTENT'),('ADMIN','VIEW_REPORTS'),
('ADMIN',     'MANAGE_ROLES'),('ADMIN','DELETE_USER'),('ADMIN','RESET_PASSWORD'),
('ADMIN',     'VIEW_SYSTEM_METRICS'),('ADMIN','MANAGE_DEDICATED_SERVERS'),
('ADMIN',     'EDIT_ELO'),('ADMIN','VIEW_REDIS_DATA'),('ADMIN','FORCE_LOGOUT_USER')
ON DUPLICATE KEY UPDATE role = role;

SET FOREIGN_KEY_CHECKS = 1;

-- =============================================================================
-- To apply this baseline on a new machine:
--   1. Create the database:  CREATE DATABASE nighthunt_db CHARACTER SET utf8mb4;
--   2. Run this file:        mysql -u root -p nighthunt_db < scripts/db-baseline-v38.sql
--   3. Done. Flyway baseline the existing schema so it won't re-run V1–V38:
--        Add to application.yml:  spring.flyway.baseline-on-migrate: true
--                                 spring.flyway.baseline-version: 38
-- =============================================================================
