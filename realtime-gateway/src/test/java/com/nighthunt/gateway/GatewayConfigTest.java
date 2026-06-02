package com.nighthunt.gateway;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayConfigTest {
    @Test
    void loadsProductionOverrides() {
        GatewayConfig config = GatewayConfig.from(Map.of(
                "GATEWAY_ID", "gateway-bkk-2",
                "GATEWAY_PORT", "18090",
                "WS_ACCEPT_BACKLOG", "16384",
                "WS_PRESENCE_LEASE_SECONDS", "90"
        ));

        assertThat(config.gatewayId()).isEqualTo("gateway-bkk-2");
        assertThat(config.port()).isEqualTo(18090);
        assertThat(config.acceptBacklog()).isEqualTo(16384);
        assertThat(config.presenceLease().toSeconds()).isEqualTo(90);
        assertThat(config.wsPath()).isEqualTo("/api/ws/game");
    }

    @Test
    void prefersContainerSpecificNatsUrlOverHostLocalUrl() {
        GatewayConfig config = GatewayConfig.from(Map.of(
                "NATS_URL", "nats://localhost:4222",
                "GATEWAY_NATS_URL", "nats://nats:4222"
        ));

        assertThat(config.natsUrl()).isEqualTo("nats://nats:4222");
    }
}
