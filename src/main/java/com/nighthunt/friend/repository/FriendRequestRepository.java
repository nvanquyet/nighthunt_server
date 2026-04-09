package com.nighthunt.friend.repository;

import com.nighthunt.friend.entity.FriendRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface FriendRequestRepository extends JpaRepository<FriendRequest, Long> {
    
    /**
     * Find friend request between two users.
     */
    Optional<FriendRequest> findByRequesterUserIdAndAddresseeUserId(Long requesterId, Long addresseeId);

    /**
     * Check if friend request exists between two users.
     */
    boolean existsByRequesterUserIdAndAddresseeUserId(Long requesterId, Long addresseeId);

    /**
     * Find all incoming friend requests for a user (pending only).
     */
    List<FriendRequest> findByAddresseeUserIdAndRequestStatus(Long addresseeId, String status);

    /**
     * Find all outgoing friend requests from a user (pending only).
     */
    List<FriendRequest> findByRequesterUserIdAndRequestStatus(Long requesterId, String status);

    /**
     * Count pending incoming requests for a user.
     */
    long countByAddresseeUserIdAndRequestStatus(Long addresseeId, String status);

    /**
     * Find all expired friend requests (for cleanup job).
     */
    @Query("SELECT fr FROM FriendRequest fr WHERE fr.expiresAt IS NOT NULL AND fr.expiresAt < :now AND fr.requestStatus = 'PENDING'")
    List<FriendRequest> findExpiredRequests(@Param("now") LocalDateTime now);

    /**
     * Delete friend request (used when cancelled or cleaned up).
     */
    void deleteByRequesterUserIdAndAddresseeUserId(Long requesterId, Long addresseeId);
}
