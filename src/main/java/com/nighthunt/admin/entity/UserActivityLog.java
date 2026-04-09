package com.nighthunt.admin.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_activity_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "username", length = 50)
    private String username;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "event_data", columnDefinition = "TEXT")
    private String eventData;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    // ── Event type constants ──────────────────────────────────────────────────
    public static final String LOGIN           = "LOGIN";
    public static final String LOGOUT          = "LOGOUT";
    public static final String REGISTER        = "REGISTER";
    public static final String PASSWORD_CHANGE = "PASSWORD_CHANGE";
    public static final String ROOM_CREATE     = "ROOM_CREATE";
    public static final String ROOM_JOIN       = "ROOM_JOIN";
    public static final String ROOM_LEAVE      = "ROOM_LEAVE";
    public static final String MATCH_END       = "MATCH_END";
    public static final String BAN             = "BAN";
    public static final String UNBAN           = "UNBAN";
}
