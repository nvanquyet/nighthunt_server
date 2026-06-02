package com.nighthunt.realtime.service;

import com.nighthunt.realtime.dto.RealtimeTicketResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class RealtimeTicketService {
    static final String TICKET_KEY_PREFIX = "ws:ticket:";

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${nighthunt.realtime.ticket-ttl-seconds:45}")
    private long ticketTtlSeconds;

    @Value("${nighthunt.realtime.ws-path:/api/ws/game}")
    private String wsPath;

    public RealtimeTicketResponse issue(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("Authenticated userId is required");
        }

        byte[] entropy = new byte[32];
        secureRandom.nextBytes(entropy);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);

        redisTemplate.opsForValue().set(
                TICKET_KEY_PREFIX + ticket,
                String.valueOf(userId),
                Duration.ofSeconds(ticketTtlSeconds)
        );

        return new RealtimeTicketResponse(ticket, ticketTtlSeconds, wsPath);
    }
}
