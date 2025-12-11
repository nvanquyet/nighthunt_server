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

    // Match errors
    public static final String MATCH_NOT_FOUND = "MATCH_001";
    public static final String MATCH_SERVER_UNAVAILABLE = "MATCH_002";
    public static final String MATCH_JOIN_FAILED = "MATCH_003";

    // Session errors
    public static final String SESSION_NOT_FOUND = "SESSION_001";
    public static final String SESSION_INVALID = "SESSION_002";

    private ErrorCodes() {
    }
}

