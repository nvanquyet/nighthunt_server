package com.nighthunt.gateway.netty;

import com.nighthunt.gateway.metrics.GatewayMetrics;
import com.nighthunt.gateway.redis.TicketStore;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

import java.util.List;
import java.util.UUID;

public final class TicketHandshakeHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    public static final AttributeKey<Long> USER_ID = AttributeKey.valueOf("userId");
    public static final AttributeKey<String> CONNECTION_ID = AttributeKey.valueOf("connectionId");
    public static final AttributeKey<String> CLIENT_IP = AttributeKey.valueOf("clientIp");

    private final TicketStore ticketStore;
    private final GatewayMetrics metrics;
    private final String wsPath;

    public TicketHandshakeHandler(TicketStore ticketStore, GatewayMetrics metrics, String wsPath) {
        this.ticketStore = ticketStore;
        this.metrics = metrics;
        this.wsPath = wsPath;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
        QueryStringDecoder query = new QueryStringDecoder(request.uri());
        if (request.method() != HttpMethod.GET || !wsPath.equals(query.path())) {
            reject(context, request, HttpResponseStatus.NOT_FOUND);
            return;
        }

        String ticket = first(query.parameters().get("ticket"));
        if (ticket == null) {
            metrics.rejectedTicket();
            reject(context, request, HttpResponseStatus.UNAUTHORIZED);
            return;
        }

        FullHttpRequest retained = request.retain();
        ticketStore.consume(ticket).whenComplete((result, error) ->
                context.executor().execute(() -> {
                    if (error != null || result == null || result.isEmpty()) {
                        metrics.rejectedTicket();
                        reject(context, retained, HttpResponseStatus.UNAUTHORIZED);
                        ReferenceCountUtil.release(retained);
                        return;
                    }

                    context.channel().attr(USER_ID).set(result.getAsLong());
                    context.channel().attr(CONNECTION_ID).set(UUID.randomUUID().toString());
                    context.channel().attr(CLIENT_IP).set(resolveClientIp(context, request));
                    context.pipeline().remove(this);
                    context.fireChannelRead(retained);
                }));
    }

    private static String first(List<String> values) {
        return values == null || values.isEmpty() || values.get(0).isBlank() ? null : values.get(0);
    }

    private static String resolveClientIp(ChannelHandlerContext context, FullHttpRequest request) {
        String forwardedFor = request.headers().get("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",", 2)[0].trim();
        }
        return context.channel().remoteAddress() == null
                ? ""
                : context.channel().remoteAddress().toString();
    }

    private static void reject(
            ChannelHandlerContext context,
            FullHttpRequest request,
            HttpResponseStatus status
    ) {
        if (HttpUtil.is100ContinueExpected(request)) {
            context.write(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
        }
        byte[] bytes = status.reasonPhrase().getBytes(CharsetUtil.UTF_8);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(bytes)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        context.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }
}
