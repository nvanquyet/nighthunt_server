package com.nighthunt.gamemode.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.gamemode.dto.GameModeDTO;
import com.nighthunt.gamemode.service.GameModeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for game mode operations.
 * 
 * Base path: /api/game-modes
 * 
 * Public endpoints (no authentication required for reading modes).
 */
@RestController
@RequestMapping("/game-modes")
@RequiredArgsConstructor
public class GameModeController {

    private final GameModeService gameModeService;

    /**
     * GET /api/game-modes
     * Get all displayable game modes (AVAILABLE, LOCKED, COMING_SOON).
     * Used by client to show mode selection UI.
     * 
     * Response: List<GameModeDTO>
     */
    @GetMapping
    public ApiResponse<List<GameModeDTO>> getDisplayableGameModes() {
        return ApiResponse.ok(gameModeService.getDisplayableGameModes());
    }

    /**
     * GET /api/game-modes/available
     * Get all AVAILABLE game modes (players can select).
     * 
     * Response: List<GameModeDTO>
     */
    @GetMapping("/available")
    public ApiResponse<List<GameModeDTO>> getAvailableGameModes() {
        return ApiResponse.ok(gameModeService.getAvailableGameModes());
    }

    /**
     * GET /api/game-modes/{modeKey}
     * Get specific game mode by key (2v2, 3v3, 4v4, 5v5).
     * 
     * Path param: modeKey (String)
     * Response: GameModeDTO
     */
    @GetMapping("/{modeKey}")
    public ApiResponse<GameModeDTO> getGameModeByKey(@PathVariable String modeKey) {
        return ApiResponse.ok(gameModeService.getGameModeByKey(modeKey));
    }
}
