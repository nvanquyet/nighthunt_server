package com.nighthunt.ratelimit.repository;

import com.nighthunt.ratelimit.entity.RateLimitRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RateLimitRuleRepository extends JpaRepository<RateLimitRule, Long> {
    List<RateLimitRule> findByIsActiveTrue();
    
    RateLimitRule findByEndpointPatternAndIsActiveTrue(String endpointPattern);
}

