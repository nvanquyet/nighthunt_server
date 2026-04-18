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

    /**
     * IP thực tế mà DS container muốn báo cho backend (optional).
     * Nếu null → backend giữ nguyên IP đã set lúc allocate (VPS_PUBLIC_IP).
     * Dùng trong trường hợp multi-VPS hoặc container IP khác VPS_PUBLIC_IP.
     */
    private String reportedIp;
}
