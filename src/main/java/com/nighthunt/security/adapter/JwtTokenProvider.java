package com.nighthunt.security.adapter;

import com.nighthunt.security.port.TokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
@Component
public class JwtTokenProvider implements TokenProvider {
    private final SecretKey secretKey;
    private final long tokenValidityInMilliseconds;

    public JwtTokenProvider(@Value("${jwt.secret}") String secret,
                           @Value("${jwt.expiration:3600000}") long expiration) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be configured — refusing to start with empty secret");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 256 bits (32 bytes)");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.tokenValidityInMilliseconds = expiration;
    }

    @Override
    public String generateToken(Long userId, String username) {
        Date now = new Date();
        Date validity = new Date(now.getTime() + tokenValidityInMilliseconds);

        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("username", username)
                .setIssuedAt(now)
                .setExpiration(validity)
                .signWith(secretKey)
                .compact();
    }

    @Override
    public AuthenticatedToken parseToken(String token) {
        Claims claims = parseClaims(token);
        return new AuthenticatedToken(
                Long.parseLong(claims.getSubject()),
                claims.get("username", String.class)
        );
    }

    @Override
    public Long getUserIdFromToken(String token) {
        return parseToken(token).userId();
    }

    @Override
    public String getUsernameFromToken(String token) {
        return parseToken(token).username();
    }

    @Override
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}

