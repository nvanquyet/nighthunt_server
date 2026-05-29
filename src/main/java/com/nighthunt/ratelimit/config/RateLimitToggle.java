package com.nighthunt.ratelimit.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runtime toggle for rate limiting.
 * Controlled at startup via RATE_LIMIT_ENABLED env var,
 * and at runtime via POST /api/admin/rate-limit/toggle.
 *
 * Example (disable for load testing):
 *   RATE_LIMIT_ENABLED=false  (docker-compose env / .env file)
 */
@Component
public class RateLimitToggle {

    private static final Logger log = LoggerFactory.getLogger(RateLimitToggle.class);

    private volatile boolean enabled;

    @Value("${app.rate-limit.enabled:true}")
    public void init(boolean enabled) {
        this.enabled = enabled;
        log.info("[RateLimit] Rate limiting {} at startup (RATE_LIMIT_ENABLED env)",
                enabled ? "ENABLED" : "DISABLED");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("[RateLimit] Rate limiting toggled {} at runtime", enabled ? "ENABLED" : "DISABLED");
    }
}

