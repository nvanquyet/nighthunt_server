package com.nighthunt.room.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "room_players", indexes = {
        @Index(name = "idx_room_id", columnList = "roomId"),
        @Index(name = "idx_user_id", columnList = "userId"),
        @Index(name = "idx_room_user", columnList = "roomId,userId", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoomPlayer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "team", nullable = false)
    private Integer team; // 1 or 2

    @Column(name = "slot", nullable = false)
    private Integer slot; // position in team (0, 1, 2...)

    @Column(name = "is_ready", nullable = false)
    private Boolean isReady;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private LocalDateTime joinedAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @PrePersist
    protected void onCreate() {
        joinedAt = LocalDateTime.now();
        lastSeenAt = LocalDateTime.now();
        if (isReady == null) {
            isReady = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        lastSeenAt = LocalDateTime.now();
    }
}

