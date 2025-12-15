package com.nighthunt.room.service;

import com.nighthunt.room.entity.SwapRequest;
import com.nighthunt.room.repository.SwapRequestRepository;
import com.nighthunt.game.websocket.GameWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Background task to auto-expire pending swap requests after their expiresAt
 * Runs frequently (every second) to ensure 5s auto-reject is enforced server-side.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SwapRequestCleanupTask {

    private final SwapRequestRepository swapRequestRepository;
    private final GameWebSocketHandler gameWebSocketHandler;

    // Run every second
    @Scheduled(fixedDelay = 1000L)
    public void expirePendingSwaps() {
        LocalDateTime now = LocalDateTime.now();
        List<SwapRequest> expired = swapRequestRepository.findExpiredPending(now);
        if (expired.isEmpty()) {
            return;
        }

        for (SwapRequest sr : expired) {
            try {
                sr.setStatus("REJECTED");
                swapRequestRepository.save(sr);
                // Notify both requester/target in room about rejection/expiry
                gameWebSocketHandler.broadcastSwapRequestStatusChanged(sr.getRoomId(), sr.getId(), "REJECTED");
            } catch (Exception ex) {
                log.warn("Failed to expire swap request {}: {}", sr.getId(), ex.getMessage());
            }
        }
    }
}

