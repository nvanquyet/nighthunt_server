package com.nighthunt.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * MdcLoggingFilter — injects per-request context into the SLF4J MDC.
 *
 * <p>Runs FIRST in the filter chain (Order.HIGHEST_PRECEDENCE + 1) so every
 * subsequent filter and controller has {@code requestId} and optionally
 * {@code userId} available in log output.
 *
 * <p>MDC keys injected:
 * <ul>
 *   <li>{@code requestId} — random UUID-short (8 hex chars) or {@code X-Request-Id} header if present</li>
 *   <li>{@code userId}    — extracted from {@code X-User-Id} response header set by JwtAuthenticationFilter</li>
 *   <li>{@code matchId}   — extracted from {@code X-Match-Id} request header (DS → backend calls)</li>
 *   <li>{@code method}    — HTTP method (GET/POST/…)</li>
 *   <li>{@code path}      — request URI</li>
 * </ul>
 *
 * <p>MDC is always cleared in the {@code finally} block to prevent thread-pool leaks.
 *
 * <p>Log prefix conventions used across NightHunt services:
 * <pre>
 *   [AUTH]        login, register, token rotation
 *   [WS]          WebSocket connect/disconnect/events
 *   [STATUS]      player online/offline state changes
 *   [Room]        room CRUD, player join/leave
 *   [MM]          matchmaking queue, match found
 *   [DS-Alloc]    DS server allocation
 *   [DS-Svc]      DS lifecycle (register, heartbeat, game-ready)
 *   [DS-Reclaim]  DS container stop after match
 *   [Docker]      Docker CLI operations
 *   [Relay]       relay session create/close
 *   [MatchEnd]    match result, ELO update
 *   [Friend]      friend request, accept, block
 *   [Party]       party create, invite, queue
 *   [Profile]     profile load, character update
 * </pre>
 */
@Component
@Order(Integer.MIN_VALUE + 1)
public class MdcLoggingFilter extends OncePerRequestFilter {

    private static final String HEADER_REQUEST_ID = "X-Request-Id";
    private static final String HEADER_MATCH_ID   = "X-Match-Id";

    // MDC key constants — shared with logback-spring.xml patterns
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_USER_ID    = "userId";
    public static final String MDC_MATCH_ID   = "matchId";

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         chain)
            throws ServletException, IOException {

        try {
            // ── Request ID ──────────────────────────────────────────────────
            String requestId = request.getHeader(HEADER_REQUEST_ID);
            if (requestId == null || requestId.isBlank()) {
                // Generate short ID: first 8 chars of random UUID (no dashes)
                requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            }
            MDC.put(MDC_REQUEST_ID, requestId);

            // ── Match ID (DS → backend calls carry X-Match-Id) ─────────────
            String matchId = request.getHeader(HEADER_MATCH_ID);
            if (matchId != null && !matchId.isBlank()) {
                MDC.put(MDC_MATCH_ID, matchId);
            }

            // ── Echo request ID back to client for distributed tracing ──────
            response.setHeader(HEADER_REQUEST_ID, requestId);

            // ── Execute rest of filter chain ────────────────────────────────
            chain.doFilter(request, response);

        } finally {
            // ALWAYS clear MDC to avoid thread-pool leakage
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_USER_ID);
            MDC.remove(MDC_MATCH_ID);
        }
    }

    /**
     * Set the userId MDC key from within the JWT auth filter after token is validated.
     * Called by {@link JwtAuthenticationFilter} after successful auth.
     */
    public static void setUserId(Long userId) {
        if (userId != null) {
            MDC.put(MDC_USER_ID, userId.toString());
        }
    }
}
