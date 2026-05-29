package com.nighthunt.ratelimit.config;

import com.nighthunt.queue.interceptor.RequestQueueInterceptor;
import com.nighthunt.ratelimit.interceptor.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Rate limiting and request queue configuration
 * Registers rate limit and queue interceptors
 */
@Configuration
@RequiredArgsConstructor
public class RateLimitConfig implements WebMvcConfigurer {
    
    private final RateLimitInterceptor rateLimitInterceptor;
    private final RequestQueueInterceptor requestQueueInterceptor;
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Request queue interceptor runs first (to queue if needed)
        registry.addInterceptor(requestQueueInterceptor)
                .addPathPatterns("/api/**", "/auth/**", "/rooms/**")
                .excludePathPatterns("/actuator/**", "/error", "/ws/**", "/dashboard/**")
                .order(0);
        
        // Rate limit interceptor runs second (to check rate limits)
        // Exclude admin endpoints — they use X-Admin-Secret auth, not JWT,
        // and the rate-limit toggle endpoint must never be blocked by rate limiting itself.
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/actuator/**", "/error", "/ws/**", "/dashboard/**", "/admin/**")
                .order(1);
    }
}

