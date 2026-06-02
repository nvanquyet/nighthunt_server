package com.nighthunt.gateway.connection;

import com.nighthunt.gateway.event.GatewayEventPublisher;
import com.nighthunt.gateway.metrics.GatewayMetrics;
import com.nighthunt.gateway.redis.PresenceStore;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionRegistryTest {
    @Test
    void registerRefreshesPresenceThenPublishesConnectedEvent() throws Exception {
        FakePresenceStore presenceStore = new FakePresenceStore();
        RecordingGatewayEvents events = new RecordingGatewayEvents();

        try (GatewayMetrics metrics = new GatewayMetrics(0)) {
            ConnectionRegistry registry = new ConnectionRegistry(presenceStore, events, metrics, 1024);
            EmbeddedChannel channel = new EmbeddedChannel();

            registry.register(7L, "connection-new", "203.0.113.9", channel);

            assertThat(presenceStore.refreshed).containsExactly("7|connection-new|203.0.113.9");
            assertThat(events.published).containsExactly("connected|7|connection-new|203.0.113.9");
            channel.close();
        }
    }

    @Test
    void registerDoesNotPublishConnectedWhenPresenceRefreshFails() throws Exception {
        FakePresenceStore presenceStore = new FakePresenceStore();
        presenceStore.failRefresh = true;
        RecordingGatewayEvents events = new RecordingGatewayEvents();

        try (GatewayMetrics metrics = new GatewayMetrics(0)) {
            ConnectionRegistry registry = new ConnectionRegistry(presenceStore, events, metrics, 1024);
            EmbeddedChannel channel = new EmbeddedChannel();

            registry.register(7L, "connection-new", "203.0.113.9", channel);

            assertThat(events.published).isEmpty();
            channel.close();
        }
    }

    @Test
    void unregisterReleasesAndPublishesOnlyForCurrentChannel() throws Exception {
        FakePresenceStore presenceStore = new FakePresenceStore();
        RecordingGatewayEvents events = new RecordingGatewayEvents();

        try (GatewayMetrics metrics = new GatewayMetrics(0)) {
            ConnectionRegistry registry = new ConnectionRegistry(presenceStore, events, metrics, 1024);
            EmbeddedChannel channel = new EmbeddedChannel();

            registry.register(7L, "connection-new", "203.0.113.9", channel);
            events.published.clear();
            registry.unregister(7L, channel, "CLIENT_CLOSE");

            assertThat(presenceStore.released).containsExactly("7|connection-new");
            assertThat(events.published).containsExactly("disconnected|7|connection-new|203.0.113.9|CLIENT_CLOSE");
            channel.close();
        }
    }

    @Test
    void staleUnregisterDoesNotReleaseNewerConnection() throws Exception {
        FakePresenceStore presenceStore = new FakePresenceStore();
        RecordingGatewayEvents events = new RecordingGatewayEvents();

        try (GatewayMetrics metrics = new GatewayMetrics(0)) {
            ConnectionRegistry registry = new ConnectionRegistry(presenceStore, events, metrics, 1024);
            EmbeddedChannel current = new EmbeddedChannel();
            EmbeddedChannel stale = new EmbeddedChannel();

            registry.register(7L, "connection-new", "203.0.113.9", current);
            presenceStore.released.clear();
            events.published.clear();

            registry.unregister(7L, stale, "TRANSPORT_DROP");

            assertThat(presenceStore.released).isEmpty();
            assertThat(events.published).isEmpty();
            current.close();
            stale.close();
        }
    }

    private static final class FakePresenceStore implements PresenceStore {
        private final List<String> refreshed = new ArrayList<>();
        private final List<String> released = new ArrayList<>();
        private boolean failRefresh;

        @Override
        public CompletionStage<Void> refresh(long userId, String connectionId, String clientIp) {
            refreshed.add(userId + "|" + connectionId + "|" + clientIp);
            if (failRefresh) {
                return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> release(long userId, String connectionId) {
            released.add(userId + "|" + connectionId);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class RecordingGatewayEvents implements GatewayEventPublisher {
        private final List<String> published = new ArrayList<>();

        @Override
        public void connected(long userId, String connectionId, String clientIp) {
            published.add("connected|" + userId + "|" + connectionId + "|" + clientIp);
        }

        @Override
        public void disconnected(long userId, String connectionId, String clientIp, String reason) {
            published.add("disconnected|" + userId + "|" + connectionId + "|" + clientIp + "|" + reason);
        }
    }
}
