package com.nighthunt.session.service;

import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.session.port.SessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {
    private final SessionStore sessionStore;

    public void invalidateAllUserSessions(Long userId) {
        String userIdStr = String.valueOf(userId);
        String existingSessionId = sessionStore.getSessionId(userIdStr);
        
        if (existingSessionId != null) {
            // Set force logout flag for existing session
            sessionStore.setForceLogout(userIdStr, true);
            // Delete existing session
            sessionStore.deleteSession(userIdStr);
            log.info("Invalidated all sessions for user: {}", userId);
        }
    }

    public void refreshSession(String userId) {
        String sessionId = sessionStore.getSessionId(userId);
        if (sessionId != null) {
            sessionStore.saveSession(userId, sessionId, GameConstants.SESSION_TIMEOUT_SECONDS);
        }
    }
}

