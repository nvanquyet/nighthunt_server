package com.nighthunt.map.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.map.dto.GameMapDTO;
import com.nighthunt.map.service.GameMapService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * GET /api/maps — returns all active maps for the client to populate MapConfig at runtime.
 * No authentication required (public endpoint).
 */
@RestController
@RequestMapping("/maps")
@RequiredArgsConstructor
public class GameMapController {

    private final GameMapService mapService;

    /** All active maps (including locked ones so client can show "Coming Soon"). */
    @GetMapping
    public ApiResponse<List<GameMapDTO>> getAllMaps() {
        return ApiResponse.ok(mapService.getAllMaps());
    }

    /** Only unlocked, playable maps. */
    @GetMapping("/available")
    public ApiResponse<List<GameMapDTO>> getAvailableMaps() {
        return ApiResponse.ok(mapService.getAvailableMaps());
    }
}
