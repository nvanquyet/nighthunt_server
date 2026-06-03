package com.nighthunt.gateway.metrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

public final class GatewayMetrics implements AutoCloseable {
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejectedTickets = new AtomicLong();
    private final AtomicLong inboundFrames = new AtomicLong();
    private final AtomicLong outboundFrames = new AtomicLong();
    private final AtomicLong slowConsumers = new AtomicLong();
    private final AtomicLong staleDisconnects = new AtomicLong();
    private final AtomicLong clientDisconnects = new AtomicLong();
    private final AtomicLong transportDrops = new AtomicLong();
    private final HttpServer server;
    private volatile IntSupplier activeConnections = () -> 0;

    public GatewayMetrics(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 64);
        server.createContext("/health", exchange -> respond(exchange, 200, "UP\n"));
        server.createContext("/metrics", exchange -> respond(exchange, 200, scrape()));
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gateway-metrics");
            thread.setDaemon(true);
            return thread;
        }));
    }

    public void bindActiveConnections(IntSupplier supplier) {
        activeConnections = supplier;
    }

    public void start() {
        server.start();
    }

    public void accepted() { accepted.incrementAndGet(); }
    public void rejectedTicket() { rejectedTickets.incrementAndGet(); }
    public void inboundFrame() { inboundFrames.incrementAndGet(); }
    public void outboundFrame() { outboundFrames.incrementAndGet(); }
    public void slowConsumer() { slowConsumers.incrementAndGet(); }
    public void disconnected(String reason) {
        if ("STALE_CONNECTION".equals(reason)) {
            staleDisconnects.incrementAndGet();
        } else if ("CLIENT_CLOSE".equals(reason)) {
            clientDisconnects.incrementAndGet();
        } else {
            transportDrops.incrementAndGet();
        }
    }

    String scrape() {
        return """
                # TYPE nighthunt_gateway_active_connections gauge
                nighthunt_gateway_active_connections %d
                # TYPE nighthunt_gateway_connections_accepted_total counter
                nighthunt_gateway_connections_accepted_total %d
                # TYPE nighthunt_gateway_ticket_rejections_total counter
                nighthunt_gateway_ticket_rejections_total %d
                # TYPE nighthunt_gateway_inbound_frames_total counter
                nighthunt_gateway_inbound_frames_total %d
                # TYPE nighthunt_gateway_outbound_frames_total counter
                nighthunt_gateway_outbound_frames_total %d
                # TYPE nighthunt_gateway_slow_consumers_total counter
                nighthunt_gateway_slow_consumers_total %d
                # TYPE nighthunt_gateway_disconnects_total counter
                nighthunt_gateway_disconnects_total{reason="STALE_CONNECTION"} %d
                nighthunt_gateway_disconnects_total{reason="CLIENT_CLOSE"} %d
                nighthunt_gateway_disconnects_total{reason="TRANSPORT_DROP"} %d
                """.formatted(
                activeConnections.getAsInt(),
                accepted.get(),
                rejectedTickets.get(),
                inboundFrames.get(),
                outboundFrames.get(),
                slowConsumers.get(),
                staleDisconnects.get(),
                clientDisconnects.get(),
                transportDrops.get()
        );
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
