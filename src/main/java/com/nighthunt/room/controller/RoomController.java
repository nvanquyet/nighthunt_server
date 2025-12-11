package com.nighthunt.room.controller;

import com.nighthunt.common.ApiResponse;
import com.nighthunt.room.dto.*;
import com.nighthunt.room.service.ReconnectService;
import com.nighthunt.room.service.RoomService;
import com.nighthunt.security.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/rooms")
@RequiredArgsConstructor
public class RoomController {
    private final RoomService roomService;
    private final ReconnectService reconnectService;

    @PostMapping("/create")
    public ApiResponse<RoomResponse> createRoom(@Valid @RequestBody CreateRoomRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        }
        RoomResponse response = roomService.createRoom(userId, request);
        return ApiResponse.ok(response);
    }

    @PostMapping("/join-by-code")
    public ApiResponse<RoomResponse> joinByCode(@Valid @RequestBody JoinRoomRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        }
        RoomResponse response = roomService.joinRoomByCode(userId, request.getRoomCode(), request.getPassword());
        return ApiResponse.ok(response);
    }

    @PostMapping("/quick-play")
    public ApiResponse<RoomResponse> quickPlay(@Valid @RequestBody QuickPlayRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        }
        RoomResponse response = roomService.quickPlay(userId, request);
        return ApiResponse.ok(response);
    }

    @PostMapping("/{roomId}/ready")
    public ApiResponse<RoomResponse> setReady(@PathVariable Long roomId,
                                              @Valid @RequestBody ReadyRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        }
        RoomResponse response = roomService.setReady(userId, roomId, request);
        return ApiResponse.ok(response);
    }

    @PostMapping("/{roomId}/change-team")
    public ApiResponse<RoomResponse> changeTeam(@PathVariable Long roomId,
                                               @Valid @RequestBody ChangeTeamRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        }
        RoomResponse response = roomService.changeTeam(userId, roomId, request);
        return ApiResponse.ok(response);
    }

    @PostMapping("/{roomId}/leave")
    public ApiResponse<Void> leaveRoom(@PathVariable Long roomId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("User not authenticated");
        }
        roomService.leaveRoom(userId, roomId);
        return ApiResponse.ok();
    }

    @PostMapping("/{roomId}/kick/{playerId}")
    public ApiResponse<Void> kickPlayer(@PathVariable Long roomId,
                                        @PathVariable Long playerId) {
        Long ownerId = SecurityUtils.getCurrentUserId();
        if (ownerId == null) {
            return ApiResponse.error("User not authenticated");
        }
        roomService.kickPlayer(roomId, ownerId, playerId);
        return ApiResponse.ok();
    }

    @PostMapping("/{roomId}/disband")
    public ApiResponse<Void> disbandRoom(@PathVariable Long roomId) {
        Long ownerId = SecurityUtils.getCurrentUserId();
        if (ownerId == null) {
            return ApiResponse.error("User not authenticated");
        }
        roomService.disbandRoom(roomId, ownerId);
        return ApiResponse.ok();
    }

    @PostMapping("/{roomId}/start")
    public ApiResponse<RoomResponse> startGame(@PathVariable Long roomId) {
        Long ownerId = SecurityUtils.getCurrentUserId();
        if (ownerId == null) {
            return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        }
        RoomResponse response = roomService.startGame(roomId, ownerId);
        return ApiResponse.ok(response);
    }

    @GetMapping("/{roomId}")
    public ApiResponse<RoomResponse> getRoom(@PathVariable Long roomId) {
        RoomResponse response = roomService.getRoom(roomId);
        return ApiResponse.ok(response);
    }

    @PostMapping("/reconnect")
    public ApiResponse<RoomResponse> reconnect(@Valid @RequestBody ReconnectRequest request) {
        RoomResponse response = reconnectService.reconnect(
                request.getAccessToken(),
                request.getSessionId(),
                request.getRoomId()
        );
        return ApiResponse.ok(response);
    }

    @PostMapping("/{roomId}/swap-request")
    public ApiResponse<SwapRequestDTO> requestSwap(@PathVariable Long roomId,
                                                   @Valid @RequestBody SwapRequestRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        }
        SwapRequestDTO response = roomService.requestSwap(userId, roomId, request.getTargetUserId(),
                request.getTargetTeam(), request.getTargetSlot());
        return ApiResponse.ok(response);
    }

    @PostMapping("/{roomId}/swap-accept/{requestId}")
    public ApiResponse<RoomResponse> acceptSwap(@PathVariable Long roomId,
                                                @PathVariable Long requestId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        }
        RoomResponse response = roomService.acceptSwapRequest(userId, roomId, requestId);
        return ApiResponse.ok(response);
    }

    @PostMapping("/{roomId}/swap-reject/{requestId}")
    public ApiResponse<Void> rejectSwap(@PathVariable Long roomId,
                                        @PathVariable Long requestId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("User not authenticated");
        }
        roomService.rejectSwapRequest(userId, roomId, requestId);
        return ApiResponse.ok();
    }

    @GetMapping("/{roomId}/swap-requests")
    public ApiResponse<List<SwapRequestDTO>> getPendingSwapRequests(@PathVariable Long roomId) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("User not authenticated");
        }
        List<SwapRequestDTO> requests = roomService.getPendingSwapRequests(userId, roomId);
        return ApiResponse.ok(requests);
    }

    @PostMapping("/{roomId}/update-settings")
    public ApiResponse<RoomResponse> updateRoomSettings(@PathVariable Long roomId,
                                                       @Valid @RequestBody UpdateRoomSettingsRequest request) {
        Long ownerId = SecurityUtils.getCurrentUserId();
        if (ownerId == null) {
            return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        }
        RoomResponse response = roomService.updateRoomSettings(ownerId, roomId, request);
        return ApiResponse.ok(response);
    }

    @PostMapping("/{roomId}/transfer-owner")
    public ApiResponse<RoomResponse> transferOwner(@PathVariable Long roomId,
                                                    @Valid @RequestBody TransferOwnerRequest request) {
        Long ownerId = SecurityUtils.getCurrentUserId();
        if (ownerId == null) {
            return ApiResponse.error("User not authenticated", "AUTH_REQUIRED");
        }
        RoomResponse response = roomService.transferOwner(ownerId, roomId, request.getTargetUserId());
        return ApiResponse.ok(response);
    }
}

