package com.nighthunt.ban.repository;

import com.nighthunt.ban.entity.ConcurrentLoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface ConcurrentLoginAttemptRepository extends JpaRepository<ConcurrentLoginAttempt, Long> {
    
    Optional<ConcurrentLoginAttempt> findFirstByIpAddressAndWindowEndAfterOrderByWindowEndDesc(String ipAddress, LocalDateTime now);
    
    Optional<ConcurrentLoginAttempt> findFirstByDeviceFingerprintAndWindowEndAfterOrderByWindowEndDesc(String deviceFingerprint, LocalDateTime now);
    
    @Modifying
    @Query("DELETE FROM ConcurrentLoginAttempt c WHERE c.windowEnd < :cutoffTime AND c.isBanned = false")
    void deleteOldAttempts(@Param("cutoffTime") LocalDateTime cutoffTime);
}

