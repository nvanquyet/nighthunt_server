package com.nighthunt.security.filter;

import com.nighthunt.game.websocket.GameWebSocketHandler;
import com.nighthunt.common.exception.BusinessException;
import com.nighthunt.common.exception.ErrorCodes;
import com.nighthunt.common.constants.GameConstants;
import com.nighthunt.security.port.TokenProvider;
import com.nighthunt.session.port.SessionStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final TokenProvider tokenProvider;
    private final SessionStore sessionStore;
    private final GameWebSocketHandler gameWebSocketHandler;
    private static final String SESSION_HEADER = "X-Session-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // Skip JWT filter for WebSocket handshake - WebSocketHandler will validate token itself
        String requestPath = request.getRequestURI();
        if (requestPath != null && requestPath.startsWith("/ws/")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        try {
            String token = getTokenFromRequest(request);
            if (StringUtils.hasText(token) && tokenProvider.validateToken(token)) {
                Long userId = tokenProvider.getUserIdFromToken(token);
                String username = tokenProvider.getUsernameFromToken(token);
                String clientSessionId = request.getHeader(SESSION_HEADER);

                // Check force logout
                if (sessionStore.isForceLogout(String.valueOf(userId))) {
                    // Send force_logout event via WebSocket
                    try {
                        gameWebSocketHandler.sendForceLogout(userId, "Tài khoản đã đăng nhập ở nơi khác");
                    } catch (Exception e) {
                        log.error("Error sending force_logout event to user {}: {}", userId, e.getMessage());
                    }
                    throw new BusinessException(ErrorCodes.AUTH_FORCE_LOGOUT,
                            "Tài khoản đã đăng nhập ở nơi khác. Vui lòng đăng nhập lại.");
                }

                // Check session validity
                String currentSessionId = sessionStore.getSessionId(String.valueOf(userId));
                if (currentSessionId == null) {
                    log.warn("Session not found in Redis for user {} - session expired or never existed", userId);
                    // Send session_expired event via WebSocket
                    try {
                        gameWebSocketHandler.sendSessionExpired(userId);
                    } catch (Exception e) {
                        log.error("Error sending session_expired event to user {}: {}", userId, e.getMessage());
                    }
                    throw new BusinessException(ErrorCodes.AUTH_SESSION_EXPIRED,
                            "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
                }
                if (!StringUtils.hasText(clientSessionId) || !currentSessionId.equals(clientSessionId)) {
                    log.warn("Session mismatch for user {} - Redis sessionId: {}, Client sessionId: {}", 
                            userId, currentSessionId, clientSessionId);
                    // Send session_expired event via WebSocket
                    try {
                        gameWebSocketHandler.sendSessionExpired(userId);
                    } catch (Exception e) {
                        log.error("Error sending session_expired event to user {}: {}", userId, e.getMessage());
                    }
                    throw new BusinessException(ErrorCodes.AUTH_SESSION_EXPIRED,
                            "Session không hợp lệ hoặc đã hết hạn.");
                }

                // Refresh TTL for active session
                sessionStore.saveSession(String.valueOf(userId), currentSessionId, GameConstants.SESSION_TIMEOUT_SECONDS);
                log.debug("Session TTL refreshed for user {} (sessionId: {})", userId, currentSessionId);

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                userId,
                                username,
                                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
                        );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }

            filterChain.doFilter(request, response);
        } catch (BusinessException ex) {
            // Auth-related business errors: return 401/403 with message
            log.warn("Auth error: {}", ex.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    String.format("{\"success\":false,\"message\":\"%s\",\"errorCode\":\"%s\"}", ex.getMessage(), ex.getErrorCode())
            );
        } catch (Exception e) {
            log.error("Cannot set user authentication", e);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"Unauthorized\",\"errorCode\":\"AUTH_UNAUTHORIZED\"}");
        }
    }

    private String getTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}

