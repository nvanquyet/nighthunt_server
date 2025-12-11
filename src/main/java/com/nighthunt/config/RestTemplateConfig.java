package com.nighthunt.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplateConfig - DISABLED
 * RestTemplate is no longer needed as headless server functionality has been disabled.
 * This config remains for backward compatibility but RestTemplate is not actively used.
 */
@Configuration
public class RestTemplateConfig {
    @Bean
    public RestTemplate restTemplate() {
        // RestTemplate still created for backward compatibility
        // but not actively used since headless server is disabled
        return new RestTemplate();
    }
}
