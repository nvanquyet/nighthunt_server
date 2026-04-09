package com.nighthunt.messaging.constants;

/**
 * Message topics/channels constants
 * Centralized topic names for message broker
 */
public class MessageTopics {
    
    // ==================== Authentication Topics ====================
    public static final String AUTH_USER_LOGIN = "auth.user.login";
    public static final String AUTH_USER_LOGOUT = "auth.user.logout";
    public static final String AUTH_USER_REGISTERED = "auth.user.registered";
    public static final String AUTH_SESSION_EXPIRED = "auth.session.expired";
    public static final String AUTH_FORCE_LOGOUT = "auth.force.logout";
    
    // ==================== Room Topics ====================
    public static final String ROOM_CREATED = "room.created";
    public static final String ROOM_UPDATED = "room.updated";
    public static final String ROOM_DELETED = "room.deleted";
    public static final String ROOM_PLAYER_JOINED = "room.player.joined";
    public static final String ROOM_PLAYER_LEFT = "room.player.left";
    public static final String ROOM_PLAYER_READY = "room.player.ready";
    public static final String ROOM_PLAYER_TEAM_CHANGED = "room.player.team.changed";
    public static final String ROOM_STATUS_CHANGED = "room.status.changed";
    public static final String ROOM_OWNER_CHANGED = "room.owner.changed";
    public static final String ROOM_SWAP_REQUEST = "room.swap.request";
    public static final String ROOM_SWAP_REQUEST_STATUS = "room.swap.request.status";
    
    // ==================== Match Topics ====================
    public static final String MATCH_CREATED = "match.created";
    public static final String MATCH_STARTED = "match.started";
    public static final String MATCH_ENDED = "match.ended";
    public static final String MATCH_PLAYER_JOINED = "match.player.joined";
    public static final String MATCH_PLAYER_LEFT = "match.player.left";
    /** Fired when the matchmaking system finds a full group. */
    public static final String MATCH_FOUND = "match.found";
    /** Fired when the DS/relay host confirms all players are in and the game is loading. */
    public static final String GAME_STARTING = "game.starting";
    /** Fired when a relay session is created (Custom mode). */
    public static final String RELAY_SESSION_CREATED = "relay.session.created";
    
    // ==================== Headless Server Topics ====================
    public static final String HEADLESS_SERVER_STARTED = "headless.server.started";
    public static final String HEADLESS_SERVER_STOPPED = "headless.server.stopped";
    public static final String HEADLESS_SERVER_ERROR = "headless.server.error";
    
    // ==================== System Topics ====================
    public static final String SYSTEM_HEALTH_CHECK = "system.health.check";
    public static final String SYSTEM_METRICS = "system.metrics";
    public static final String SYSTEM_ALERT = "system.alert";
    
    // ==================== Friend System Topics ====================
    public static final String FRIEND_REQUEST_RECEIVED = "friend.request.received";
    public static final String FRIEND_REQUEST_ACCEPTED = "friend.request.accepted";
    public static final String FRIEND_REQUEST_DECLINED = "friend.request.declined";
    public static final String FRIEND_REMOVED = "friend.removed";
    public static final String FRIEND_STATUS_CHANGED = "friend.status.changed";
    public static final String FRIEND_BLOCKED = "friend.blocked";
    
    // ==================== Party System Topics ====================
    public static final String PARTY_CREATED = "party.created";
    public static final String PARTY_INVITATION_RECEIVED = "party.invitation.received";
    public static final String PARTY_MEMBER_JOINED = "party.member.joined";
    public static final String PARTY_MEMBER_LEFT = "party.member.left";
    public static final String PARTY_MEMBER_KICKED = "party.member.kicked";
    public static final String PARTY_DISBANDED = "party.disbanded";
    public static final String PARTY_HOST_CHANGED = "party.host.changed";
    public static final String PARTY_STATUS_CHANGED = "party.status.changed";
    public static final String PARTY_INVITATION_EXPIRED = "party.invitation.expired";
    
    // ==================== Notification Topics ====================
    public static final String NOTIFICATION_USER = "notification.user";
    public static final String NOTIFICATION_ROOM = "notification.room";
    public static final String NOTIFICATION_MATCH = "notification.match";
    
    // ==================== Topic Patterns ====================
    public static final String PATTERN_AUTH_ALL = "auth.*";
    public static final String PATTERN_ROOM_ALL = "room.*";
    public static final String PATTERN_MATCH_ALL = "match.*";
    public static final String PATTERN_NOTIFICATION_ALL = "notification.*";
    
    private MessageTopics() {
        // Utility class
    }
}

