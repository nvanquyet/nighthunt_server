package com.nighthunt.realtime.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;

public interface RealtimeOutboxRepository extends JpaRepository<RealtimeOutboxEvent, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select event from RealtimeOutboxEvent event
            where event.status = com.nighthunt.realtime.outbox.RealtimeOutboxStatus.PENDING
              and event.availableAt <= :now
            order by event.id
            """)
    List<RealtimeOutboxEvent> findReadyForPublish(@Param("now") LocalDateTime now, Pageable pageable);
}
