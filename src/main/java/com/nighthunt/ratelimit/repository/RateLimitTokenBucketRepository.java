package com.nighthunt.ratelimit.repository;

import com.nighthunt.ratelimit.entity.RateLimitTokenBucket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface RateLimitTokenBucketRepository extends JpaRepository<RateLimitTokenBucket, Long> {
    
    Optional<RateLimitTokenBucket> findByRuleIdAndIdentifier(Long ruleId, String identifier);
    
    @Modifying
    @Query("DELETE FROM RateLimitTokenBucket t WHERE t.lastRefillAt < :cutoffTime")
    void deleteOldBuckets(@Param("cutoffTime") LocalDateTime cutoffTime);
}

