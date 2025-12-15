package com.nighthunt.room.repository;

import com.nighthunt.room.entity.SwapRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SwapRequestRepository extends JpaRepository<SwapRequest, Long> {
    // Find pending swap requests for a user
    @Query("SELECT sr FROM SwapRequest sr WHERE sr.targetId = :userId AND sr.status = 'PENDING' AND sr.expiresAt > CURRENT_TIMESTAMP")
    List<SwapRequest> findPendingRequestsForUser(@Param("userId") Long userId);

    // Find pending swap request between two users in a room
    @Query("SELECT sr FROM SwapRequest sr WHERE sr.roomId = :roomId AND sr.requesterId = :requesterId AND sr.targetId = :targetId AND sr.status = 'PENDING'")
    Optional<SwapRequest> findPendingRequest(@Param("roomId") Long roomId, 
                                             @Param("requesterId") Long requesterId, 
                                             @Param("targetId") Long targetId);

    // Find all pending requests in a room
    @Query("SELECT sr FROM SwapRequest sr WHERE sr.roomId = :roomId AND sr.status = 'PENDING'")
    List<SwapRequest> findPendingRequestsInRoom(@Param("roomId") Long roomId);

    // Find expired pending requests
    @Query("SELECT sr FROM SwapRequest sr WHERE sr.status = 'PENDING' AND sr.expiresAt < :now")
    List<SwapRequest> findExpiredPending(@Param("now") LocalDateTime now);
}

