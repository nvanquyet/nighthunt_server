package com.nighthunt.common.constants;

public class GameConstants {
    // Room status
    public static final String ROOM_STATUS_WAITING = "WAITING";
    public static final String ROOM_STATUS_IN_GAME = "IN_GAME";
    public static final String ROOM_STATUS_CLOSED = "CLOSED";
    public static final String ROOM_STATUS_FINISHED = "FINISHED";

    // Match status
    public static final String MATCH_STATUS_LOBBY = "LOBBY";
    public static final String MATCH_STATUS_IN_GAME = "IN_GAME";
    public static final String MATCH_STATUS_FINISHED = "FINISHED";

    // Game modes
    public static final String MODE_2V2 = "2v2";
    public static final String MODE_3V3 = "3v3";
    public static final String MODE_5V5 = "5v5";

    // Team
    public static final int TEAM_1 = 1;
    public static final int TEAM_2 = 2;

    // Session
    public static final int SESSION_TIMEOUT_SECONDS = 3600; // 1 hour
    public static final int RECONNECT_TIMEOUT_SECONDS = 60; // 1 minute
    public static final int MATCH_ABANDON_GRACE_SECONDS = 60; // Hold slot after in-match disconnect
    public static final int MATCH_PRESENCE_CACHE_TTL_SECONDS = 21600; // 6 hours

    // Redis keys
    public static final String REDIS_KEY_SESSION_PREFIX = "session:";
    public static final String REDIS_KEY_ROOM_STATE_PREFIX = "room_state:";
    public static final String REDIS_KEY_MATCH_SESSION_PREFIX = "match_session:";
    public static final String REDIS_KEY_MATCH_PRESENCE_PREFIX = "match_presence:";
    public static final String REDIS_KEY_FORCE_LOGOUT_PREFIX = "force_logout:";

    private GameConstants() {
    }
}

