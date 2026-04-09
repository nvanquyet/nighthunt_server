package com.nighthunt.party.repository;

import com.nighthunt.party.entity.PartyInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PartyInvitationRepository extends JpaRepository<PartyInvitation, Long> {
    
    /**
     * Find all incoming party invitations for a user (pending only).
     */
    List<PartyInvitation> findByInviteeUserIdAndInvitationStatus(Long inviteeId, String status);

    /**
     * Find all pending invitations for a specific party.
     */
    List<PartyInvitation> findByPartyIdAndInvitationStatus(Long partyId, String status);

    /**
     * Find specific invitation by party and invitee.
     */
    Optional<PartyInvitation> findByPartyIdAndInviteeUserId(Long partyId, Long inviteeId);

    /**
     * Check if user has pending invitation to a party.
     */
    @Query("SELECT COUNT(pi) > 0 FROM PartyInvitation pi WHERE pi.partyId = :partyId AND pi.inviteeUserId = :inviteeId AND pi.invitationStatus = 'PENDING'")
    boolean hasPendingInvitation(@Param("partyId") Long partyId, @Param("inviteeId") Long inviteeId);

    /**
     * Count pending incoming invitations for a user.
     */
    long countByInviteeUserIdAndInvitationStatus(Long inviteeId, String status);

    /**
     * Find all expired invitations (for cleanup job).
     */
    @Query("SELECT pi FROM PartyInvitation pi WHERE pi.expiresAt < :now AND pi.invitationStatus = 'PENDING'")
    List<PartyInvitation> findExpiredInvitations(@Param("now") LocalDateTime now);

    /**
     * Delete all invitations for a party (when party is disbanded).
     */
    void deleteByPartyId(Long partyId);

    /**
     * Delete specific invitation (when cancelled or expired).
     */
    void deleteByPartyIdAndInviteeUserId(Long partyId, Long inviteeId);
}
