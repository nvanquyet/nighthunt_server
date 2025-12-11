package com.nighthunt.match.adapter;

import com.nighthunt.match.dto.*;
import com.nighthunt.match.port.HeadlessPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * RestHeadlessAdapter - DISABLED
 * Headless server functionality has been disabled.
 * This adapter remains for backward compatibility but all methods are no-ops.
 */
@Slf4j
@Component
public class RestHeadlessAdapter implements HeadlessPort {
    @Override
    public CreateMatchResponse createMatch(CreateMatchRequest request) {
        log.warn("Headless server disabled - createMatch is no-op");
        return null;
    }

    @Override
    public void startMatch(String matchId) {
        log.warn("Headless server disabled - startMatch is no-op");
    }

    @Override
    public void closeMatch(String matchId) {
        log.warn("Headless server disabled - closeMatch is no-op");
    }

    @Override
    public void kickPlayer(String matchId, Long playerId) {
        log.warn("Headless server disabled - kickPlayer is no-op");
    }

    @Override
    public void updatePlayerTeam(String matchId, Long playerId, Integer team, Integer slot) {
        log.warn("Headless server disabled - updatePlayerTeam is no-op");
    }

    @Override
    public void reportLoad(String serverId, LoadReportRequest request) {
        log.warn("Headless server disabled - reportLoad is no-op");
    }
}
