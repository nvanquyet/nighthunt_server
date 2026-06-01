package com.nighthunt.config;

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
 *       Using a separate pool ensures Redis listener threads are never blocked.</li>
 *   <li>{@code broadcastExecutor} — used for parallel fan-out to multiple WebSocket sessions
 *       (e.g., broadcasting to all friends on status change).</li>
 * </ul>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Executor for Redis event handlers in {@link com.nighthunt.messaging.service.WebSocketEventSubscriber}.
     * Core 20, max 50, queue 500 — sized for expected friend/party event volume.
     * CallerRunsPolicy prevents task drops under heavy load (backs pressure to Redis listener).
     */
    @Bean(name = "wsEventExecutor")
    public Executor wsEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(20);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("ws-event-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    /**
     * Executor for parallel WebSocket broadcasts (sendToUser fan-out).
     * Core 10, max 30, queue 1000 — tuned for small match sizes (4–10 players) and
     * friend lists up to a few hundred.
     */
    @Bean(name = "broadcastExecutor")
    public Executor broadcastExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(30);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("ws-broadcast-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
