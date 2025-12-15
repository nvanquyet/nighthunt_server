package com.nighthunt.room.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "swap_requests", indexes = {
        @Index(name = "idx_room_id", columnList = "roomId"),
        @Index(name = "idx_requester_id", columnList = "requesterId"),
        @Index(name = "idx_target_id", columnList = "targetId"),
        @Index(name = "idx_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SwapRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "requester_id", nullable = false)
    private Long requesterId; // User who requested swap

    @Column(name = "target_id", nullable = false)
    private Long targetId; // User who received swap request

    @Column(name = "requester_team", nullable = false)
    private Integer requesterTeam;

    @Column(name = "requester_slot", nullable = false)
    private Integer requesterSlot;

    @Column(name = "target_team", nullable = false)
    private Integer targetTeam;

    @Column(name = "target_slot", nullable = false)
    private Integer targetSlot;

    @Column(name = "status", nullable = false, length = 20)
    private String status; // PENDING, ACCEPTED, REJECTED, EXPIRED

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (expiresAt == null) {
            expiresAt = LocalDateTime.now().plusSeconds(5); // 5 seconds expiry
        }
        if (status == null) {
            status = "PENDING";
        }
    }
}

