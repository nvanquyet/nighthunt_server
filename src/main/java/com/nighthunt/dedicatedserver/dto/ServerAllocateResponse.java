package com.nighthunt.dedicatedserver.dto;

import lombok.Builder;
import lombok.Data;

/** Trả về cho Client sau khi allocate thành công (GET /api/matchmaking/request) */
@Data
@Builder
public class ServerAllocateResponse {
    private String  serverId;
    private String  ip;
    private Integer port;
    /** "starting" | "ready" - client poll nếu status = starting */
    private String  status;
    /** One-time session token - client gửi kèm khi connect UDP vào DS */
    private String  sessionToken;
    /**
     * CHỈ có giá trị khi ds.docker.enabled=false (local dev mode).
     * Dùng để gọi POST /api/ds/register trong test script.
     * KHÔNG bao giờ có giá trị trên production.
     */
    private String  devSecret;
}
