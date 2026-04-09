package com.nighthunt.admin.repository;

import com.nighthunt.admin.entity.UserActivityLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface UserActivityLogRepository extends JpaRepository<UserActivityLog, Long> {

    Page<UserActivityLog> findByUserId(Long userId, Pageable pageable);

    List<UserActivityLog> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);

    // All logs for root admin view (capped at 200)
    List<UserActivityLog> findTop200ByUserIdOrderByCreatedAtDesc(Long userId);

    List<UserActivityLog> findTop30ByOrderByCreatedAtDesc();

    @Query("""
        SELECT l FROM UserActivityLog l
        WHERE (:userId IS NULL OR l.userId = :userId)
          AND (:eventType IS NULL OR l.eventType = :eventType)
          AND (:from IS NULL OR l.createdAt >= :from)
          AND (:to   IS NULL OR l.createdAt <= :to)
        ORDER BY l.createdAt DESC
        """)
    Page<UserActivityLog> findFiltered(
            @Param("userId")    Long userId,
            @Param("eventType") String eventType,
            @Param("from")      LocalDateTime from,
            @Param("to")        LocalDateTime to,
            Pageable pageable
    );

    long countByCreatedAtAfter(LocalDateTime after);
}
