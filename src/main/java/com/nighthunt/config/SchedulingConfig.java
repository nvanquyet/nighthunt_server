package com.nighthunt.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SchedulingConfig - Enabled for room cleanup tasks
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
    // Scheduling enabled for room cleanup service
}
