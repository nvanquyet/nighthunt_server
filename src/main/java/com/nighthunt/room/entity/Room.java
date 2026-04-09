package com.nighthunt.room.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "rooms", indexes = {
        @Index(name = "idx_room_code", columnList = "roomCode", unique = true),
        @Index(name = "idx_status", columnList = "status"),
        @Index(name = "idx_owner_id", columnList = "ownerId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Room {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_code", nullable = false, unique = true, length = 8)
    private String roomCode;

    @Column(nullable = false, length = 20)
    private String mode; // 2v2, 3v3, 5v5

    @Column(name = "map_id", nullable = false, length = 50)
    private String mapId;

    @Column(nullable = false, length = 20)
    private String status; // WAITING, IN_GAME, CLOSED, FINISHED

    @Column(name = "is_public", nullable = false)
    private Boolean isPublic;

    @Column(name = "is_locked", nullable = false)
    private Boolean isLocked;

    @Column(name = "password", length = 50)
    private String password; // Optional password for room

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "match_id")
    private String matchId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

