package com.nighthunt.admin.service;

import com.nighthunt.admin.entity.UserActivityLog;
import com.nighthunt.admin.repository.UserActivityLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserActivityService {

    private final UserActivityLogRepository logRepository;

    @Async
    public void log(Long userId, String username, String eventType, String eventData) {
        log(userId, username, eventType, eventData, resolveIp());
    }

    @Async
    public void log(Long userId, String username, String eventType, String eventData, String ipAddress) {
        try {
            logRepository.save(UserActivityLog.builder()
                    .userId(userId)
                    .username(username)
                    .eventType(eventType)
                    .eventData(eventData)
                    .ipAddress(ipAddress)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to save activity log [{}] for user {}: {}", eventType, userId, e.getMessage());
        }
    }

    private String resolveIp() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            var req = attrs.getRequest();
            String ip = req.getHeader("X-Forwarded-For");
            return (ip != null && !ip.isBlank()) ? ip.split(",")[0].trim() : req.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }
}
