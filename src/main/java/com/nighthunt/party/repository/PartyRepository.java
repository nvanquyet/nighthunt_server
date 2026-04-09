package com.nighthunt.party.repository;

import com.nighthunt.party.entity.Party;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PartyRepository extends JpaRepository<Party, Long> {
    
    /**
     * Find party by host user ID.
     */
    Optional<Party> findByHostUserId(Long hostUserId);

    /**
     * Find all parties in specific status (IDLE, IN_QUEUE, IN_ROOM, IN_GAME).
     */
    List<Party> findByPartyStatus(String status);

    /**
     * Find parties in a specific room.
     */
    List<Party> findByCurrentRoomId(Long roomId);

    /**
     * Count parties in specific status.
     */
    long countByPartyStatus(String status);

    /**
     * Find parties that are not disbanded (for cleanup).
     */
    List<Party> findByPartyStatusNot(String status);

    /**
     * Check if user is host of any active party.
     */
    @Query("SELECT COUNT(p) > 0 FROM Party p WHERE p.hostUserId = :userId AND p.partyStatus != 'DISBANDED'")
    boolean isUserHostOfActiveParty(@Param("userId") Long userId);
}
