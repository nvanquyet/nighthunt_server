package com.nighthunt.common.health;

import com.nighthunt.game.websocket.port.ConnectionManager;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * WebSocketHealthIndicator — custom Spring Actuator health indicator for the
 * WebSocket gateway (P2-1 / P2-6 implementation).
 *
 * <h2>Exposed at</h2>
 * <pre>GET /actuator/health → components.webSocket</pre>
 *
 * <h2>Metrics registered</h2>
 * <ul>
 *   <li>{@code nighthunt.ws.connections.active} — real-time Gauge of open sinks</li>
 * </ul>
 *
 * <h2>Health status rules</h2>
 * <table>
 *   <tr><th>Condition</th><th>Status</th></tr>
 *   <tr><td>active &lt; warningThreshold</td><td>UP</td></tr>
 *   <tr><td>active &ge; warningThreshold</td><td>UP (detail: HIGH)</td></tr>
 * </table>
 * Health never goes DOWN from connection count alone — only from exception.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketHealthIndicator implements HealthIndicator {

    private final ConnectionManager connectionManager;
    private final MeterRegistry     meterRegistry;

    @Value("${nighthunt.ws.capacity-warning-threshold:8000}")
    private int capacityWarningThreshold;

    @PostConstruct
    public void registerMetrics() {
        // P2-6: Gauge — reflects userSinks.size() in real time
        Gauge.builder("nighthunt.ws.connections.active",
                        connectionManager, c -> (double) c.getActiveConnectionCount())
                .description("Number of active WebSocket connections")
                .baseUnit("connections")
                .register(meterRegistry);

        log.info("[WsHealth] Metrics registered. Warning threshold: {} connections", capacityWarningThreshold);
    }

    @Override
    public Health health() {
        int active = connectionManager.getActiveConnectionCount();
        String load = active >= capacityWarningThreshold ? "HIGH" : "OK";

        return Health.up()
                .withDetail("active_connections",        active)
                .withDetail("capacity_warning_threshold", capacityWarningThreshold)
                .withDetail("load",                      load)
                .build();
    }
}
