package com.nighthunt.friend.repository;

import com.nighthunt.friend.entity.BlockedUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BlockedUserRepository extends JpaRepository<BlockedUser, Long> {
    
    /**
     * Find blocked user record.
     */
    Optional<BlockedUser> findByBlockerUserIdAndBlockedUserId(Long blockerId, Long blockedId);

    /**
     * Check if user A blocked user B.
     */
    boolean existsByBlockerUserIdAndBlockedUserId(Long blockerId, Long blockedId);

    /**
     * Get all users blocked by a user.
     */
    List<BlockedUser> findByBlockerUserId(Long blockerId);

    /**
     * Get all users who blocked a specific user (for reverse check).
     */
    List<BlockedUser> findByBlockedUserId(Long blockedId);

    /**
     * Delete blocked user record (unblock).
     */
    void deleteByBlockerUserIdAndBlockedUserId(Long blockerId, Long blockedId);

    /**
     * Count total blocked users by a user.
     */
    long countByBlockerUserId(Long blockerId);

    /**
     * Get IDs of all users blocked by a user (efficient query).
     */
    @Query("SELECT bu.blockedUserId FROM BlockedUser bu WHERE bu.blockerUserId = :blockerId")
    List<Long> findBlockedUserIdsByBlockerId(@Param("blockerId") Long blockerId);
}
