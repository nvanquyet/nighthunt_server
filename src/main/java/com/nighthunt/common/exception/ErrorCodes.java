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
    public static final String AUTH_SERVER_BUSY = "AUTH_016";

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
    public static final String MATCH_ALREADY_FINISHED = "MATCH_004";

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

    // Party errors
    public static final String PARTY_NOT_FOUND = "PARTY_001";
    public static final String PARTY_ALREADY_IN_PARTY = "PARTY_002";
    public static final String PARTY_FULL = "PARTY_003";
    public static final String PARTY_NOT_HOST = "PARTY_004";
    public static final String PARTY_NOT_IN_PARTY = "PARTY_005";
    public static final String PARTY_USER_ALREADY_IN_PARTY = "PARTY_006";
    public static final String PARTY_INVITATION_EXISTS = "PARTY_007";
    public static final String PARTY_INVITATION_BLOCKED = "PARTY_008";
    public static final String PARTY_INVITATION_NOT_FOR_YOU = "PARTY_009";
    public static final String PARTY_INVITATION_NOT_PENDING = "PARTY_010";
    public static final String PARTY_INVITATION_EXPIRED = "PARTY_011";
    public static final String PARTY_DISBANDED = "PARTY_012";
    public static final String PARTY_INVITATION_NOT_FOUND = "PARTY_013";
    public static final String PARTY_INVITATION_NOT_YOURS = "PARTY_014";
    public static final String PARTY_CANNOT_KICK_SELF = "PARTY_015";
    public static final String PARTY_USER_NOT_IN_PARTY = "PARTY_016";
    public static final String PARTY_TRANSFER_SAME_USER = "PARTY_017";
    public static final String PARTY_NOT_IDLE = "PARTY_018";

    // Auth - general
    public static final String AUTH_REQUIRED = "AUTH_015";

    // DS errors
    public static final String DS_BAD_REQUEST = "DS_001";
    public static final String DS_INVALID_SECRET = "DS_002";
    public static final String DS_GAME_MODE_UNAVAILABLE = "DS_003";

    // Room - additional
    public static final String ROOM_ALREADY_IN_ROOM = "ROOM_014";
    public static final String ROOM_SWAP_REQUEST_PENDING = "ROOM_015";
    public static final String ROOM_SWAP_NOT_TARGET = "ROOM_016";
    public static final String ROOM_SWAP_NOT_REQUESTER = "ROOM_017";
    public static final String ROOM_SWAP_EXPIRED = "ROOM_018";
    public static final String ROOM_SWAP_CANCELLED = "ROOM_019";

    // Party - additional
    public static final String PARTY_SIZE_MISMATCH    = "PARTY_019";
    /** Party is in a custom lobby room; cannot start ranked matchmaking. */
    public static final String PARTY_IN_CUSTOM_MODE   = "PARTY_020";
    /** Party is in ranked matchmaking queue; cannot join a custom lobby. */
    public static final String PARTY_IN_RANKED_QUEUE  = "PARTY_021";
    /** Invitee must be actively online before a party invitation can be sent. */
    public static final String PARTY_INVITEE_OFFLINE  = "PARTY_022";
    /** A player cannot invite themselves to a party. */
    public static final String PARTY_INVITATION_SELF  = "PARTY_023";
    /** Solo player already has an active SEARCHING queue entry. */
    public static final String ALREADY_IN_QUEUE       = "MATCH_005";


    private ErrorCodes() {
    }
}

