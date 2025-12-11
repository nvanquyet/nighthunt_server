package com.nighthunt.match.repository;

import com.nighthunt.match.entity.HeadlessServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * HeadlessServerRepository - DISABLED
 * Headless server functionality has been disabled.
 * This repository remains for backward compatibility but is not actively used.
 */
@Repository
public interface HeadlessServerRepository extends JpaRepository<HeadlessServer, Long> {
    // All methods disabled - headless server functionality disabled
}
