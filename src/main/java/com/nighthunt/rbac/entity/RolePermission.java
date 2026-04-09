package com.nighthunt.rbac.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Role Permission Entity - Maps roles to specific permissions.
 * 
 * Allows fine-grained access control beyond simple role hierarchy.
 * Permissions can be dynamically adjusted without code changes.
 */
@Entity
@Table(name = "role_permissions", 
       uniqueConstraints = @UniqueConstraint(name = "idx_role_permission", columnNames = {"role", "permission"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RolePermission {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Role name: USER, SUPPORT, MODERATOR, ADMIN
     */
    @Column(name = "role", nullable = false, length = 20)
    private String role;
    
    /**
     * Permission name (matches Permission enum).
     * Examples: VIEW_DASHBOARD, BAN_USER, MANAGE_ROLES, etc.
     */
    @Column(name = "permission", nullable = false, length = 100)
    private String permission;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
