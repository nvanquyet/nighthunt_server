package com.nighthunt.session.port;

public interface SessionStore {
    void saveSession(String userId, String sessionId, int timeoutSeconds);
    String getSessionId(String userId);
    void deleteSession(String userId);
    void setForceLogout(String userId, boolean forceLogout);
    boolean isForceLogout(String userId);
    void deleteForceLogout(String userId);
}

