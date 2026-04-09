package com.nighthunt.gamemode.service;

import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.gamemode.dto.GameModeDTO;
import com.nighthunt.gamemode.entity.GameMode;
import com.nighthunt.gamemode.repository.GameModeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Game Mode Service - Manages configurable game modes (2v2, 3v3, 4v4, 5v5).
 * 
 * Game modes can be:
 * - AVAILABLE: Players can select and play
 * - LOCKED: Visible but not selectable (show lock icon)
 * - COMING_SOON: Visible with "Coming Soon" badge
 * - DISABLED: Hidden from UI
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameModeService {

    private final GameModeRepository gameModeRepository;

    // ──────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Get all game modes that should be displayed in UI.
     * Includes AVAILABLE, LOCKED, and COMING_SOON modes.
     * Excludes DISABLED modes.
     */
    @Transactional(readOnly = true)
    public List<GameModeDTO> getDisplayableGameModes() {
        List<GameMode> gameModes = gameModeRepository.findDisplayableGameModes();
        
        return gameModes.stream()
                .map(this::toGameModeDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get all AVAILABLE game modes (players can select).
     */
    @Transactional(readOnly = true)
    public List<GameModeDTO> getAvailableGameModes() {
        List<GameMode> gameModes = gameModeRepository.findByModeStatusAndIsActiveTrueOrderByDisplayOrderAsc("AVAILABLE");
        
        return gameModes.stream()
                .map(this::toGameModeDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get game mode by key (2v2, 3v3, 4v4, 5v5).
     */
    @Transactional(readOnly = true)
    public GameModeDTO getGameModeByKey(String modeKey) {
        GameMode gameMode = gameModeRepository.findByModeKey(modeKey)
                .orElseThrow(() -> new BusinessException(ErrorCodes.USER_NOT_FOUND, "Game mode not found: " + modeKey));
        
        return toGameModeDTO(gameMode);
    }

    /**
     * Check if game mode exists and is available.
     */
    @Transactional(readOnly = true)
    public boolean isGameModeAvailable(String modeKey) {
        return gameModeRepository.findByModeKey(modeKey)
                .map(gm -> "AVAILABLE".equals(gm.getModeStatus()) && gm.isActive())
                .orElse(false);
    }

    /**
     * Get players per team for a mode key.
     */
    @Transactional(readOnly = true)
    public int getPlayersPerTeam(String modeKey) {
        GameMode gameMode = gameModeRepository.findByModeKey(modeKey)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND, "Game mode not found: " + modeKey));
        return gameMode.getPlayersPerTeam();
    }

    /**
     * Get total players (max capacity) for a mode key.
     */
    @Transactional(readOnly = true)
    public int getTotalPlayers(String modeKey) {
        GameMode gameMode = gameModeRepository.findByModeKey(modeKey)
                .orElseThrow(() -> new BusinessException(ErrorCodes.ROOM_NOT_FOUND, "Game mode not found: " + modeKey));
        return gameMode.getTotalPlayers();
    }

    /**
     * Validate that a mode exists and is AVAILABLE. Throws ROOM_NOT_FOUND if not.
     * Use this as a drop-in replacement for GameMode.fromString() validation calls.
     */
    @Transactional(readOnly = true)
    public void validateModeOrThrow(String modeKey) {
        if (!isGameModeAvailable(modeKey)) {
            throw new BusinessException(ErrorCodes.ROOM_NOT_FOUND,
                    "Invalid or unavailable game mode: " + modeKey);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ADMIN API (for future admin panel)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Get all game modes (for admin panel).
     */
    @Transactional(readOnly = true)
    public List<GameModeDTO> getAllGameModes() {
        List<GameMode> gameModes = gameModeRepository.findAllByOrderByDisplayOrderAsc();
        
        return gameModes.stream()
                .map(this::toGameModeDTO)
                .collect(Collectors.toList());
    }

    /**
     * Update game mode status (AVAILABLE, LOCKED, COMING_SOON, DISABLED).
     * Admin only.
     */
    @Transactional
    public GameModeDTO updateGameModeStatus(String modeKey, String newStatus) {
        GameMode gameMode = gameModeRepository.findByModeKey(modeKey)
                .orElseThrow(() -> new BusinessException(ErrorCodes.USER_NOT_FOUND, "Game mode not found: " + modeKey));
        
        gameMode.setModeStatus(newStatus);
        gameModeRepository.save(gameMode);
        
        log.info("Game mode status updated: mode={}, status={}", modeKey, newStatus);
        
        return toGameModeDTO(gameMode);
    }

    /**
     * Enable/disable game mode (soft delete).
     * Admin only.
     */
    @Transactional
    public GameModeDTO setGameModeActive(String modeKey, boolean isActive) {
        GameMode gameMode = gameModeRepository.findByModeKey(modeKey)
                .orElseThrow(() -> new BusinessException(ErrorCodes.USER_NOT_FOUND, "Game mode not found: " + modeKey));
        
        gameMode.setActive(isActive);
        gameModeRepository.save(gameMode);
        
        log.info("Game mode active status updated: mode={}, isActive={}", modeKey, isActive);
        
        return toGameModeDTO(gameMode);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // MATCHMAKING SUPPORT
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Modes that the scheduler should process: active + matchmakingEnabled.
     * This replaces iterating GameMode enum in MatchmakingQueueService.
     */
    @Transactional(readOnly = true)
    public List<GameModeDTO> getMatchmakingEnabledModes() {
        return gameModeRepository.findMatchmakingEnabledModes()
                .stream().map(this::toGameModeDTO).collect(Collectors.toList());
    }

    /**
     * Validate that a player can enter the matchmaking queue for this mode.
     * True when mode is AVAILABLE + matchmakingEnabled + active.
     */
    @Transactional(readOnly = true)
    public boolean isModeAvailableForMatchmaking(String modeKey) {
        return gameModeRepository.findByModeKey(modeKey)
                .map(gm -> gm.isActive()
                        && gm.isMatchmakingEnabled()
                        && "AVAILABLE".equals(gm.getModeStatus()))
                .orElse(false);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // DTO CONVERSION
    // ──────────────────────────────────────────────────────────────────────────

    private GameModeDTO toGameModeDTO(GameMode gameMode) {
        return GameModeDTO.builder()
                .id(gameMode.getId())
                .modeKey(gameMode.getModeKey())
                .displayName(gameMode.getDisplayName())
                .description(gameMode.getDescription())
                .playersPerTeam(gameMode.getPlayersPerTeam())
                .totalPlayers(gameMode.getTotalPlayers())
                .allowFill(gameMode.isAllowFill())
                .matchmakingEnabled(gameMode.isMatchmakingEnabled())
                .minElo(gameMode.getMinElo())
                .maxElo(gameMode.getMaxElo())
                .modeStatus(gameMode.getModeStatus())
                .displayOrder(gameMode.getDisplayOrder())
                .isActive(gameMode.isActive())
                .build();
    }
}
