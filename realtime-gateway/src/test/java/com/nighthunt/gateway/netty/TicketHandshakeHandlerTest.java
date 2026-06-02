package com.nighthunt.gateway.netty;

import com.nighthunt.gateway.metrics.GatewayMetrics;
import com.nighthunt.gateway.redis.TicketStore;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class TicketHandshakeHandlerTest {
    @Test
    void acceptsValidOneTimeTicketAndAddsIdentity() throws IOException {
        try (GatewayMetrics metrics = new GatewayMetrics(0)) {
            TicketStore tickets = ticket -> CompletableFuture.completedFuture(OptionalLong.of(42L));
            EmbeddedChannel channel = new EmbeddedChannel(
                    new TicketHandshakeHandler(tickets, metrics, "/api/ws/game")
            );

            channel.writeInbound(request("/api/ws/game?ticket=opaque"));
            channel.runPendingTasks();

            FullHttpRequest forwarded = channel.readInbound();
            assertThat(forwarded).isNotNull();
            assertThat(channel.attr(TicketHandshakeHandler.USER_ID).get()).isEqualTo(42L);
            assertThat(channel.pipeline().get(TicketHandshakeHandler.class)).isNull();
            forwarded.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsMissingTicket() throws IOException {
        try (GatewayMetrics metrics = new GatewayMetrics(0)) {
            TicketStore tickets = ticket -> CompletableFuture.completedFuture(OptionalLong.empty());
            EmbeddedChannel channel = new EmbeddedChannel(
                    new TicketHandshakeHandler(tickets, metrics, "/api/ws/game")
            );

            channel.writeInbound(request("/api/ws/game"));
            FullHttpResponse response = channel.readOutbound();

            assertThat(response.status()).isEqualTo(HttpResponseStatus.UNAUTHORIZED);
            response.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsReusedTicket() throws IOException {
        java.util.concurrent.atomic.AtomicBoolean used = new java.util.concurrent.atomic.AtomicBoolean();
        try (GatewayMetrics metrics = new GatewayMetrics(0)) {
            TicketStore tickets = ticket -> CompletableFuture.completedFuture(
                    used.compareAndSet(false, true) ? OptionalLong.of(42L) : OptionalLong.empty()
            );

            EmbeddedChannel first = new EmbeddedChannel(
                    new TicketHandshakeHandler(tickets, metrics, "/api/ws/game")
            );
            first.writeInbound(request("/api/ws/game?ticket=one-time"));
            first.runPendingTasks();
            FullHttpRequest forwarded = first.readInbound();
            assertThat(forwarded).isNotNull();
            forwarded.release();
            first.finishAndReleaseAll();

            EmbeddedChannel second = new EmbeddedChannel(
                    new TicketHandshakeHandler(tickets, metrics, "/api/ws/game")
            );
            second.writeInbound(request("/api/ws/game?ticket=one-time"));
            second.runPendingTasks();
            FullHttpResponse response = second.readOutbound();
            assertThat(response.status()).isEqualTo(HttpResponseStatus.UNAUTHORIZED);
            response.release();
            second.finishAndReleaseAll();
        }
    }

    private static FullHttpRequest request(String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    }
}
