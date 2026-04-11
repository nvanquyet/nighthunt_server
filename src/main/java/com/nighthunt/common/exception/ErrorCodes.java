package com.nighthunt.common.exception;

public class ErrorCodes {
    // Auth errors
    public static final String AUTH_USERNAME_EXISTS = "AUTH_001";
    public static final String AUTH_EMAIL_EXISTS = "AUTH_002";
    public static final String AUTH_PASSWORD_MISMATCH = "AUTH_003";
    public static final String AUTH_INVALID_CREDENTIALS = "AUTH_004";
    public static final String AUTH_TOKEN_INVALID = "AUTH_005";
    public static final String AUTH_TOKEN_EXPIRED = "AUTH_006";
    public static final String AUTH_SESSION_EXPIRED = "AUTH_007";
    public static final String AUTH_FORCE_LOGOUT = "AUTH_008";
    public static final String AUTH_OLD_PASSWORD_INCORRECT = "AUTH_009";
    public static final String AUTH_ACCOUNT_BANNED = "AUTH_010";
    public static final String AUTH_IP_BANNED = "AUTH_011";
    public static final String AUTH_DEVICE_BANNED = "AUTH_012";
    public static final String AUTH_REFRESH_TOKEN_INVALID = "AUTH_013"; // Refresh token not found, expired, or revoked
    public static final String AUTH_SESSION_CONFLICT = "AUTH_014"; // Login attempted while existing session active — old session terminated, retry required

    // Rate limiting errors
    public static final String RATE_LIMIT_EXCEEDED = "RATE_001";
    
    // Request queue errors
    public static final String REQUEST_QUEUED = "QUEUE_001";
    public static final String QUEUE_FULL = "QUEUE_002";
    public static final String REQUEST_EXPIRED = "QUEUE_003";

    // Room errors
    public static final String ROOM_NOT_FOUND = "ROOM_001";
    public static final String ROOM_FULL = "ROOM_002";
    public static final String ROOM_ALREADY_STARTED = "ROOM_003";
    public static final String ROOM_NOT_OWNER = "ROOM_004";
    public static final String ROOM_INVALID_CODE = "ROOM_005";
    public static final String ROOM_LOCKED = "ROOM_006";
    public static final String ROOM_PLAYER_NOT_FOUND = "ROOM_007";
    public static final String ROOM_NOT_READY = "ROOM_008";
    public static final String ROOM_SLOT_OCCUPIED = "ROOM_009";
    public static final String ROOM_PASSWORD_INVALID = "ROOM_010";
    public static final String ROOM_SWAP_REQUEST_NOT_FOUND = "ROOM_011";
    public static final String ROOM_SWAP_REQUEST_EXPIRED = "ROOM_012";
    public static final String ROOM_NOT_ENOUGH_PLAYERS = "ROOM_013";

    // User errors
    public static final String USER_NOT_FOUND = "USER_001";

    // Match errors
    public static final String MATCH_NOT_FOUND = "MATCH_001";
    public static final String MATCH_SERVER_UNAVAILABLE = "MATCH_002";
    public static final String MATCH_JOIN_FAILED = "MATCH_003";

    // Session errors
    public static final String SESSION_NOT_FOUND = "SESSION_001";
    public static final String SESSION_INVALID = "SESSION_002";

    // Profile errors
    public static final String PROFILE_CHARACTER_NOT_FOUND = "PROFILE_001";

    // Friend errors
    public static final String FRIEND_REQUEST_SELF = "FRIEND_001";
    public static final String FRIEND_ALREADY_EXISTS = "FRIEND_002";
    public static final String FRIEND_REQUEST_ALREADY_SENT = "FRIEND_003";
    public static final String FRIEND_REQUEST_BLOCKED = "FRIEND_004";
    public static final String FRIEND_REQUEST_NOT_FOR_YOU = "FRIEND_005";
    public static final String FRIEND_REQUEST_NOT_PENDING = "FRIEND_006";
    public static final String FRIEND_REQUEST_NOT_YOURS = "FRIEND_007";
    public static final String FRIEND_REQUEST_NOT_FOUND = "FRIEND_008";
    public static final String FRIEND_NOT_FOUND = "FRIEND_009";
    public static final String FRIEND_REQUEST_INVALID = "FRIEND_010";
    public static final String BLOCK_SELF = "FRIEND_011";
    public static final String BLOCK_ALREADY_EXISTS = "FRIEND_012";
    public static final String BLOCK_NOT_FOUND = "FRIEND_013";

    private ErrorCodes() {
    }
}

