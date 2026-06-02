package com.nighthunt.gateway;

import com.nighthunt.gateway.connection.ConnectionRegistry;
import com.nighthunt.gateway.event.NatsGatewayEventPublisher;
import com.nighthunt.gateway.metrics.GatewayMetrics;
import com.nighthunt.gateway.nats.NatsOutboundSubscriber;
import com.nighthunt.gateway.netty.TicketHandshakeHandler;
import com.nighthunt.gateway.netty.WebSocketFrameHandler;
import com.nighthunt.gateway.redis.RedisPresenceStore;
import com.nighthunt.gateway.redis.RedisTicketStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RealtimeGatewayApplication {
    private static final Logger log = LoggerFactory.getLogger(RealtimeGatewayApplication.class);

    private RealtimeGatewayApplication() {
    }

    public static void main(String[] args) throws Exception {
        GatewayConfig config = GatewayConfig.fromEnvironment();
        RedisClient redisClient = RedisClient.create(config.redisUri());
        StatefulRedisConnection<String, String> redis = redisClient.connect();
        GatewayMetrics metrics = new GatewayMetrics(config.metricsPort());
        NatsGatewayEventPublisher gatewayEvents = new NatsGatewayEventPublisher(
                config.natsUrl(),
                config.natsSubjectPrefix(),
                config.gatewayId()
        );
        ConnectionRegistry registry = new ConnectionRegistry(
                new RedisPresenceStore(redis.async(), config.gatewayId(), config.presenceLease()),
                gatewayEvents,
                metrics,
                config.maxPendingOutboundBytes()
        );
        metrics.bindActiveConnections(registry::size);
        metrics.start();

        NatsOutboundSubscriber nats = new NatsOutboundSubscriber(
                config.natsUrl(),
                config.natsSubjectPrefix(),
                config.gatewayId(),
                registry
        );

        boolean epollEnabled = Epoll.isAvailable();
        EventLoopGroup boss = epollEnabled ? new EpollEventLoopGroup(1) : new NioEventLoopGroup(1);
        EventLoopGroup workers = epollEnabled ? new EpollEventLoopGroup() : new NioEventLoopGroup();
        Class<? extends ServerChannel> serverChannel = epollEnabled
                ? EpollServerSocketChannel.class
                : NioServerSocketChannel.class;
        Channel server = new ServerBootstrap()
                .group(boss, workers)
                .channel(serverChannel)
                .option(ChannelOption.SO_BACKLOG, config.acceptBacklog())
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32 * 1024, 64 * 1024))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(config.maxFrameBytes()))
                                .addLast(new TicketHandshakeHandler(
                                        new RedisTicketStore(redis.async()),
                                        metrics,
                                        config.wsPath()
                                ))
                                .addLast(new WebSocketServerProtocolHandler(
                                        WebSocketServerProtocolConfig.newBuilder()
                                                .websocketPath(config.wsPath())
                                                .checkStartsWith(true)
                                                .maxFramePayloadLength(config.maxFrameBytes())
                                                .build()
                                ))
                                .addLast(new IdleStateHandler(
                                        Math.toIntExact(config.heartbeatTimeout().toSeconds()),
                                        0,
                                        0
                                ))
                                .addLast(new WebSocketFrameHandler(registry, metrics));
                    }
                })
                .bind(config.port())
                .sync()
                .channel();

        log.info("Realtime gateway {} listening on :{} (metrics :{}, transport {}, backlog {})",
                config.gatewayId(), config.port(), config.metricsPort(),
                epollEnabled ? "epoll" : "nio", config.acceptBacklog());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            workers.shutdownGracefully();
            boss.shutdownGracefully();
            try {
                nats.close();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            try {
                gatewayEvents.close();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            metrics.close();
            redis.close();
            redisClient.shutdown();
        }, "gateway-shutdown"));

        server.closeFuture().sync();
    }
}
