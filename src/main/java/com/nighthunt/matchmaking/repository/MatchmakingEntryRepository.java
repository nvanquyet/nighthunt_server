package com.nighthunt.matchmaking.repository;

import com.nighthunt.matchmaking.entity.MatchmakingEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
    @Modifying
    @Query("DELETE FROM MatchmakingEntry m WHERE m.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    /**
     * Returns true if the user is currently in the ranked matchmaking queue
     * (status SEARCHING or MATCHED). Used to prevent joining a custom lobby
     * while queued for ranked.
     */
    @Query("SELECT COUNT(m) > 0 FROM MatchmakingEntry m WHERE m.userId = :userId AND m.status IN ('SEARCHING', 'MATCHED')")
    boolean existsActiveEntryForUser(@Param("userId") Long userId);

    /** Total number of entries currently in SEARCHING state. */
    @Query("SELECT COUNT(m) FROM MatchmakingEntry m WHERE m.status = 'SEARCHING'")
    long countSearching();

    /** Count of SEARCHING entries grouped by gameMode — returns [gameMode, count] pairs. */
    @Query("SELECT m.gameMode, COUNT(m) FROM MatchmakingEntry m WHERE m.status = 'SEARCHING' GROUP BY m.gameMode")
    List<Object[]> countSearchingByMode();
}
