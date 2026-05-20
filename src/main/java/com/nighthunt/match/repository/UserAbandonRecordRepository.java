package com.nighthunt.match.repository;

import com.nighthunt.match.entity.UserAbandonRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface UserAbandonRecordRepository extends JpaRepository<UserAbandonRecord, Long> {

    List<UserAbandonRecord> findByUserId(Long userId);

    /** Count abandons for a user within the given time window (for repeat-offender check). */
    @Query("""
            SELECT COUNT(r) FROM UserAbandonRecord r
            WHERE r.userId = :userId
              AND r.recordedAt >= :since
            """)
    long countRecentAbandons(@Param("userId") Long userId,
                             @Param("since") LocalDateTime since);

    /** Find all abandon records for a specific match. */
    List<UserAbandonRecord> findByMatchId(String matchId);
}
