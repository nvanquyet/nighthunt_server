package com.nighthunt.dedicatedserver.dto;

import lombok.Data;

/** Gửi từ DS container sau khi boot xong (POST /api/ds/register) */
@Data
public class DsRegisterRequest {
    private String serverId;
    private Integer port;
    private String status;
    private Integer maxPlayers;
    private String serverSecret;   // Plain secret - backend verify bằng BCrypt hash
}
