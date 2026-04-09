package com.nighthunt.party.repository;

import com.nighthunt.party.entity.PartyMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PartyMemberRepository extends JpaRepository<PartyMember, Long> {
    
    /**
     * Find all members of a party (ordered by join_order).
     */
    List<PartyMember> findByPartyIdOrderByJoinOrderAsc(Long partyId);

    /**
     * Find party member record by party ID and user ID.
     */
    Optional<PartyMember> findByPartyIdAndUserId(Long partyId, Long userId);

    /**
     * Find party that a user is currently in.
     */
    Optional<PartyMember> findByUserId(Long userId);

    /**
     * Check if user is in a party.
     */
    boolean existsByUserId(Long userId);

    /**
     * Check if user is in a specific party.
     */
    boolean existsByPartyIdAndUserId(Long partyId, Long userId);

    /**
     * Count members in a party.
     */
    long countByPartyId(Long partyId);

    /**
     * Delete party member (when leaving or kicked).
     */
    void deleteByPartyIdAndUserId(Long partyId, Long userId);

    /**
     * Delete all members of a party (when party is disbanded).
     */
    void deleteByPartyId(Long partyId);

    /**
     * Get user IDs of all members in a party (efficient query).
     */
    @Query("SELECT pm.userId FROM PartyMember pm WHERE pm.partyId = :partyId ORDER BY pm.joinOrder ASC")
    List<Long> findUserIdsByPartyId(@Param("partyId") Long partyId);
}
