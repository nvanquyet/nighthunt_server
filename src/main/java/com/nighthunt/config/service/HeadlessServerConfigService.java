package com.nighthunt.config.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * HeadlessServerConfigService - DISABLED
 * Headless server functionality has been disabled.
 * This service remains for backward compatibility but all methods return stub values.
 */
@Slf4j
@Service
public class HeadlessServerConfigService {
    
    // Config keys (kept for reference, not used)
    public static final String CONFIG_BUILD_PATH = "build.path";
    public static final String CONFIG_DEFAULT_VERSION = "default.version";
    public static final String CONFIG_LOG_PATH = "log.path";
    public static final String CONFIG_AUTO_SCALING_ENABLED = "auto-scaling.enabled";
    public static final String CONFIG_SCALE_UP_THRESHOLD = "scale-up.threshold";
    public static final String CONFIG_SCALE_DOWN_THRESHOLD = "scale-down.threshold";
    public static final String CONFIG_IDLE_TIMEOUT_MINUTES = "idle-timeout.minutes";
    public static final String CONFIG_MAX_SERVERS = "max-servers";
    public static final String CONFIG_DEFAULT_IP = "default.ip";
    public static final String CONFIG_BASE_PORT = "base-port";
    public static final String CONFIG_MAX_MATCHES_PER_SERVER = "max-matches-per-server";
    
    public String getConfigValue(String configKey, String defaultValue) {
        log.debug("Headless server disabled - returning default value for {}", configKey);
        return defaultValue;
    }
    
    public int getConfigValueAsInt(String configKey, int defaultValue) {
        return defaultValue;
    }
    
    public double getConfigValueAsDouble(String configKey, double defaultValue) {
        return defaultValue;
    }
    
    public boolean getConfigValueAsBoolean(String configKey, boolean defaultValue) {
        return defaultValue;
    }
    
    public void setConfigValue(String configKey, String configValue, String description) {
        log.warn("Headless server disabled - setConfigValue is no-op");
    }
    
    public void initializeDefaultConfigs() {
        log.debug("Headless server disabled - initializeDefaultConfigs is no-op");
    }
}
