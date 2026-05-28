package com.nighthunt.ban.repository;

import com.nighthunt.ban.entity.FailedLoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface FailedLoginAttemptRepository extends JpaRepository<FailedLoginAttempt, Long> {
    
    Optional<FailedLoginAttempt> findFirstByIdentifierAndIpAddressOrderByLastAttemptAtDesc(String identifier, String ipAddress);
    
    @Modifying
    @Query("DELETE FROM FailedLoginAttempt f WHERE f.lastAttemptAt < :cutoffTime AND f.isBanned = false")
    void deleteOldAttempts(@Param("cutoffTime") LocalDateTime cutoffTime);
}

