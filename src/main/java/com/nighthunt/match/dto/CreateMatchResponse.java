package com.nighthunt.match.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateMatchResponse {
    private String matchId;
    private String serverIp;
    private Integer serverPort;
    private String joinToken;
}

