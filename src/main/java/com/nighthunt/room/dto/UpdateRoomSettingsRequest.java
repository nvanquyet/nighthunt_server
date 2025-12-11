package com.nighthunt.room.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateRoomSettingsRequest {
    @Size(min = 3, max = 10, message = "Mode must be 2v2, 3v3, or 5v5")
    private String mode; // Optional: 2v2, 3v3, 5v5

    private Boolean isPublic; // Optional: true/false

    private Boolean isLocked; // Optional: true/false

    @Size(max = 50, message = "Password must be less than 50 characters")
    private String password; // Optional: new password (null to remove, empty to keep current)
}

