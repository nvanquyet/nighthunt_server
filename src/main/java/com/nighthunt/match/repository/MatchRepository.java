package com.nighthunt.match.repository;

import com.nighthunt.match.entity.Match;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MatchRepository extends JpaRepository<Match, Long> {
    Optional<Match> findByMatchId(String matchId);
    Optional<Match> findByRoomId(Long roomId);
    List<Match> findByStatus(String status);

    /**
     * SELECT ... FOR UPDATE — prevents concurrent processMatchEnd() calls from
     * both passing the FINISHED-status guard and double-writing ELO/coins.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Match m WHERE m.matchId = :matchId")
    Optional<Match> findByMatchIdForUpdate(@Param("matchId") String matchId);

    // Admin queries
    long countByCreatedAtAfter(LocalDateTime after);

    /** Count matches that finished after the given time (for rolling-window metrics). */
    long countByFinishedAtAfter(LocalDateTime after);

    /** Count finished matches grouped by gameMode — returns [gameMode, count] pairs. */
    @Query("""
        SELECT m.gameMode, COUNT(m) FROM Match m
        WHERE m.status = 'FINISHED' AND m.finishedAt >= :after
        GROUP BY m.gameMode
        """)
    List<Object[]> countFinishedByModeAfter(@Param("after") LocalDateTime after);

    @Query("""
        SELECT m FROM Match m
        WHERE (:status IS NULL OR m.status = :status)
          AND (:mode   IS NULL OR m.gameMode = :mode)
        ORDER BY m.createdAt DESC
        """)
    Page<Match> findFiltered(
        @Param("status") String status,
        @Param("mode")   String mode,
        Pageable pageable
    );
}

