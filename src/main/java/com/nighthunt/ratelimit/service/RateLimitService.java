package com.nighthunt.ratelimit.service;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.ratelimit.entity.RateLimitRule;
import com.nighthunt.ratelimit.entity.RateLimitTracking;
import com.nighthunt.ratelimit.entity.RateLimitTokenBucket;
import com.nighthunt.ratelimit.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Rate limiting service
 * Implements rate limiting using fixed window, sliding window, or token bucket
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {
    
    private final RateLimitRuleRepository rateLimitRuleRepository;
    private final RateLimitTrackingRepository rateLimitTrackingRepository;
    private final RateLimitTokenBucketRepository rateLimitTokenBucketRepository;
    
    /**
     * Check rate limit for endpoint
     * @param endpoint Request endpoint
     * @param method HTTP method
     * @param identifier User ID, IP address, or "global"
     * @return true if allowed, false if rate limited
     */
    @Transactional
    public void checkRateLimit(String endpoint, String method, String identifier) {
        // Find matching rate limit rule
        List<RateLimitRule> rules = rateLimitRuleRepository.findByIsActiveTrue();
        
        for (RateLimitRule rule : rules) {
            if (rule.matchesEndpoint(endpoint, method)) {
                String scopeIdentifier = getScopeIdentifier(rule.getScope(), identifier);
                
                switch (rule.getLimitType()) {
                    case FIXED_WINDOW:
                        checkFixedWindow(rule, scopeIdentifier);
                        break;
                    case SLIDING_WINDOW:
                        checkSlidingWindow(rule, scopeIdentifier);
                        break;
                    case TOKEN_BUCKET:
                        checkTokenBucket(rule, scopeIdentifier);
                        break;
                }
                return; // Only apply first matching rule
            }
        }
    }
    
    /**
     * Check fixed window rate limit
     */
    private void checkFixedWindow(RateLimitRule rule, String identifier) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusSeconds(rule.getWindowSeconds());
        LocalDateTime windowEnd = now.plusSeconds(rule.getWindowSeconds());
        
        Optional<RateLimitTracking> tracking = rateLimitTrackingRepository
                .findFirstByRuleIdAndIdentifierOrderByWindowEndDesc(rule.getId(), identifier);
        
        if (tracking.isPresent()) {
            RateLimitTracking track = tracking.get();
            
            // Check if window expired
            if (track.getWindowEnd().isBefore(now)) {
                // Reset window
                track.setRequestCount(1);
                track.setWindowStart(now);
                track.setWindowEnd(windowEnd);
                track.setLastRequestAt(now);
            } else {
                // Increment count
                track.setRequestCount(track.getRequestCount() + 1);
                track.setLastRequestAt(now);
                
                // Check limit
                if (track.getRequestCount() > rule.getMaxRequests()) {
                    throw new BusinessException(ErrorCodes.RATE_LIMIT_EXCEEDED,
                            String.format("Rate limit exceeded. Maximum %d requests per %d seconds.",
                                    rule.getMaxRequests(), rule.getWindowSeconds()));
                }
            }
            
            rateLimitTrackingRepository.save(track);
        } else {
            // Create new tracking
            RateLimitTracking newTracking = RateLimitTracking.builder()
                    .ruleId(rule.getId())
                    .identifier(identifier)
                    .requestCount(1)
                    .windowStart(now)
                    .windowEnd(windowEnd)
                    .lastRequestAt(now)
                    .build();
            rateLimitTrackingRepository.save(newTracking);
        }
    }
    
    /**
     * Check sliding window rate limit (simplified - uses fixed window for now)
     * Can be enhanced with more sophisticated sliding window algorithm
     */
    private void checkSlidingWindow(RateLimitRule rule, String identifier) {
        // For simplicity, use fixed window implementation
        // Can be enhanced with proper sliding window later
        checkFixedWindow(rule, identifier);
    }
    
    /**
     * Check token bucket rate limit
     */
    private void checkTokenBucket(RateLimitRule rule, String identifier) {
        LocalDateTime now = LocalDateTime.now();
        
        Optional<RateLimitTokenBucket> bucketOpt = rateLimitTokenBucketRepository
                .findFirstByRuleIdAndIdentifier(rule.getId(), identifier);
        
        RateLimitTokenBucket bucket;
        
        if (bucketOpt.isPresent()) {
            bucket = bucketOpt.get();
            
            // Refill tokens based on time passed
            long secondsPassed = java.time.Duration.between(bucket.getLastRefillAt(), now).getSeconds();
            if (secondsPassed > 0 && rule.getRefillRate() != null) {
                int tokensToAdd = (int) (secondsPassed * rule.getRefillRate());
                bucket.setTokens(Math.min(bucket.getTokens() + tokensToAdd, rule.getBucketSize()));
                bucket.setLastRefillAt(now);
            }
        } else {
            // Create new bucket with full tokens
            bucket = RateLimitTokenBucket.builder()
                    .ruleId(rule.getId())
                    .identifier(identifier)
                    .tokens(rule.getBucketSize() != null ? rule.getBucketSize() : rule.getMaxRequests())
                    .lastRefillAt(now)
                    .build();
        }
        
        // Check if has tokens
        if (bucket.getTokens() <= 0) {
            throw new BusinessException(ErrorCodes.RATE_LIMIT_EXCEEDED,
                    String.format("Rate limit exceeded. Please wait before making more requests."));
        }
        
        // Consume token
        bucket.setTokens(bucket.getTokens() - 1);
        rateLimitTokenBucketRepository.save(bucket);
    }
    
    /**
     * Get identifier based on scope
     */
    private String getScopeIdentifier(RateLimitRule.Scope scope, String identifier) {
        switch (scope) {
            case USER:
                return identifier; // Use user ID
            case IP:
                return identifier; // Use IP address (should be passed separately)
            case GLOBAL:
                return "global"; // Global rate limit
            default:
                return identifier;
        }
    }
    
    /**
     * Cleanup expired rate limit tracking (scheduled task)
     */
    @Scheduled(fixedDelayString = "${ratelimit.cleanup.interval:3600000}") // Default 1 hour
    @Transactional
    public void cleanupExpiredTracking() {
        LocalDateTime cutoffTime = LocalDateTime.now().minusHours(1);
        rateLimitTrackingRepository.deleteExpiredTracking(cutoffTime);
        rateLimitTokenBucketRepository.deleteOldBuckets(cutoffTime);
    }
}

