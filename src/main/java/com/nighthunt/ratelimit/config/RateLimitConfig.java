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
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/actuator/**", "/error", "/ws/**", "/dashboard/**")
                .order(1);
    }
}

