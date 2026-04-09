package com.nighthunt.match.repository;

import com.nighthunt.match.entity.Match;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MatchRepository extends JpaRepository<Match, Long> {
    Optional<Match> findByMatchId(String matchId);
    Optional<Match> findByRoomId(Long roomId);
    List<Match> findByStatus(String status);

    // Admin queries
    long countByCreatedAtAfter(LocalDateTime after);

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

