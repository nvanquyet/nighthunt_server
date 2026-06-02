package com.nighthunt.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * AsyncConfig — thread pool executors for WebSocket event fan-out.
 *
 * <p>Two dedicated executors keep WebSocket work isolated from Tomcat worker threads:</p>
 * <ul>
 *   <li>{@code wsEventExecutor} — handles Redis Pub/Sub callbacks that include JPA queries.
 *       Using a separate pool ensures Redis listener threads are never blocked.
 *       Pool sizes tuned for 5,000–10,000 CCU workload (Phase 1 target).</li>
 *   <li>{@code broadcastExecutor} — used for parallel fan-out to multiple WebSocket sessions
 *       (e.g., broadcasting to all friends on status change).</li>
 * </ul>
 *
 * <h2>P1-4 changes</h2>
 * <ul>
 *   <li>Core/max pool sizes increased to match Phase-1 CCU target (10,000 CCU).</li>
 *   <li>Thread pool metrics registered with Micrometer — visible at
 *       {@code /actuator/metrics/executor.*}.</li>
 * </ul>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Executor for Redis event handlers in
     * {@link com.nighthunt.messaging.service.WebSocketEventSubscriber}.
     *
     * <p>Sizing rationale (Phase 1 — 10,000 CCU target):</p>
     * <ul>
     *   <li>Core 40 — handles sustained event volume without thread creation overhead.</li>
     *   <li>Max 100 — burst headroom for status-changed storms (e.g., server restart broadcast).</li>
     *   <li>Queue 2000 — prevents task rejection under heavy load; CallerRunsPolicy
     *       as final back-pressure backstop.</li>
     * </ul>
     */
    @Bean(name = "wsEventExecutor")
    public Executor wsEventExecutor(MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(40);
        executor.setMaxPoolSize(100);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("ws-event-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();

        // Register Micrometer metrics — exposed at /actuator/metrics/executor.*
        ExecutorServiceMetrics.monitor(
                meterRegistry,
                executor.getThreadPoolExecutor(),
                "ws-event-executor"
        );

        return executor;
    }

    /**
     * Executor for parallel WebSocket broadcast fan-out ({@code sendToUser}).
     *
     * <p>Sizing rationale:</p>
     * <ul>
     *   <li>Core 20 — sized for concurrent room broadcasts (up to 10 players × N rooms).</li>
     *   <li>Max 60 — burst headroom for friend-list broadcasts (up to ~200 friends each).</li>
     *   <li>Queue 5000 — large queue to absorb spikes without dropping messages.</li>
     * </ul>
     */
    @Bean(name = "broadcastExecutor")
    public Executor broadcastExecutor(MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(20);
        executor.setMaxPoolSize(60);
        executor.setQueueCapacity(5000);
        executor.setThreadNamePrefix("ws-broadcast-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();

        ExecutorServiceMetrics.monitor(
                meterRegistry,
                executor.getThreadPoolExecutor(),
                "ws-broadcast-executor"
        );

        return executor;
    }
}
