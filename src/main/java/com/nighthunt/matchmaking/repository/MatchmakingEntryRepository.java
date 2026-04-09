package com.nighthunt.matchmaking.repository;

import com.nighthunt.matchmaking.entity.MatchmakingEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MatchmakingEntryRepository extends JpaRepository<MatchmakingEntry, Long> {

    Optional<MatchmakingEntry> findByUserId(Long userId);

    /** All SEARCHING entries for a given game mode, oldest first. */
    @Query("""
            SELECT m FROM MatchmakingEntry m
            WHERE m.status   = 'SEARCHING'
              AND m.gameMode = :mode
            ORDER BY m.queuedAt ASC
            """)
    List<MatchmakingEntry> findSearchingByMode(@Param("mode") String mode);

    /** All entries sharing the same lobby token. */
    List<MatchmakingEntry> findByLobbyToken(String lobbyToken);

    /** Delete any existing entry for a user (re-queue clean-up). */
    void deleteByUserId(Long userId);
}
