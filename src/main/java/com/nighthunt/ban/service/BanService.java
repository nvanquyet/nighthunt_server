package com.nighthunt.ban.service;

import com.nighthunt.ban.entity.Ban;
import com.nighthunt.ban.entity.BanConfig;
import com.nighthunt.ban.entity.ConcurrentLoginAttempt;
import com.nighthunt.ban.entity.FailedLoginAttempt;
import com.nighthunt.ban.repository.*;
import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Ban service
 * Handles ban logic, auto-ban, and auto-unban
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BanService {
    
    private final BanRepository banRepository;
    private final FailedLoginAttemptRepository failedLoginAttemptRepository;
    private final ConcurrentLoginAttemptRepository concurrentLoginAttemptRepository;
    private final BanConfigRepository banConfigRepository;
    
    // Config keys
    private static final String MAX_FAILED_LOGIN_ATTEMPTS = "MAX_FAILED_LOGIN_ATTEMPTS";
    private static final String FAILED_LOGIN_WINDOW_MINUTES = "FAILED_LOGIN_WINDOW_MINUTES";
    private static final String FAILED_LOGIN_BAN_DURATION_MINUTES = "FAILED_LOGIN_BAN_DURATION_MINUTES";
    private static final String MAX_CONCURRENT_LOGIN_ATTEMPTS = "MAX_CONCURRENT_LOGIN_ATTEMPTS";
    private static final String CONCURRENT_LOGIN_WINDOW_SECONDS = "CONCURRENT_LOGIN_WINDOW_SECONDS";
    private static final String CONCURRENT_LOGIN_BAN_DURATION_MINUTES = "CONCURRENT_LOGIN_BAN_DURATION_MINUTES";
    private static final String AUTO_UNBAN_ENABLED = "AUTO_UNBAN_ENABLED";
    
    /**
     * Check if user is banned
     */
    public void checkUserBan(Long userId) {
        Optional<Ban> ban = banRepository.findActiveBanByUserId(userId, LocalDateTime.now());
        if (ban.isPresent()) {
            Ban banRecord = ban.get();
            throw new BusinessException(ErrorCodes.AUTH_ACCOUNT_BANNED,
                    String.format("Tài khoản đã bị khóa. Lý do: %s. Hết hạn: %s",
                            banRecord.getReason(),
                            banRecord.getExpiresAt() != null ? banRecord.getExpiresAt().toString() : "Vĩnh viễn"));
        }
    }
    
    /**
     * Check if IP is banned
     */
    public void checkIpBan(String ipAddress) {
        Optional<Ban> ban = banRepository.findActiveBanByIpAddress(ipAddress, LocalDateTime.now());
        if (ban.isPresent()) {
            Ban banRecord = ban.get();
            throw new BusinessException(ErrorCodes.AUTH_IP_BANNED,
                    String.format("IP đã bị khóa. Lý do: %s. Hết hạn: %s",
                            banRecord.getReason(),
                            banRecord.getExpiresAt() != null ? banRecord.getExpiresAt().toString() : "Vĩnh viễn"));
        }
    }
    
    /**
     * Check if device is banned
     */
    public void checkDeviceBan(String deviceFingerprint) {
        if (deviceFingerprint == null || deviceFingerprint.isEmpty()) {
            return; // Skip if no fingerprint
        }
        
        Optional<Ban> ban = banRepository.findActiveBanByDeviceFingerprint(deviceFingerprint, LocalDateTime.now());
        if (ban.isPresent()) {
            Ban banRecord = ban.get();
            throw new BusinessException(ErrorCodes.AUTH_DEVICE_BANNED,
                    String.format("Thiết bị đã bị khóa. Lý do: %s. Hết hạn: %s",
                            banRecord.getReason(),
                            banRecord.getExpiresAt() != null ? banRecord.getExpiresAt().toString() : "Vĩnh viễn"));
        }
    }
    
    /**
     * Record failed login attempt and auto-ban if threshold exceeded
     */
    @Transactional
    public void recordFailedLoginAttempt(String identifier, String ipAddress, String deviceFingerprint) {
        // Get config
        int maxAttempts = getConfigInt(MAX_FAILED_LOGIN_ATTEMPTS, 5);
        int windowMinutes = getConfigInt(FAILED_LOGIN_WINDOW_MINUTES, 15);
        int banDurationMinutes = getConfigInt(FAILED_LOGIN_BAN_DURATION_MINUTES, 30);
        
        LocalDateTime windowStart = LocalDateTime.now().minusMinutes(windowMinutes);
        
        // Find or create failed login attempt record
        Optional<FailedLoginAttempt> existing = failedLoginAttemptRepository.findByIdentifierAndIpAddress(identifier, ipAddress);
        FailedLoginAttempt attempt;
        
        if (existing.isPresent()) {
            attempt = existing.get();
            // Check if within window
            if (attempt.getLastAttemptAt().isAfter(windowStart)) {
                attempt.setAttemptCount(attempt.getAttemptCount() + 1);
                attempt.setLastAttemptAt(LocalDateTime.now());
            } else {
                // Reset if outside window
                attempt.setAttemptCount(1);
                attempt.setFirstAttemptAt(LocalDateTime.now());
                attempt.setLastAttemptAt(LocalDateTime.now());
            }
        } else {
            attempt = FailedLoginAttempt.builder()
                    .identifier(identifier)
                    .ipAddress(ipAddress)
                    .deviceFingerprint(deviceFingerprint)
                    .attemptCount(1)
                    .firstAttemptAt(LocalDateTime.now())
                    .lastAttemptAt(LocalDateTime.now())
                    .build();
        }
        
        attempt = failedLoginAttemptRepository.save(attempt);
        
        // Check if should auto-ban
        if (attempt.getAttemptCount() >= maxAttempts && !attempt.getIsBanned()) {
            log.warn("Auto-banning due to failed login attempts: identifier={}, ip={}, attempts={}",
                    identifier, ipAddress, attempt.getAttemptCount());
            
            // Create ban
            Ban ban = Ban.builder()
                    .userId(null) // Will be set when user is found
                    .banType(Ban.BanType.IP)
                    .ipAddress(ipAddress)
                    .deviceFingerprint(deviceFingerprint)
                    .reason(String.format("Quá nhiều lần đăng nhập sai (%d lần trong %d phút)", 
                            attempt.getAttemptCount(), windowMinutes))
                    .banDurationMinutes(banDurationMinutes)
                    .bannedBy(null) // Auto-ban
                    .build();
            
            ban = banRepository.save(ban);
            
            // Mark attempt as banned
            attempt.setIsBanned(true);
            attempt.setBanId(ban.getId());
            failedLoginAttemptRepository.save(attempt);
        }
    }
    
    /**
     * Record concurrent login attempt and auto-ban if threshold exceeded
     */
    @Transactional
    public void recordConcurrentLoginAttempt(String ipAddress, String deviceFingerprint) {
        // Get config
        int maxAttempts = getConfigInt(MAX_CONCURRENT_LOGIN_ATTEMPTS, 10);
        int windowSeconds = getConfigInt(CONCURRENT_LOGIN_WINDOW_SECONDS, 60);
        int banDurationMinutes = getConfigInt(CONCURRENT_LOGIN_BAN_DURATION_MINUTES, 15);
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowEnd = now.plusSeconds(windowSeconds);
        
        // Find or create concurrent login attempt record
        Optional<ConcurrentLoginAttempt> existing = concurrentLoginAttemptRepository
                .findByIpAddressAndWindowEndAfter(ipAddress, now);
        
        ConcurrentLoginAttempt attempt;
        
        if (existing.isPresent()) {
            attempt = existing.get();
            attempt.setAttemptCount(attempt.getAttemptCount() + 1);
            attempt.setWindowEnd(windowEnd);
        } else {
            attempt = ConcurrentLoginAttempt.builder()
                    .ipAddress(ipAddress)
                    .deviceFingerprint(deviceFingerprint)
                    .attemptCount(1)
                    .windowStart(now)
                    .windowEnd(windowEnd)
                    .build();
        }
        
        attempt = concurrentLoginAttemptRepository.save(attempt);
        
        // Check if should auto-ban
        if (attempt.getAttemptCount() >= maxAttempts && !attempt.getIsBanned()) {
            log.warn("Auto-banning due to concurrent login attempts: ip={}, attempts={}",
                    ipAddress, attempt.getAttemptCount());
            
            // Create ban
            Ban ban = Ban.builder()
                    .banType(Ban.BanType.IP)
                    .ipAddress(ipAddress)
                    .deviceFingerprint(deviceFingerprint)
                    .reason(String.format("Quá nhiều lần đăng nhập đồng thời (%d lần trong %d giây)", 
                            attempt.getAttemptCount(), windowSeconds))
                    .banDurationMinutes(banDurationMinutes)
                    .bannedBy(null) // Auto-ban
                    .build();
            
            ban = banRepository.save(ban);
            
            // Mark attempt as banned
            attempt.setIsBanned(true);
            attempt.setBanId(ban.getId());
            concurrentLoginAttemptRepository.save(attempt);
        }
    }
    
    /**
     * Clear failed login attempts on successful login
     */
    @Transactional
    public void clearFailedLoginAttempts(String identifier, String ipAddress) {
        failedLoginAttemptRepository.findByIdentifierAndIpAddress(identifier, ipAddress)
                .ifPresent(attempt -> {
                    if (!attempt.getIsBanned()) {
                        failedLoginAttemptRepository.delete(attempt);
                    }
                });
    }
    
    /**
     * Manually ban user (admin action)
     */
    @Transactional
    public Ban banUser(Long userId, String reason, Integer banDurationMinutes, Long bannedBy) {
        // Deactivate existing bans
        List<Ban> existingBans = banRepository.findAllActiveBansByUserId(userId);
        existingBans.forEach(ban -> {
            ban.setIsActive(false);
            banRepository.save(ban);
        });
        
        // Create new ban
        Ban ban = Ban.builder()
                .userId(userId)
                .banType(Ban.BanType.USER)
                .reason(reason)
                .banDurationMinutes(banDurationMinutes)
                .bannedBy(bannedBy)
                .build();
        
        return banRepository.save(ban);
    }
    
    /**
     * Manually unban user (admin action)
     */
    @Transactional
    public void unbanUser(Long userId) {
        List<Ban> activeBans = banRepository.findAllActiveBansByUserId(userId);
        activeBans.forEach(ban -> {
            ban.setIsActive(false);
            banRepository.save(ban);
        });
    }
    
    /**
     * Auto-unban expired bans (scheduled task)
     */
    @Scheduled(fixedDelayString = "${ban.auto-unban.check-interval:60000}") // Default 60 seconds
    @Transactional
    public void autoUnbanExpiredBans() {
        if (!getConfigBoolean(AUTO_UNBAN_ENABLED, true)) {
            return; // Auto-unban disabled
        }
        
        LocalDateTime now = LocalDateTime.now();
        List<Ban> expiredBans = banRepository.findExpiredBans(now);
        
        for (Ban ban : expiredBans) {
            log.info("Auto-unbanning expired ban: banId={}, userId={}, reason={}",
                    ban.getId(), ban.getUserId(), ban.getReason());
            
            ban.setIsActive(false);
            ban.setAutoUnbanned(true);
            banRepository.save(ban);
        }
        
        if (!expiredBans.isEmpty()) {
            log.info("Auto-unbanned {} expired bans", expiredBans.size());
        }
    }
    
    // Helper methods to get config values
    private int getConfigInt(String key, int defaultValue) {
        return banConfigRepository.findByConfigKey(key)
                .map(BanConfig::getIntValue)
                .orElse(defaultValue);
    }
    
    private boolean getConfigBoolean(String key, boolean defaultValue) {
        return banConfigRepository.findByConfigKey(key)
                .map(BanConfig::getBooleanValue)
                .orElse(defaultValue);
    }
}

