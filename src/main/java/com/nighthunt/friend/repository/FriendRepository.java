package com.nighthunt.friend.repository;

import com.nighthunt.friend.entity.Friend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FriendRepository extends JpaRepository<Friend, Long> {
    
    /**
     * Find all friends of a user (returns list of friend_user_id).
     */
    List<Friend> findByUserId(Long userId);

    /**
     * Find friendship record between two users (directed).
     */
    Optional<Friend> findByUserIdAndFriendUserId(Long userId, Long friendUserId);

    /**
     * Check if two users are friends (directed check).
     */
    boolean existsByUserIdAndFriendUserId(Long userId, Long friendUserId);

    /**
     * Delete friendship record (directed).
     */
    void deleteByUserIdAndFriendUserId(Long userId, Long friendUserId);

    /**
     * Count total friends of a user.
     */
    long countByUserId(Long userId);

    /**
     * Find all friends with specific status (ACTIVE, BLOCKED).
     */
    List<Friend> findByUserIdAndFriendshipStatus(Long userId, String status);

    /**
     * Get friend IDs only (efficient query for checking blockage).
     */
    @Query("SELECT f.friendUserId FROM Friend f WHERE f.userId = :userId")
    List<Long> findFriendIdsByUserId(@Param("userId") Long userId);
}
