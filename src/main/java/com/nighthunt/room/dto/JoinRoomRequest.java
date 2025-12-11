package com.nighthunt.room.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class JoinRoomRequest {
    @NotBlank
    private String roomCode;
    private String password; // Required if room has password
}

