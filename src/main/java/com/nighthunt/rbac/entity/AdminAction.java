package com.nighthunt.rbac.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Admin Action Entity - Audit trail for all administrative actions.
 * 
 * Tracks who did what, when, and to whom for accountability and debugging.
 */
@Entity
@Table(name = "admin_actions", indexes = {
        @Index(name = "idx_admin_actions_admin_user_id", columnList = "admin_user_id"),
        @Index(name = "idx_admin_actions_target_user_id", columnList = "target_user_id"),
        @Index(name = "idx_admin_actions_action_type", columnList = "action_type"),
        @Index(name = "idx_admin_actions_created_at", columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminAction {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Admin who performed the action.
     */
    @Column(name = "admin_user_id", nullable = false)
    private Long adminUserId;
    
    @Column(name = "admin_username", nullable = false, length = 50)
    private String adminUsername;
    
    /**
     * Type of action performed.
     * Examples: BAN_USER, UNBAN_USER, CHANGE_ROLE, DELETE_USER, 
     *          EDIT_ELO, FORCE_LOGOUT, RESET_PASSWORD
     */
    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType;
    
    /**
     * User who was affected by the action (if applicable).
     * NULL for system-wide actions like viewing metrics.
     */
    @Column(name = "target_user_id")
    private Long targetUserId;
    
    @Column(name = "target_username", length = 50)
    private String targetUsername;
    
    /**
     * JSON details of the action.
     * Example: {"reason": "Cheating detected", "duration_days": 7, "old_elo": 1500, "new_elo": 1000}
     */
    @Column(name = "details", columnDefinition = "TEXT")
    private String details;
    
    /**
     * IP address of the admin performing the action.
     */
    @Column(name = "ip_address", length = 50)
    private String ipAddress;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
    
    // ── Helper Methods ────────────────────────────────────────────────────────
    
    /**
     * Create an admin action for banning a user.
     */
    public static AdminAction banUser(Long adminId, String adminUsername, Long targetId, 
                                       String targetUsername, String reason, String ipAddress) {
        return AdminAction.builder()
                .adminUserId(adminId)
                .adminUsername(adminUsername)
                .actionType("BAN_USER")
                .targetUserId(targetId)
                .targetUsername(targetUsername)
                .details(String.format("{\"reason\": \"%s\"}", reason))
                .ipAddress(ipAddress)
                .build();
    }
    
    /**
     * Create an admin action for changing a user's role.
     */
    public static AdminAction changeRole(Long adminId, String adminUsername, Long targetId,
                                          String targetUsername, String oldRole, String newRole,
                                          String ipAddress) {
        return AdminAction.builder()
                .adminUserId(adminId)
                .adminUsername(adminUsername)
                .actionType("CHANGE_ROLE")
                .targetUserId(targetId)
                .targetUsername(targetUsername)
                .details(String.format("{\"old_role\": \"%s\", \"new_role\": \"%s\"}", oldRole, newRole))
                .ipAddress(ipAddress)
                .build();
    }
    
    /**
     * Create an admin action for editing ELO.
     */
    public static AdminAction editElo(Long adminId, String adminUsername, Long targetId,
                                       String targetUsername, int oldElo, int newElo,
                                       String reason, String ipAddress) {
        return AdminAction.builder()
                .adminUserId(adminId)
                .adminUsername(adminUsername)
                .actionType("EDIT_ELO")
                .targetUserId(targetId)
                .targetUsername(targetUsername)
                .details(String.format("{\"old_elo\": %d, \"new_elo\": %d, \"reason\": \"%s\"}", 
                        oldElo, newElo, reason))
                .ipAddress(ipAddress)
                .build();
    }
}
