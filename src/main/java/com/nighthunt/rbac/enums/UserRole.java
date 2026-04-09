package com.nighthunt.rbac.enums;

/**
 * User Roles for Role-Based Access Control (RBAC)
 * 
 * Hierarchy (from lowest to highest authority):
 * USER → SUPPORT → MODERATOR → ADMIN
 * 
 * Each role inherits permissions from lower roles.
 */
public enum UserRole {
    /**
     * Regular player - default role for all new users.
     * Permissions: Play game, create parties, send friend requests.
     */
    USER,
    
    /**
     * Support staff - can view dashboard and help users.
     * Additional permissions: View user details, activity logs, match history.
     */
    SUPPORT,
    
    /**
     * Moderator - can ban users and moderate content.
     * Additional permissions: Ban/unban users, kick from rooms, delete content.
     */
    MODERATOR,
    
    /**
     * Administrator - full access to all features.
     * Additional permissions: Manage roles, delete users, view system metrics,
     * manage dedicated servers, edit ELO, force logout users.
     */
    ADMIN;
    
    /**
     * Check if this role has at least the authority level of the given role.
     * 
     * @param targetRole The role to compare against
     * @return true if this role is equal to or higher than targetRole
     * 
     * @example
     * ADMIN.hasAuthorityOf(MODERATOR) // true
     * MODERATOR.hasAuthorityOf(ADMIN) // false
     * USER.hasAuthorityOf(USER) // true
     */
    public boolean hasAuthorityOf(UserRole targetRole) {
        return this.ordinal() >= targetRole.ordinal();
    }
    
    /**
     * Check if this role is strictly higher than the given role.
     * 
     * @param targetRole The role to compare against
     * @return true if this role is higher than targetRole
     */
    public boolean isHigherThan(UserRole targetRole) {
        return this.ordinal() > targetRole.ordinal();
    }
    
    /**
     * Get display name for UI rendering.
     * 
     * @return Human-readable role name
     */
    public String getDisplayName() {
        return switch (this) {
            case USER -> "User";
            case SUPPORT -> "Support Staff";
            case MODERATOR -> "Moderator";
            case ADMIN -> "Administrator";
        };
    }
    
    /**
     * Get color code for dashboard UI.
     * 
     * @return Hex color code for role badge
     */
    public String getColorCode() {
        return switch (this) {
            case USER -> "#6c757d";      // Gray
            case SUPPORT -> "#17a2b8";   // Cyan
            case MODERATOR -> "#ffc107";  // Yellow
            case ADMIN -> "#dc3545";      // Red
        };
    }
}
