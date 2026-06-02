package com.nighthunt.realtime.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "realtime_outbox_event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RealtimeOutboxEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RealtimeOutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "available_at", nullable = false)
    private LocalDateTime availableAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    public static RealtimeOutboxEvent pending(String subject, String payload) {
        LocalDateTime now = LocalDateTime.now();
        RealtimeOutboxEvent event = new RealtimeOutboxEvent();
        event.eventId = UUID.randomUUID().toString();
        event.subject = subject;
        event.payload = payload;
        event.status = RealtimeOutboxStatus.PENDING;
        event.availableAt = now;
        event.createdAt = now;
        return event;
    }

    public void markPublished() {
        status = RealtimeOutboxStatus.PUBLISHED;
        publishedAt = LocalDateTime.now();
        lastError = null;
    }

    public void scheduleRetry(Throwable error) {
        attempts++;
        long delaySeconds = Math.min(60L, 1L << Math.min(attempts, 6));
        availableAt = LocalDateTime.now().plusSeconds(delaySeconds);
        String message = error == null ? "Unknown publish failure" : error.getMessage();
        if (message == null || message.isBlank()) {
            message = error == null ? "Unknown publish failure" : error.getClass().getSimpleName();
        }
        lastError = message.substring(0, Math.min(message.length(), 1000));
    }
}
