package com.nighthunt.dedicatedserver.dto;

import lombok.Data;

/** Gửi từ DS mỗi 30s để báo hiệu còn alive (POST /api/ds/heartbeat) */
@Data
public class DsHeartbeatRequest {
    private String  serverId;
    private Integer currentPlayers;
    private String  serverSecret;
}
