package com.nighthunt.rbac.repository;

import com.nighthunt.rbac.entity.AdminAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for AdminAction entities - audit trail of administrative actions.
 */
@Repository
public interface AdminActionRepository extends JpaRepository<AdminAction, Long> {
    
    /**
     * Find all actions performed by a specific admin.
     */
    Page<AdminAction> findByAdminUserIdOrderByCreatedAtDesc(Long adminUserId, Pageable pageable);
    
    /**
     * Find all actions targeting a specific user.
     */
    Page<AdminAction> findByTargetUserIdOrderByCreatedAtDesc(Long targetUserId, Pageable pageable);
    
    /**
     * Find all actions by action type.
     */
    Page<AdminAction> findByActionTypeOrderByCreatedAtDesc(String actionType, Pageable pageable);
    
    /**
     * Find all actions within a date range.
     */
    @Query("SELECT a FROM AdminAction a WHERE a.createdAt BETWEEN :startDate AND :endDate ORDER BY a.createdAt DESC")
    Page<AdminAction> findByDateRange(@Param("startDate") LocalDateTime startDate, 
                                       @Param("endDate") LocalDateTime endDate, 
                                       Pageable pageable);
    
    /**
     * Find recent actions (last N hours).
     */
    @Query("SELECT a FROM AdminAction a WHERE a.createdAt >= :sinceDate ORDER BY a.createdAt DESC")
    List<AdminAction> findRecentActions(@Param("sinceDate") LocalDateTime sinceDate);
    
    /**
     * Count total actions by admin.
     */
    long countByAdminUserId(Long adminUserId);
    
    /**
     * Count actions by type in date range.
     */
    @Query("SELECT COUNT(a) FROM AdminAction a WHERE a.actionType = :actionType AND a.createdAt BETWEEN :startDate AND :endDate")
    long countByActionTypeAndDateRange(@Param("actionType") String actionType,
                                        @Param("startDate") LocalDateTime startDate,
                                        @Param("endDate") LocalDateTime endDate);
}
