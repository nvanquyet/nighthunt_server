package com.nighthunt.config.repository;

import com.nighthunt.config.entity.HeadlessServerConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * HeadlessServerConfigRepository - DISABLED
 * Headless server functionality has been disabled.
 * This repository remains for backward compatibility but is not actively used.
 */
@Repository
public interface HeadlessServerConfigRepository extends JpaRepository<HeadlessServerConfig, Long> {
    // All methods disabled - headless server functionality disabled
}
