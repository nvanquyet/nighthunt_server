package com.nighthunt.rbac.enums;

import lombok.Getter;

/**
 * System Permissions for fine-grained access control.
 * 
 * Mapped to roles via role_permissions table.
 * Each permission represents a specific action in the system.
 */
@Getter
public enum Permission {
    // ══════════════════════════════════════════════════════════════════════════
    // BASIC USER PERMISSIONS
    // ══════════════════════════════════════════════════════════════════════════
    
    PLAY_GAME("Play the game", "USER"),
    CREATE_PARTY("Create or join parties", "USER"),
    CREATE_ROOM("Create custom game rooms", "USER"),
    SEND_FRIEND_REQUEST("Send friend requests", "USER"),
    VIEW_PROFILE("View user profiles", "USER"),
    
    // ══════════════════════════════════════════════════════════════════════════
    // DASHBOARD VIEW PERMISSIONS (SUPPORT+)
    // ══════════════════════════════════════════════════════════════════════════
    
    VIEW_DASHBOARD("Access admin dashboard", "SUPPORT"),
    VIEW_USER_DETAILS("View detailed user information", "SUPPORT"),
    VIEW_ACTIVITY_LOGS("View user activity logs", "SUPPORT"),
    VIEW_BAN_LIST("View list of banned users", "SUPPORT"),
    VIEW_MATCH_HISTORY("View match history", "SUPPORT"),
    VIEW_REPORTS("View user reports", "MODERATOR"),
    
    // ══════════════════════════════════════════════════════════════════════════
    // MODERATION PERMISSIONS (MODERATOR+)
    // ══════════════════════════════════════════════════════════════════════════
    
    BAN_USER("Ban users from the game", "MODERATOR"),
    UNBAN_USER("Remove bans from users", "MODERATOR"),
    KICK_USER_FROM_ROOM("Kick users from game rooms", "MODERATOR"),
    DELETE_OFFENSIVE_CONTENT("Delete offensive content", "MODERATOR"),
    MUTE_USER("Mute users in chat", "MODERATOR"),
    
    // ══════════════════════════════════════════════════════════════════════════
    // ADMIN PERMISSIONS (ADMIN only)
    // ══════════════════════════════════════════════════════════════════════════
    
    MANAGE_ROLES("Assign roles to users", "ADMIN"),
    DELETE_USER("Permanently delete user accounts", "ADMIN"),
    RESET_PASSWORD("Reset user passwords", "ADMIN"),
    VIEW_SYSTEM_METRICS("View system performance metrics", "ADMIN"),
    MANAGE_DEDICATED_SERVERS("Manage dedicated game servers", "ADMIN"),
    EDIT_ELO("Manually edit user ELO ratings", "ADMIN"),
    VIEW_REDIS_DATA("View Redis cache data", "ADMIN"),
    FORCE_LOGOUT_USER("Force logout users from all sessions", "ADMIN"),
    EDIT_SETTINGS("Edit system settings", "ADMIN"),
    VIEW_ERROR_LOGS("View system error logs", "ADMIN");
    
    private final String description;
    private final String minimumRole;
    
    Permission(String description, String minimumRole) {
        this.description = description;
        this.minimumRole = minimumRole;
    }
    
    /**
     * Get permission by name (case-insensitive).
     * 
     * @param name Permission name
     * @return Permission enum or null if not found
     */
    public static Permission fromString(String name) {
        try {
            return Permission.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    
    /**
     * Check if this permission requires at least ADMIN role.
     * 
     * @return true if ADMIN required
     */
    public boolean requiresAdmin() {
        return "ADMIN".equals(minimumRole);
    }
    
    /**
     * Check if this permission requires at least MODERATOR role.
     * 
     * @return true if MODERATOR or higher required
     */
    public boolean requiresModerator() {
        return "MODERATOR".equals(minimumRole) || requiresAdmin();
    }
}
