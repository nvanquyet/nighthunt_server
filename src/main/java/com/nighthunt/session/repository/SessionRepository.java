package com.nighthunt.session.repository;

import com.nighthunt.session.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * SessionRepository - DISABLED
 * Session management now uses RedisSessionStore instead of database.
 * This repository remains for backward compatibility but is not actively used.
 */
@Repository
public interface SessionRepository extends JpaRepository<Session, Long> {
    // All methods disabled - using RedisSessionStore instead
}
