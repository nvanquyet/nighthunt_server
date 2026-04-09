package com.nighthunt.friend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for adding a friend.
 * Can add by username or user ID.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddFriendRequest {
    
    /**
     * Username of the friend to add.
     * Either username or userId must be provided.
     */
    private String username;
    
    /**
     * User ID of the friend to add.
     * Either username or userId must be provided.
     */
    private Long userId;
}
