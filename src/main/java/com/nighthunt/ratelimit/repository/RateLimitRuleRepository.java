package com.nighthunt.ratelimit.repository;

import com.nighthunt.ratelimit.entity.RateLimitRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RateLimitRuleRepository extends JpaRepository<RateLimitRule, Long> {
    /**
     * Return active rules ordered by pattern length DESC so that more specific patterns
     * (e.g. /auth/login) are evaluated before catch-all wildcards (e.g. /*).
     */
    @Query("SELECT r FROM RateLimitRule r WHERE r.isActive = true ORDER BY LENGTH(r.endpointPattern) DESC")
    List<RateLimitRule> findByIsActiveTrue();
    
    RateLimitRule findByEndpointPatternAndIsActiveTrue(String endpointPattern);
}

