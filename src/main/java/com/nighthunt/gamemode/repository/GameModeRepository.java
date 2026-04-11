package com.nighthunt.gamemode.repository;

import com.nighthunt.gamemode.entity.GameMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GameModeRepository extends JpaRepository<GameMode, Long> {
    
    /**
     * Find game mode by unique key (2v2, 3v3, 4v4, 5v5).
     */
    Optional<GameMode> findByModeKey(String modeKey);

    /**
     * Check if game mode exists by key.
     */
    boolean existsByModeKey(String modeKey);

    /**
     * Find all active game modes (ordered by display_order).
     */
    List<GameMode> findByIsActiveTrueOrderByDisplayOrderAsc();

    /**
     * Find all available game modes (players can select).
     */
    List<GameMode> findByModeStatusAndIsActiveTrueOrderByDisplayOrderAsc(String modeStatus);

    /**
     * Find all active game modes with specific status (AVAILABLE, LOCKED, COMING_SOON).
     */
    @Query("SELECT gm FROM GameMode gm WHERE gm.isActive = true AND gm.modeStatus IN ('AVAILABLE', 'LOCKED', 'COMING_SOON') AND gm.isDevMode = false ORDER BY gm.displayOrder ASC")
    List<GameMode> findDisplayableGameModes();

    /**
     * Modes that the matchmaking scheduler should process.
     * Criteria: active + AVAILABLE status + matchmakingEnabled = true.
     */
    @Query("SELECT gm FROM GameMode gm WHERE gm.isActive = true AND gm.modeStatus = 'AVAILABLE' AND gm.matchmakingEnabled = true ORDER BY gm.displayOrder ASC")
    List<GameMode> findMatchmakingEnabledModes();

    /**
     * Find all game modes regardless of status (for admin panel).
     */
    List<GameMode> findAllByOrderByDisplayOrderAsc();
}
