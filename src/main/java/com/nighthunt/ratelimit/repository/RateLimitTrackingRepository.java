package com.nighthunt.ratelimit.repository;

import com.nighthunt.ratelimit.entity.RateLimitTracking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface RateLimitTrackingRepository extends JpaRepository<RateLimitTracking, Long> {
    
    Optional<RateLimitTracking> findFirstByRuleIdAndIdentifierOrderByWindowEndDesc(Long ruleId, String identifier);
    
    @Modifying
    @Query("DELETE FROM RateLimitTracking r WHERE r.windowEnd < :cutoffTime")
    void deleteExpiredTracking(@Param("cutoffTime") LocalDateTime cutoffTime);
}

