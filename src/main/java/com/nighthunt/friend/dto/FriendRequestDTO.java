package com.nighthunt.friend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for friend request information.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FriendRequestDTO {
    private Long requestId;
    private Long requesterUserId;
    private String requesterUsername;
    private Long addresseeUserId;
    private String addresseeUsername;
    private String requestStatus;    // PENDING, ACCEPTED, DECLINED, CANCELLED
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
}
