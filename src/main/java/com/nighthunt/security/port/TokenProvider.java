package com.nighthunt.security.port;

public interface TokenProvider {
    String generateToken(Long userId, String username);
    Long getUserIdFromToken(String token);
    String getUsernameFromToken(String token);
    boolean validateToken(String token);
}

