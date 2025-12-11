package com.nighthunt.match.port;

import com.nighthunt.match.dto.*;

public interface HeadlessPort {
    CreateMatchResponse createMatch(CreateMatchRequest request);
    void startMatch(String matchId);
    void closeMatch(String matchId);
    void kickPlayer(String matchId, Long playerId);
    void updatePlayerTeam(String matchId, Long playerId, Integer team, Integer slot);
    void reportLoad(String serverId, LoadReportRequest request);
}

