package com.nighthunt.security.port;

public interface TokenProvider {
    record AuthenticatedToken(Long userId, String username) {}

    String generateToken(Long userId, String username);
    AuthenticatedToken parseToken(String token);
    Long getUserIdFromToken(String token);
    String getUsernameFromToken(String token);
    boolean validateToken(String token);
}

