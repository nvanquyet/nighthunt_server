package com.nighthunt.analytics.repository;

import com.nighthunt.analytics.entity.ServerMetricsSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ServerMetricsSnapshotRepository extends JpaRepository<ServerMetricsSnapshot, Long> {

    /** Return snapshots in [from, to] ordered ascending by time. */
    List<ServerMetricsSnapshot> findBySnapshotAtBetweenOrderBySnapshotAtAsc(
            LocalDateTime from, LocalDateTime to);

    /** Return the last N snapshots ordered descending (most recent first). */
    @Query("""
            SELECT s FROM ServerMetricsSnapshot s
            ORDER BY s.snapshotAt DESC
            LIMIT :n
            """)
    List<ServerMetricsSnapshot> findLatestN(@Param("n") int n);

    /** Delete snapshots older than a given cutoff (housekeeping). */
    void deleteBySnapshotAtBefore(LocalDateTime cutoff);
}
