package com.nighthunt.friend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for friend information.
 * Returned when querying friend list.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FriendDTO {
    private Long friendId;           // Friend entity ID
    private Long userId;             // Friend's user ID
    private String username;         // Friend's username
    private String onlineStatus;     // ONLINE, OFFLINE, AWAY, IN_GAME
    private LocalDateTime lastSeenAt;
    private String friendshipStatus; // ACTIVE, BLOCKED
    private Long currentPartyId;     // NULL if not in party
    private Long currentRoomId;      // NULL if not in room
    private LocalDateTime friendsSince; // When friendship was created
}
