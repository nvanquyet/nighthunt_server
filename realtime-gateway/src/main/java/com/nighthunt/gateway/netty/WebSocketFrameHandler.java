package com.nighthunt.gateway.netty;

import com.nighthunt.gateway.connection.ConnectionRegistry;
import com.nighthunt.gateway.metrics.GatewayMetrics;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;

public final class WebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private static final String CONNECTED_FRAME = "{\"type\":\"connected\",\"data\":\"{\\\"message\\\":\\\"Realtime gateway connected\\\"}\"}";
    private static final String PONG_FRAME = "{\"type\":\"pong\"}";
    private static final io.netty.util.AttributeKey<String> DISCONNECT_REASON =
            io.netty.util.AttributeKey.valueOf("disconnectReason");

    private final ConnectionRegistry registry;
    private final GatewayMetrics metrics;

    public WebSocketFrameHandler(ConnectionRegistry registry, GatewayMetrics metrics) {
        this.registry = registry;
        this.metrics = metrics;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
        if (event instanceof IdleStateEvent) {
            context.channel().attr(DISCONNECT_REASON).set("STALE_CONNECTION");
            context.close();
            return;
        }
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            Long userId = context.channel().attr(TicketHandshakeHandler.USER_ID).get();
            String connectionId = context.channel().attr(TicketHandshakeHandler.CONNECTION_ID).get();
            String clientIp = context.channel().attr(TicketHandshakeHandler.CLIENT_IP).get();
            if (userId == null || connectionId == null) {
                context.close();
                return;
            }
            registry.register(userId, connectionId, clientIp, context.channel());
            registry.send(userId, CONNECTED_FRAME);
        }
        context.fireUserEventTriggered(event);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, WebSocketFrame frame) {
        metrics.inboundFrame();
        Long userId = context.channel().attr(TicketHandshakeHandler.USER_ID).get();
        if (userId == null) {
            context.close();
            return;
        }

        registry.refresh(userId, context.channel());
        if (frame instanceof PingWebSocketFrame ping) {
            context.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
        } else if (frame instanceof TextWebSocketFrame text && text.text().contains("\"ping\"")) {
            registry.send(userId, PONG_FRAME);
        } else if (frame instanceof CloseWebSocketFrame) {
            context.channel().attr(DISCONNECT_REASON).set("CLIENT_CLOSE");
            context.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        Long userId = context.channel().attr(TicketHandshakeHandler.USER_ID).get();
        if (userId != null) {
            String reason = context.channel().attr(DISCONNECT_REASON).get();
            String resolvedReason = reason == null ? "TRANSPORT_DROP" : reason;
            metrics.disconnected(resolvedReason);
            registry.unregister(userId, context.channel(), resolvedReason);
        }
        context.fireChannelInactive();
    }
}
