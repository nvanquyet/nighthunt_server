package com.nighthunt.ban.repository;

import com.nighthunt.ban.entity.Ban;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BanRepository extends JpaRepository<Ban, Long> {
    
    // Find active bans for user
    @Query("SELECT b FROM Ban b WHERE b.userId = :userId AND b.isActive = true AND (b.expiresAt IS NULL OR b.expiresAt > :now)")
    Optional<Ban> findActiveBanByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);
    
    // Find active bans for IP
    @Query("SELECT b FROM Ban b WHERE b.ipAddress = :ipAddress AND b.isActive = true AND (b.expiresAt IS NULL OR b.expiresAt > :now)")
    Optional<Ban> findActiveBanByIpAddress(@Param("ipAddress") String ipAddress, @Param("now") LocalDateTime now);
    
    // Find active bans for device
    @Query("SELECT b FROM Ban b WHERE b.deviceFingerprint = :deviceFingerprint AND b.isActive = true AND (b.expiresAt IS NULL OR b.expiresAt > :now)")
    Optional<Ban> findActiveBanByDeviceFingerprint(@Param("deviceFingerprint") String deviceFingerprint, @Param("now") LocalDateTime now);
    
    // Find expired bans that need auto-unban
    @Query("SELECT b FROM Ban b WHERE b.isActive = true AND b.expiresAt IS NOT NULL AND b.expiresAt <= :now AND b.autoUnbanned = false")
    List<Ban> findExpiredBans(@Param("now") LocalDateTime now);
    
    // Find all active bans for user (any type)
    @Query("SELECT b FROM Ban b WHERE b.userId = :userId AND b.isActive = true")
    List<Ban> findAllActiveBansByUserId(@Param("userId") Long userId);

    // All bans for a user (history, including expired)
    List<Ban> findByUserIdOrderByBannedAtDesc(Long userId);

    // Admin queries
    long countByIsActiveTrue();

    @Query("""
        SELECT b FROM Ban b
        WHERE (:type IS NULL OR b.banType = :type)
          AND (:active IS NULL OR b.isActive = :active)
        ORDER BY b.bannedAt DESC
        """)
    org.springframework.data.domain.Page<Ban> findFiltered(
        @Param("type")   com.nighthunt.ban.entity.Ban.BanType type,
        @Param("active") Boolean active,
        org.springframework.data.domain.Pageable pageable
    );
}

