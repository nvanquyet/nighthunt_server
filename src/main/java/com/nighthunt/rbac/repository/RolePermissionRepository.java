package com.nighthunt.rbac.repository;

import com.nighthunt.rbac.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for RolePermission entities - maps roles to permissions.
 */
@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {
    
    /**
     * Find all permissions for a specific role.
     */
    List<RolePermission> findByRole(String role);
    
    /**
     * Check if a role has a specific permission.
     */
    @Query("SELECT CASE WHEN COUNT(rp) > 0 THEN true ELSE false END FROM RolePermission rp WHERE rp.role = :role AND rp.permission = :permission")
    boolean hasPermission(@Param("role") String role, @Param("permission") String permission);
    
    /**
     * Find a specific role-permission mapping.
     */
    Optional<RolePermission> findByRoleAndPermission(String role, String permission);
    
    /**
     * Get all permissions across all roles (for admin UI).
     */
    @Query("SELECT DISTINCT rp.permission FROM RolePermission rp ORDER BY rp.permission")
    List<String> findAllDistinctPermissions();
    
    /**
     * Get all roles that have a specific permission.
     */
    @Query("SELECT rp.role FROM RolePermission rp WHERE rp.permission = :permission")
    List<String> findRolesByPermission(@Param("permission") String permission);
}
