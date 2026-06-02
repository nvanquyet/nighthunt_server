# Night Hunt Backend - Architecture Migration Analysis 2026

> Ngày audit: 2026-06-02
> Phạm vi hiện tại: backend, realtime presence/lobby/chat/notification, hạ tầng single-node và kế hoạch mở rộng.
> Chưa triển khai trong tài liệu này: pipeline nhiều VPS Dedicated Server và chọn region theo ping. Phần đó được mô tả trước để kiến trúc hiện tại không chặn việc phát triển sau.
>
> Trạng thái runtime sau implementation slice, sơ đồ Mermaid và capacity model:
> [`Realtime Architecture Runtime Guide 2026.md`](./Realtime%20Architecture%20Runtime%20Guide%202026.md)

## 1. Kết luận chốt

Không tiếp tục hướng "thêm WebFlux vào Spring Boot đang chạy Tomcat" như một migration hoàn chỉnh.

Kiến trúc mục tiêu nên tách thành hai lớp:

1. **Data plane realtime**: một process `realtime-gateway` độc lập, viết bằng Netty thuần, chỉ giữ kết nối WebSocket và route event.
2. **Control plane business**: `business-api` Spring Boot phục vụ REST, transaction MySQL, auth, inventory, shop, quest, party, room và matchmaking orchestration.

Hạ tầng event và state:

- **NATS Core**: fan-out realtime có thể reconcile lại, ví dụ presence, lobby refresh hint, notification online.
- **NATS JetStream**: command/event cần durability, retry và consumer acknowledgement, ví dụ invite, match allocation, state transition, audit.
- **Redis**: session cache, one-time WebSocket ticket, presence lease, route directory, cache và rate limit. Không dùng Redis Pub/Sub làm durable event bus.
- **MySQL**: source of truth cho dữ liệu bền vững.
- **Outbox pattern**: publish event bền vững sau transaction MySQL, tránh dual-write không nhất quán.

```text
Unity Client
   |
   | HTTPS REST
   v
Nginx / Edge ---------------------> business-api (Spring Boot REST)
   |
   | WSS
   v
realtime-gateway (Netty standalone)
   |             |
   |             +---------------> Redis
   |                                ticket, session, presence lease,
   |                                user -> gateway route, rate limit
   |
   +-----------------------------> NATS Core + JetStream
                                    |
                                    +--> business-api event consumers
                                    +--> ds-orchestrator
                                    +--> future gateway nodes

business-api --------------------> MySQL
ds-orchestrator -----------------> Docker / future VM or container platform
Dedicated Server fleet ----------> UDP gameplay, tách khỏi lobby WebSocket
```

Đây là lựa chọn phù hợp với mục tiêu CCU lớn nhất có thể trên một node nhưng vẫn mở đường scale ngang. Không có con số `20,000`, `50,000` hay `100,000` CCU nào được coi là đúng trước khi chạy capacity test trên đúng cấu hình máy, payload, TLS, fan-out và tỷ lệ reconnect thực tế.

## 2. Audit hệ thống hiện tại

### 2.1 Stack đang có

Backend hiện tại là modular monolith:

| Thành phần | Hiện trạng |
|---|---|
| Runtime | Java 17, Spring Boot `3.2.5` |
| REST | `spring-boot-starter-web`, embedded Tomcat |
| WebSocket cũ | Spring Servlet WebSocket, đang được deprecate |
| Migration đang làm dở | Thêm `spring-boot-starter-webflux` và `ReactiveGameWebSocketHandler` |
| Database | MySQL 8, JPA/Hibernate, Flyway |
| Cache/session | Redis qua `RedisTemplate` |
| Broker hiện tại | Redis Pub/Sub |
| TLS edge | Nginx |
| Custom game relay | Python UDP relay container |
| Ranked gameplay | Dedicated Server containers, FishNet UDP |
| Load test | JMeter REST, Python HTTPS/WSS suite, mock DS capacity test, k6 WS script đang làm dở |

Evidence trong repo:

- `build.gradle:26-38`: khai báo đồng thời MVC, Servlet WebSocket và WebFlux.
- `src/main/java/com/nighthunt/config/ReactiveWebSocketConfig.java:16-23`: comment giả định Tomcat REST và Reactor Netty WS cùng chạy trong một process.
- `src/main/java/com/nighthunt/session/adapter/RedisSessionStore.java:21-98`: dùng synchronous `RedisTemplate`.
- `src/main/java/com/nighthunt/friend/service/PlayerStatusService.java:45-75`: connect/disconnect cập nhật JPA rồi publish event.
- `docker-compose.yml:23,51,77,221`: MySQL, Redis, phpMyAdmin và relay management port đang publish ra host.
- `docker-compose.yml:149,261`: backend và dashboard đều mount Docker socket.
- `docker/nginx/nginx.conf:8`: Nginx mới có `worker_connections 1024`.
- `docker/nginx/nginx.conf:21`: access log dùng `$request`, có thể ghi cả query string.
- `load-tests/k6/ws_load_test.js:34`: k6 WS URL thiếu `/api`.
- `load-tests/k6/ws_load_test.js:34`: mọi VU dùng chung một JWT.

### 2.2 Migration WebFlux hiện tại chưa đúng

Giả định hiện tại là:

```text
Tomcat phục vụ REST
Reactor Netty phục vụ WS
cùng nằm trong một Spring Boot process
```

Giả định này không đúng với cách Spring Boot auto-configuration hoạt động. Khi Spring MVC và WebFlux cùng tồn tại trên classpath, Spring Boot ưu tiên MVC. Việc thêm `spring-boot-starter-webflux` không tự khởi động thêm một Reactor Netty server song song với Tomcat.

Tài liệu Spring Boot cũng nêu rõ trường hợp thêm cả hai starter thường vẫn auto-configure Spring MVC, do nhiều ứng dụng MVC chỉ thêm WebFlux để dùng reactive `WebClient`.

Hệ quả:

- `ReactiveWebSocketConfig` không tạo ra một gateway Netty độc lập.
- `spring.webflux.netty.worker-count` trong `application.yml` không phải bằng chứng Netty worker đã được áp dụng.
- Tuning Tomcat và tuning giả định Netty trong cùng file làm capacity model không rõ ràng.
- Không thể tuyên bố đã migrate WebSocket sang Reactor Netty trước khi chứng minh runtime server, thread model và benchmark thực tế.

### 2.3 Hot path vẫn có blocking I/O

Ngay cả khi chạy được trên một reactive server, handler hiện tại vẫn chưa phù hợp với event-loop:

```text
WS handshake
  -> RedisSessionStore.getSessionId()       synchronous RedisTemplate
  -> RedisSessionStore.isForceLogout()      synchronous RedisTemplate
  -> PlayerStatusService.setOnline()        JPA + MySQL
  -> findCurrentRoomId()                    JPA + MySQL
  -> RoomResponseAssembler.toResponseById() có thể tiếp tục query DB
```

`Lettuce` hỗ trợ reactive API không đồng nghĩa với `RedisTemplate` trở thành non-blocking. Spring Data Redis có API riêng là `ReactiveRedisTemplate` cho reactive usage.

Gateway production không được gọi JPA, MySQL hoặc synchronous Redis client trong Netty event-loop.

### 2.4 Build hiện tại đang fail

Lệnh kiểm tra:

```powershell
.\gradlew.bat test
```

Kết quả tại thời điểm audit:

```text
ReactiveGameWebSocketHandler.java:183
  no suitable method found for onBackpressureBuffer(...)

GameWebSocketHandler.java:59
  does not override broadcastToUsers(...)
```

Đây là lỗi của worktree hiện tại, chưa phải lỗi do tài liệu migration này tạo ra.

### 2.5 Redis Pub/Sub không đủ cho event quan trọng

Redis Pub/Sub có delivery semantic `at-most-once`: subscriber mất kết nối hoặc xử lý lỗi thì message có thể mất vĩnh viễn. Vì vậy:

- Có thể dùng tạm cho push hint không quan trọng.
- Không được dùng làm nguồn duy nhất cho invite, match transition, inventory, reward, payment hoặc audit.
- Dead-letter logging không biến Pub/Sub thành durable queue.

Redis Streams tốt hơn Pub/Sub cho durability nếu muốn giảm số dependency. Với kiến trúc dài hạn, NATS Core + JetStream rõ ràng hơn vì tách được realtime fan-out và durable workflow.

### 2.6 Load test hiện tại chưa chứng minh CCU WebSocket

Các bài test hiện có hữu ích nhưng cần đọc đúng ý nghĩa:

| Test | Chứng minh được | Chưa chứng minh được |
|---|---|---|
| JMeter REST | Throughput và latency REST theo VU | Số kết nối WS giữ đồng thời |
| `run_full_https_suite.py` | HTTPS/WSS functional flow số lượng nhỏ | Capacity WS lớn |
| `run_capacity_test.py` | Backend track được nhiều virtual DS heartbeat | RAM/CPU thật của Unity DS container |
| `run_fleet_test.py` | Lifecycle mock container và failure injection | Số trận gameplay thật trên VPS |
| `k6/ws_load_test.js` | Ý tưởng ramp WS | Chưa hợp lệ để kết luận vì URL sai và reuse cùng JWT |

`run_capacity_test.py` nói rõ mỗi virtual DS chỉ là một thread gửi HTTP, không phải một Unity Dedicated Server thật.

## 3. Những nhận định cần sửa từ proposal cũ

| Nhận định cũ | Kết luận audit |
|---|---|
| Bottleneck nhiều khả năng chỉ là Tomcat | Chưa đủ evidence. DB rate limit, JPA trong status update, synchronous Redis, Nginx limit và fan-out đều có thể bottleneck trước. |
| Thêm WebFlux để Tomcat REST + Reactor Netty WS trong một app | Không đúng theo Spring Boot auto-configuration mặc định. |
| RedisTemplate an toàn trên event-loop vì Lettuce non-blocking | Không đúng. `RedisTemplate` là synchronous abstraction. |
| Tăng worker thread lên 16 sẽ scale tốt hơn | Không được chọn bằng cảm tính. Worker count phải benchmark theo CPU, payload và blocking audit. |
| Redis Pub/Sub đủ cho notification | Chỉ đủ cho ephemeral hint. Event quan trọng phải durable hoặc client phải reconcile. |
| 10k WS VU đã có test | Chưa. Script k6 hiện tại dùng cùng JWT cho mọi VU và sai path so với Nginx production. |
| 200 concurrent DS đã chứng minh DS fleet capacity | Chưa. Đây là virtual DS heartbeat capacity, không phải Unity DS resource capacity. |

## 4. Kiến trúc mục tiêu

### 4.1 Nguyên tắc

1. Gameplay UDP và lobby realtime WebSocket là hai data plane khác nhau.
2. Gateway không chứa business transaction.
3. Gateway không truy cập MySQL.
4. Gateway không chạy JPA.
5. Gateway không block event-loop.
6. Push realtime không thay thế source of truth.
7. Event quan trọng phải durable, idempotent và retry được.
8. Redis là state/cache store, không phải database chính và không phải durable broker mặc định.
9. Single-node đầu tiên phải giữ đúng boundary để scale ngang không cần rewrite.
10. Capacity là kết quả benchmark, không phải hằng số ghi tay trong config.

### 4.2 Service boundary

| Service | Trách nhiệm | Không được làm |
|---|---|---|
| `edge-nginx` | TLS, REST proxy, WS proxy ban đầu, header normalization, coarse rate limit | Không expose DB, Redis, NATS management |
| `realtime-gateway` | WS handshake, one-time ticket, connection registry, heartbeat, backpressure, route event | Không JPA, không MySQL, không inventory transaction |
| `business-api` | REST business, auth, party, room, profile, inventory, shop, quest | Không giữ socket map |
| `outbox-publisher` | Đọc MySQL outbox và publish durable NATS event | Không thay đổi business state |
| `ds-orchestrator` | Allocate, drain, reclaim DS; giữ Docker/VM privilege | Không public trực tiếp |
| `nats` | Core event fan-out và JetStream durable workflow | Không lưu source of truth business |
| `redis` | Ticket, lease, cache, routing, rate limit | Không làm durable source of truth |
| `mysql` | Durable business state | Không phục vụ hot path WS |
| `relay` | Custom game UDP relay | Management API chỉ internal |

### 4.3 Realtime gateway

Khuyến nghị: standalone Java service dùng Netty thuần.

Lý do chọn Netty thay vì tiếp tục WebFlux:

- Tối ưu trực tiếp connection lifecycle, `Channel`, `EventLoop`, buffer và water mark.
- Ít abstraction hơn cho một service chỉ có một nhiệm vụ.
- Không kéo Spring MVC, JPA, Hibernate hoặc business bean vào data plane.
- Dễ benchmark và giới hạn memory theo connection.
- Phù hợp với codebase Java hiện tại và giảm chi phí học stack mới.

Gateway chỉ làm:

```text
Accept WSS
  -> validate Origin / protocol version
  -> consume one-time ws ticket
  -> attach immutable ConnectionContext
  -> register channel in memory
  -> write presence lease + route directory asynchronously
  -> subscribe user to local route
  -> receive ping / allowed client command
  -> validate schema + rate limit
  -> publish command to NATS
  -> receive outbound NATS event
  -> send frame if channel writable
  -> drop or disconnect slow consumer according to event class
```

Gateway không làm:

```text
JPA query
MySQL transaction
RoomResponseAssembler DB lookup
Inventory mutation
Party membership mutation
Matchmaking algorithm
Docker command
Blocking RedisTemplate call
```

### 4.4 WebSocket authentication

Không đưa access JWT dài hạn vào URL:

```text
wss://host/api/ws/game?token=<access-jwt>
```

URL có thể xuất hiện trong reverse-proxy access log. Nginx hiện tại log `$request`, vì vậy query string có nguy cơ bị ghi log.

Thiết kế mới:

```text
1. Client login REST -> access JWT + refresh token
2. Client POST /api/realtime/tickets với Authorization: Bearer <JWT>
3. business-api tạo random one-time ticket, TTL 30-60s trong Redis
4. Client connect WSS /ws?v=1&ticket=<opaque-random-ticket>
5. gateway consume ticket atomically bằng GETDEL hoặc Lua
6. ticket không reuse được và hết hạn rất nhanh
```

Ticket payload tối thiểu:

```json
{
  "userId": 123,
  "sessionId": "opaque-session-id",
  "deviceId": "device-id",
  "expiresAt": "2026-06-02T12:00:30Z",
  "protocolVersion": 1
}
```

Sau handshake:

- Gateway giữ `ConnectionContext` immutable trong channel attribute.
- Gateway refresh presence lease theo heartbeat.
- Logout hoặc force logout publish event đến gateway và revoke session trong Redis.
- Reconnect phải xin ticket mới.

### 4.5 Protocol

Phase đầu giữ JSON để migration an toàn:

```json
{
  "v": 1,
  "type": "ping",
  "requestId": "uuid",
  "payload": {}
}
```

Outbound event:

```json
{
  "v": 1,
  "type": "party.member_joined",
  "eventId": "uuid",
  "seq": 42,
  "payload": {}
}
```

Sau khi JSON v1 ổn định, benchmark thêm binary v2 bằng Protobuf hoặc MessagePack. Không đổi protocol chỉ vì kỳ vọng lý thuyết; đo CPU, bandwidth và client complexity trước.

### 4.6 Event classification

| Event class | Ví dụ | Transport | Khi mất event |
|---|---|---|---|
| Ephemeral | presence heartbeat, typing, lobby refresh hint | NATS Core | Client reconcile bằng snapshot |
| Durable notification | friend invite, party invite, moderation notice | JetStream | Retry, ack, idempotent consumer |
| Durable workflow | match allocated, DS ready, match ended | JetStream | Retry, audit, dead-letter policy |
| Transactional state | inventory, shop purchase, reward, ELO | MySQL + outbox + JetStream | Source of truth là MySQL |
| Gameplay realtime | movement, shooting, snapshot | Dedicated Server UDP/FishNet | Không đi qua gateway lobby |

### 4.7 NATS subject convention

```text
rt.user.<userId>.event
rt.gateway.<gatewayId>.deliver
rt.presence.changed
cmd.party.<aggregateId>
evt.party.<aggregateId>
cmd.matchmaking.enqueue
evt.match.allocated
evt.ds.ready
evt.match.ended
dlq.<consumerName>
```

Rules:

- Prefix theo environment: `dev.*`, `staging.*`, `prod.*`.
- Gateway chỉ subscribe/publish subject tối thiểu cần thiết.
- Durable consumer có explicit ack, retry backoff và max delivery.
- Consumer ghi nhận `eventId` đã xử lý để idempotent.
- Payload có `schemaVersion`, `eventId`, `occurredAt`, `correlationId`.

### 4.8 Redis key convention

```text
ws:ticket:<ticket>                    TTL 30-60s, consume once
session:<userId>                      TTL theo session policy
presence:user:<userId>                TTL 30-90s
route:user:<userId>                   gatewayId + connectionId, TTL lease
gateway:<gatewayId>:heartbeat         TTL 30s
ratelimit:ws:connect:ip:<ip>          token bucket
ratelimit:ws:message:user:<userId>    token bucket
cache:friend_ids:<userId>             TTL ngắn + explicit eviction
cache:party_members:<partyId>         TTL ngắn + explicit eviction
```

Rules:

- Dùng Redis command atomic hoặc Lua cho consume ticket, lease và token bucket.
- Presence phải là lease có TTL, không phải boolean sống mãi.
- Cache miss không được khiến gateway query DB; gateway publish reconcile request hoặc client gọi snapshot API.

### 4.9 Outbox pattern

Business transaction:

```text
BEGIN
  update party_members
  insert outbox_event(event_id, aggregate_id, type, payload, status=PENDING)
COMMIT
```

Publisher:

```text
poll pending outbox
  -> publish JetStream
  -> mark SENT
```

Consumer:

```text
receive event
  -> ignore if event_id already processed
  -> apply side effect
  -> ack
```

Không publish broker message trước khi MySQL commit. Không dùng Redis Pub/Sub để giả lập outbox.

## 5. Clean Code và SOLID boundary

### 5.1 Gateway package layout

```text
realtime-gateway/
  bootstrap/
    GatewayApplication
    GatewayConfig
  domain/
    ConnectionContext
    OutboundEvent
    InboundCommand
    EventDeliveryClass
  application/
    GatewayConnectionService
    GatewayDeliveryService
    GatewayPresenceService
  port/
    inbound/
      FrameDecoder
      CommandHandler
    outbound/
      TicketStore
      PresenceDirectory
      EventBus
      ConnectionRegistry
      GatewayMetrics
  adapter/
    inbound/netty/
      NettyServer
      WsHandshakeHandler
      WsFrameHandler
      SlowConsumerHandler
    outbound/redis/
      RedisTicketStore
      RedisPresenceDirectory
    outbound/nats/
      NatsEventBus
```

### 5.2 Business API ports

```text
business-api/
  auth/
    application/
    domain/
    port/
    adapter/
  party/
  room/
  matchmaking/
  inventory/
  realtime/
    port/
      RealtimeEventPublisher
      RealtimeTicketIssuer
    adapter/
      NatsRealtimeEventPublisher
      RedisRealtimeTicketIssuer
  outbox/
    OutboxRepository
    OutboxPublisher
```

### 5.3 Contract bắt buộc

```java
public interface RealtimeTicketStore {
    Optional<RealtimeTicket> consume(String opaqueTicket);
}

public interface PresenceDirectory {
    CompletionStage<Void> renewLease(ConnectionContext context);
    CompletionStage<Void> removeLease(ConnectionContext context);
    CompletionStage<Optional<Route>> findRoute(long userId);
}

public interface RealtimeEventBus {
    CompletionStage<Void> publishEphemeral(RealtimeEvent event);
    CompletionStage<Void> publishDurable(RealtimeEvent event);
}

public interface ConnectionRegistry {
    Optional<ConnectionHandle> find(long userId);
    RegisterResult register(ConnectionContext context, ConnectionHandle handle);
    boolean removeIfCurrent(ConnectionContext context);
}
```

### 5.4 Rules review

- Không inject repository vào Netty handler.
- Không inject `RedisTemplate` synchronous vào gateway.
- Không inject Docker client vào public REST API.
- Không gửi event critical bằng fire-and-forget.
- Không tạo unbounded queue.
- Không log JWT, refresh token, one-time ticket, DS secret hoặc Redis password.
- Không dùng magic number cho payload size, idle timeout, queue budget và rate limit.
- Mọi event durable có `eventId` và test idempotency.
- Mọi adapter có interface port để thay Redis/NATS implementation mà không sửa application logic.

## 6. Single-node production topology

Mục tiêu đầu tiên là một máy chủ chạy đúng boundary production. Sau đó mới scale ngang.

```text
Internet
  |
  +--> 80/tcp, 443/tcp
         |
         v
       nginx
         |
         +--> business-api:8080       internal only
         +--> realtime-gateway:8082   internal only

Internal network
  +--> mysql:3306             internal only
  +--> redis:6379             internal only
  +--> nats:4222              internal only
  +--> nats-monitor:8222      internal/admin only
  +--> ds-orchestrator        internal only, privileged boundary
  +--> relay:7776             internal only

Public UDP
  +--> relay session ports
  +--> dedicated server gameplay ports
```

### 6.1 Port exposure policy

Production compose không publish:

```text
3306 MySQL
6379 Redis
8081 phpMyAdmin
7776 relay management API
8222 NATS monitoring
business-api internal port
gateway internal port nếu Nginx proxy WSS
```

Chỉ publish:

```text
80/tcp
443/tcp
UDP relay ports cần thiết
UDP Dedicated Server ports cần thiết
dashboard chỉ qua VPN hoặc allowlist
```

### 6.2 Docker privilege

Hiện tại backend và dashboard mount `/var/run/docker.sock`. Docker socket tương đương quyền quản trị host.

Migration:

```text
public business-api
  -> durable command evt.ds.allocate
  -> ds-orchestrator internal
  -> Docker socket / VM API
```

Chỉ `ds-orchestrator` được quyền quản lý container. Dashboard gọi API admin đã auth vào orchestrator hoặc publish command, không mount socket.

### 6.3 Nginx

Nginx hiện tại có `worker_connections 1024`, không đủ cho mục tiêu WS lớn. Mỗi WebSocket proxy thường tiêu tốn connection phía client và connection upstream.

Checklist tuning phải benchmark:

```nginx
worker_processes auto;
worker_rlimit_nofile 200000;

events {
    use epoll;
    worker_connections 100000;
    multi_accept on;
}
```

Không copy số này thẳng vào production nếu OS limit chưa đổi. Kiểm tra:

```bash
ulimit -n
sysctl fs.file-max
sysctl net.core.somaxconn
sysctl net.ipv4.ip_local_port_range
```

Hai mode cần benchmark:

| Mode | Ưu điểm | Khi dùng |
|---|---|---|
| Nginx TLS termination -> Netty WS | Dễ vận hành, cùng domain 443 | Mặc định phase đầu |
| Netty TLS trực tiếp hoặc L4 proxy | Ít proxy overhead hơn | Chỉ chọn nếu benchmark chứng minh cần |

## 7. Security checklist

### 7.1 P0 trước production

- [ ] Không expose MySQL `3306` ra Internet.
- [ ] Không expose Redis `6379` ra Internet.
- [ ] Không expose phpMyAdmin production; nếu bắt buộc thì đặt sau VPN, SSO hoặc allowlist.
- [ ] Không expose relay management `7776`.
- [ ] Không expose NATS client và monitoring port ra Internet.
- [ ] Bỏ Docker socket khỏi `business-api`.
- [ ] Bỏ Docker socket khỏi `dashboard`.
- [ ] Chuyển Docker privilege sang `ds-orchestrator`.
- [ ] Không log access JWT trong query string.
- [ ] Mask hoặc bỏ query string khỏi WS access log trong giai đoạn tương thích cũ.
- [ ] Thay JWT query param bằng one-time WS ticket.
- [ ] Actuator chỉ bind internal management port hoặc chặn ở Nginx/firewall.
- [ ] Dashboard admin chỉ cho phép VPN/allowlist và RBAC.
- [ ] Không trả password hash qua admin API.
- [ ] Redis bật ACL/password; rotate secret định kỳ.
- [ ] NATS bật credentials, subject permissions và TLS nội bộ nếu đi qua network không tin cậy.

### 7.2 WS abuse prevention

- [ ] Giới hạn handshake theo IP, account, device và subnet.
- [ ] Giới hạn concurrent connection theo account.
- [ ] Giới hạn concurrent connection theo IP có policy riêng cho NAT/mobile carrier.
- [ ] Global connection cap để fail fast trước khi OOM.
- [ ] Max frame bytes theo message type.
- [ ] Chỉ accept text hoặc binary type đã khai báo.
- [ ] JSON parser strict: reject malformed JSON, unknown root fields nếu protocol yêu cầu.
- [ ] Rate limit theo command type, không chỉ rate limit tổng.
- [ ] Heartbeat timeout có jitter để tránh cùng lúc reconnect.
- [ ] Client reconnect dùng exponential backoff + jitter.
- [ ] Detect slow consumer bằng Netty channel writability.
- [ ] Outbound buffer bounded theo bytes, không chỉ theo số message.
- [ ] Event critical không drop; disconnect slow consumer và buộc snapshot reconcile.
- [ ] Event ephemeral được phép coalesce hoặc drop có metric.

### 7.3 DS và relay

- [ ] Mỗi DS có boot credential riêng, TTL ngắn, rotate được.
- [ ] Lưu hash của secret nếu cần persistence.
- [ ] DS heartbeat validate `serverId`, secret, match assignment và source policy.
- [ ] DS register token consume once.
- [ ] Management API relay chỉ internal network.
- [ ] Relay token có entropy đủ lớn, TTL và revoke.
- [ ] Tách UDP relay port pool khỏi DS port pool.
- [ ] Thêm anti-amplification và packet size limit cho relay UDP.
- [ ] Thêm rate limit theo source IP/token cho relay.

### 7.4 Failure scenarios bảo mật

- [ ] WS connect không ticket -> reject.
- [ ] Ticket expired -> reject.
- [ ] Ticket reuse -> reject.
- [ ] Ticket user A dùng với session B -> reject.
- [ ] JWT hoặc ticket xuất hiện trong access log -> test fail.
- [ ] 10k connect attempt từ một IP -> throttled, gateway không OOM.
- [ ] 1 MB frame -> reject/close, gateway vẫn healthy.
- [ ] Malformed JSON flood -> throttled, không leak stacktrace.
- [ ] Ping flood -> throttled.
- [ ] Unauthorized NATS subject publish -> reject.
- [ ] Redis outage -> gateway degrade theo policy, không event-loop stall.
- [ ] NATS outage -> durable publisher retry, không mất outbox.

## 8. Observability

### 8.1 Gateway metrics

```text
nighthunt_gateway_connections_active
nighthunt_gateway_connections_opened_total
nighthunt_gateway_connections_closed_total{reason}
nighthunt_gateway_handshake_duration_seconds
nighthunt_gateway_handshake_rejected_total{reason}
nighthunt_gateway_frames_in_total{type}
nighthunt_gateway_frames_out_total{type}
nighthunt_gateway_frame_bytes_in_total
nighthunt_gateway_frame_bytes_out_total
nighthunt_gateway_slow_consumers_total
nighthunt_gateway_outbound_queue_bytes{gatewayId}
nighthunt_gateway_events_dropped_total{class,type}
nighthunt_gateway_event_loop_lag_seconds
nighthunt_gateway_presence_lease_failures_total
nighthunt_gateway_nats_publish_failures_total
nighthunt_gateway_redis_command_duration_seconds
```

### 8.2 Business metrics

```text
nighthunt_api_http_requests
nighthunt_api_http_latency
nighthunt_api_db_pool_active
nighthunt_api_db_pool_pending
nighthunt_outbox_pending
nighthunt_outbox_publish_failures
nighthunt_nats_consumer_redeliveries
nighthunt_nats_consumer_dlq_total
nighthunt_matchmaking_queue_depth{mode,region}
nighthunt_ds_active{region,status}
nighthunt_ds_allocate_latency
nighthunt_ds_heartbeat_age_seconds
```

### 8.3 Alert baseline

- [ ] Gateway FD usage > 70%.
- [ ] Gateway heap/direct memory > 75%.
- [ ] Event-loop lag p99 vượt SLO.
- [ ] WS handshake reject tăng đột biến.
- [ ] Slow consumer tăng đột biến.
- [ ] Critical event drop luôn bằng `0`.
- [ ] Redis latency p99 tăng đột biến.
- [ ] NATS redelivery hoặc DLQ > baseline.
- [ ] MySQL Hikari pending connections > `0` kéo dài.
- [ ] Outbox pending tăng liên tục.
- [ ] DS heartbeat stale vượt threshold.

## 9. Load test strategy

### 9.1 Tooling

| Tool | Vai trò |
|---|---|
| k6 `k6/websockets` | WS connection, ping/pong, event latency, reconnect storm |
| JMeter | REST mixed workload, auth flood, social reads, room lifecycle |
| Python full suite | Functional HTTPS/WSS, integration orchestration |
| Python virtual DS harness | DS control-plane heartbeat capacity |
| Real Unity headless DS harness | RAM/CPU và số trận gameplay thật |
| Prometheus + Grafana | Correlate latency, CPU, heap, FD, Redis, NATS, MySQL |
| Toxiproxy hoặc network fault injection tương đương | Redis/NATS latency, disconnect, packet loss |

JMeter không cần gánh toàn bộ WS test. Giữ JMeter cho REST và dùng k6 script-based test cho WS.

### 9.2 Sửa test harness hiện tại trước khi dùng

- [ ] Đổi k6 path từ `/ws/game` thành endpoint gateway thực tế.
- [ ] Không reuse một JWT hoặc một WS ticket cho mọi VU.
- [ ] Pre-generate account/session/ticket riêng cho từng VU.
- [ ] Nếu policy single-device bật, một user chỉ có một active WS.
- [ ] Tách `idle connection capacity` khỏi `message throughput capacity`.
- [ ] Tách `virtual DS heartbeat test` khỏi `real Unity DS resource test`.
- [ ] Chạy load generator ngoài VPS backend.
- [ ] Với CCU lớn, dùng nhiều generator/source IP để tránh giới hạn socket và ephemeral port của máy phát tải.
- [ ] Gắn test run ID vào metrics và report.
- [ ] Không chạy load test với secret production thật.

### 9.3 Test matrix WS

| ID | Kịch bản | Mục tiêu |
|---|---|---|
| WS-01 | Idle CCU ramp | Tìm số socket giữ ổn định tối đa |
| WS-02 | Connect ramp | Đo handshake/s, p95, p99, reject rate |
| WS-03 | Reconnect storm | Mô phỏng deploy, mạng mobile chập chờn |
| WS-04 | Ping baseline | Mỗi client ping 10-20s có jitter |
| WS-05 | Presence storm | Nhiều user online/offline cùng lúc |
| WS-06 | Party/lobby broadcast | Fan-out room nhỏ nhưng tần suất cao |
| WS-07 | Friend fan-out | User có friend list lớn đổi status |
| WS-08 | Slow consumers | Client đọc chậm hoặc dừng đọc |
| WS-09 | Oversized/malformed frames | Validate abuse handling |
| WS-10 | Redis latency/outage | Gateway không block event-loop |
| WS-11 | NATS latency/outage | Event degrade/retry đúng class |
| WS-12 | Graceful drain | Rolling deploy không tạo reconnect spike mất kiểm soát |

### 9.4 Test matrix REST/control plane

| ID | Kịch bản | Tool |
|---|---|---|
| API-01 | Login steady ramp | JMeter |
| API-02 | Auth flood | JMeter |
| API-03 | Mixed authenticated reads | JMeter |
| API-04 | Party invite lifecycle | JMeter + integration assertions |
| API-05 | Matchmaking enqueue/poll/dequeue | JMeter |
| API-06 | Inventory/shop idempotency | JMeter + DB assertions |
| API-07 | Outbox backlog recovery | Fault injection |
| API-08 | NATS consumer retry/DLQ | Integration test |
| API-09 | Redis unavailable | Fault injection |
| API-10 | MySQL pool saturation | Fault injection + load |

### 9.5 Test matrix DS

| ID | Kịch bản | Ý nghĩa |
|---|---|---|
| DS-01 | Virtual heartbeat ramp | Control plane throughput |
| DS-02 | Mock container lifecycle | Allocate/register/heartbeat/reclaim |
| DS-03 | Real Unity DS ramp | RAM/CPU thật và port capacity |
| DS-04 | DS crash | Reclaim và player notification |
| DS-05 | Orchestrator restart | Lease recovery |
| DS-06 | Rolling image update | Drain version cũ, boot version mới |
| DS-07 | UDP port exhaustion | Fail fast và alert |
| DS-08 | Relay abuse | Packet rate, token validation, amplification guard |

### 9.6 Capacity test protocol

Không ghi một target duy nhất rồi tuyên bố pass. Dùng staircase:

```text
500 CCU  hold 10m
1k CCU   hold 10m
2k CCU   hold 15m
5k CCU   hold 20m
10k CCU  hold 30m
20k CCU  hold 30m
tiếp tục tăng cho tới khi chạm saturation
```

Tại mỗi bậc:

- Ghi CPU, heap, direct memory, GC, FD, network, Nginx connections.
- Ghi Redis latency, NATS latency, event-loop lag.
- Ghi handshake success, pong p95/p99, event delivery p95/p99.
- Giữ payload và message profile giống production.
- Sau peak chạy soak test tối thiểu 2-6 giờ.
- Restart gateway và chạy reconnect storm.

Capacity được ghi thành envelope:

```text
Machine:
  CPU, RAM, NIC, OS, kernel limits

Traffic profile:
  idle ratio
  ping interval
  avg frame bytes
  outbound event rate
  reconnect rate
  TLS mode

Validated envelope:
  max stable CCU
  max handshake/s
  max inbound msg/s
  max outbound msg/s
  p95/p99 latency
  failure point
```

### 9.7 Pass criteria ban đầu

Các ngưỡng dưới đây là starting SLO, cần điều chỉnh sau baseline:

| Metric | Initial pass criteria |
|---|---|
| WS connect success | `>= 99.9%` ngoài intentional rate-limit |
| WS handshake latency | p95 `< 250 ms`, p99 `< 750 ms` |
| Ping/pong RTT | p95 `< 250 ms`, p99 `< 750 ms` từ external generator |
| Realtime event delivery | p95 `< 300 ms`, p99 `< 1000 ms` |
| Critical event loss | `0` |
| Ephemeral drop | đo được, có budget rõ ràng |
| Event-loop lag | p99 `< 100 ms` |
| Heap/direct memory | không tăng đơn điệu trong soak test |
| Gateway restart | reconnect có backoff, phục hồi đúng snapshot |
| Outbox backlog | drain hết sau khi broker phục hồi |

## 10. Migration TODO checklist

### Phase 0 - Khôi phục baseline và đo đúng

- [ ] Fix compile errors hiện tại trước khi merge bất kỳ migration code nào.
- [ ] Gắn tag Git cho baseline chạy được.
- [ ] Chạy unit test hiện có.
- [ ] Chạy smoke HTTPS/WSS suite hiện có.
- [ ] Chạy JMeter REST `500/1000/1500` VU từ máy ngoài.
- [ ] Thu thập CPU, RAM, GC, DB pool, Redis latency, Nginx connection.
- [ ] Xác nhận runtime hiện tại thực sự dùng Tomcat bằng startup log và thread dump.
- [ ] Xóa claim chưa benchmark khỏi tài liệu vận hành.
- [ ] Phân loại test DS: virtual, mock container, real Unity DS.
- [ ] Lưu report baseline theo timestamp và commit hash.

Exit criteria:

```text
Build green
Smoke green
Có baseline report tái lập được
Biết bottleneck đầu tiên của stack hiện tại
```

### Phase 1 - Scaffold standalone realtime gateway

- [ ] Tạo Gradle module hoặc repo `realtime-gateway`.
- [ ] Dùng Netty standalone, không thêm Spring MVC, JPA hoặc Hibernate.
- [ ] Tạo `ConnectionContext`, `ConnectionRegistry`, `FrameDecoder`, `GatewayMetrics`.
- [ ] Implement WSS endpoint versioned.
- [ ] Implement heartbeat timeout có jitter.
- [ ] Implement bounded outbound queue theo bytes.
- [ ] Implement channel high/low water mark.
- [ ] Implement slow consumer policy.
- [ ] Implement graceful drain: stop accept mới, thông báo reconnect, đóng theo batch.
- [ ] Implement Prometheus metrics.
- [ ] Viết Netty `EmbeddedChannel` tests.
- [ ] Benchmark idle connection local trước khi nối business logic.

Exit criteria:

```text
Gateway giữ socket độc lập
Không dependency JPA/MySQL
Không unbounded queue
Metrics đầy đủ
Idle WS ramp chạy được
```

### Phase 2 - WS ticket và presence lease

- [ ] Tạo REST `POST /api/realtime/tickets`.
- [ ] Tạo Redis `ws:ticket:*` TTL 30-60s.
- [ ] Consume ticket atomically.
- [ ] Reject expired/reused ticket.
- [ ] Tạo `presence:user:*` TTL lease.
- [ ] Tạo `route:user:*` mapping đến `gatewayId`.
- [ ] Refresh lease async theo heartbeat.
- [ ] Remove lease bằng compare-and-delete để connection cũ không xóa connection mới.
- [ ] Force logout publish đến gateway.
- [ ] Unity client xin ticket mới trước connect/reconnect.
- [ ] Mask query string trong access log trong thời gian transition.

Exit criteria:

```text
Không JWT dài hạn trong WS URL
Ticket one-time test pass
Reconnect không race xóa presence mới
```

### Phase 3 - NATS Core, JetStream và outbox

- [ ] Thêm NATS vào compose internal network.
- [ ] Bật JetStream storage volume.
- [ ] Định nghĩa subject convention.
- [ ] Định nghĩa event envelope versioned.
- [ ] Tạo NATS credentials theo service.
- [ ] Tạo subject permissions tối thiểu.
- [ ] Implement gateway NATS adapter.
- [ ] Route ephemeral presence/lobby hint qua NATS Core.
- [ ] Tạo MySQL outbox table.
- [ ] Implement outbox publisher.
- [ ] Route invite, match transition và DS workflow qua JetStream.
- [ ] Implement explicit ack, retry backoff, max delivery.
- [ ] Implement DLQ subject.
- [ ] Implement consumer idempotency store.
- [ ] Viết integration test broker outage và replay.

Exit criteria:

```text
Critical event không phụ thuộc Redis Pub/Sub
Outbox replay pass
Consumer idempotency pass
NATS outage recovery pass
```

### Phase 4 - Tách business API khỏi gateway

- [ ] Xóa socket registry khỏi Spring business API.
- [ ] Thay `ConnectionManager.sendToUser()` trực tiếp bằng `RealtimeEventPublisher`.
- [ ] Business API publish event, không ghi thẳng channel.
- [ ] Gateway subscribe outbound user events.
- [ ] Client reconnect gọi snapshot REST để reconcile.
- [ ] Chuyển online state authoritative sang presence lease Redis.
- [ ] MySQL chỉ lưu last-seen/audit theo async workflow, không update mỗi heartbeat.
- [ ] Xóa Servlet `GameWebSocketHandler`.
- [ ] Xóa partial `ReactiveGameWebSocketHandler`.
- [ ] Xóa `spring-boot-starter-websocket` khỏi business API nếu không còn dùng.
- [ ] Xóa `spring-boot-starter-webflux` khỏi business API nếu chỉ thêm vì WS migration.
- [ ] Nâng Spring Boot `3.2.5` lên một release line còn được support; ưu tiên latest `3.5.x` patch trước khi đánh giá Boot 4.

Exit criteria:

```text
Spring process chỉ là control plane REST
Netty process chỉ là realtime data plane
Không blocking I/O trong gateway
```

### Phase 5 - Production hardening single-node

- [ ] Bỏ host publish MySQL.
- [ ] Bỏ host publish Redis.
- [ ] Disable phpMyAdmin production.
- [ ] Bỏ host publish relay management port.
- [ ] Actuator internal only.
- [ ] Dashboard sau VPN/allowlist.
- [ ] Tạo `ds-orchestrator`.
- [ ] Chỉ orchestrator mount Docker socket.
- [ ] Bỏ Docker socket khỏi backend.
- [ ] Bỏ Docker socket khỏi dashboard.
- [ ] Tuning Nginx FD và worker connection.
- [ ] Tuning OS FD, backlog và conntrack nếu firewall/NAT yêu cầu.
- [ ] Tuning JVM heap/direct memory sau benchmark.
- [ ] Chọn GC sau benchmark, không mặc định giả định một GC luôn tốt nhất.
- [ ] Tạo Grafana dashboard.
- [ ] Tạo alert rules.
- [ ] Tạo backup/restore test cho MySQL, Redis persistent state cần thiết và JetStream volume.
- [ ] Tạo runbook deploy, rollback, drain gateway, broker outage.

Exit criteria:

```text
Chỉ public đúng port cần thiết
Privilege boundary rõ ràng
Monitoring và alert hoạt động
Rollback và restore đã test
```

### Phase 6 - Capacity certification single-node

- [ ] Sửa k6 WS harness theo ticket riêng từng VU.
- [ ] Chạy WS-01 đến WS-12.
- [ ] Chạy API-01 đến API-10.
- [ ] Chạy DS-01 đến DS-08.
- [ ] Chạy mixed workload: WS + REST + virtual DS.
- [ ] Chạy real Unity DS ramp riêng.
- [ ] Chạy soak 2-6 giờ.
- [ ] Chạy broker outage recovery.
- [ ] Chạy Redis outage behavior.
- [ ] Chạy graceful drain/reconnect storm.
- [ ] Viết capacity envelope theo cấu hình VPS thật.
- [ ] Đặt production alert threshold ở dưới validated saturation point.

Exit criteria:

```text
Có max stable CCU đã đo
Có failure point đã đo
Có alert threshold có cơ sở
Có report tái lập được
```

### Phase 7 - Chuẩn bị scale ngang và multi-region

- [ ] Cho gateway có `gatewayId`.
- [ ] Route user bằng Redis lease `userId -> gatewayId`.
- [ ] Subject outbound theo gateway để tránh broadcast toàn cụm.
- [ ] Test 2 gateway nodes và rolling drain.
- [ ] Thêm load balancer health check.
- [ ] Client gửi ping probe đến region endpoints.
- [ ] Region selector chọn latency phù hợp và còn capacity.
- [ ] Prefix event và metrics theo region.
- [ ] Tạo DS pool theo region.
- [ ] Tách orchestrator region-local.
- [ ] Thiết kế failover region có rule rõ ràng, không tự động mù quáng.
- [ ] Benchmark cross-region NATS trước khi chọn topology supercluster/leaf node.

Exit criteria:

```text
Thêm gateway node không sửa business code
Thêm region không sửa protocol cốt lõi
DS allocation chọn được region theo latency + capacity
```

## 11. Multi-region blueprint phát triển sau

```text
Global DNS / Anycast / geo routing
  |
  +--> Region VN
  |      edge
  |      gateway pool
  |      business-api pool
  |      redis
  |      nats
  |      ds-orchestrator
  |      DS fleet
  |
  +--> Region SG
         edge
         gateway pool
         business-api pool
         redis
         nats
         ds-orchestrator
         DS fleet
```

Client chọn region:

```text
1. Fetch region directory
2. Ping lightweight HTTPS/UDP probe nhiều lần
3. Loại region vượt latency threshold hoặc hết capacity
4. Chọn median latency tốt nhất
5. Xin matchmaking ticket gắn region
6. Orchestrator allocate DS trong region tương ứng
```

Không dùng một MySQL writer cross-region ngay từ đầu nếu chưa cần. Trước hết:

- Auth/profile có thể giữ home region hoặc primary region.
- Matchmaking và DS allocation region-local.
- Kết quả match publish durable event về service chịu trách nhiệm persistence.
- Cross-region consistency policy phải được định nghĩa theo từng domain.

## 12. Definition of Done

Migration chỉ được coi là hoàn thành khi:

- [ ] Gateway là process độc lập, không phải bean trong Spring monolith.
- [ ] Gateway không có JPA/MySQL dependency.
- [ ] Gateway không gọi synchronous Redis API trên event-loop.
- [ ] Access JWT không xuất hiện trong WS URL hoặc access log.
- [ ] Critical events đi qua JetStream/outbox và replay được.
- [ ] Redis Pub/Sub không còn là durable workflow backbone.
- [ ] Business API không giữ socket map.
- [ ] Docker socket chỉ nằm trong orchestrator nội bộ.
- [ ] MySQL, Redis, relay management, NATS management không public.
- [ ] k6 WS dùng identity/ticket riêng từng VU.
- [ ] JMeter REST, k6 WS, virtual DS và real Unity DS được báo cáo tách biệt.
- [ ] Có capacity envelope single-node tái lập được.
- [ ] Có dashboard, alert, runbook rollback và failure recovery.
- [ ] Có blueprint thêm gateway node và thêm region mà không rewrite.

## 13. Nguồn tham chiếu chính thức

- Spring Boot WebFlux auto-configuration: khi cả MVC và WebFlux cùng tồn tại, MVC được ưu tiên.
  https://docs.spring.io/spring-boot/reference/web/reactive.html

- Spring Framework WebFlux WebSocket support và Reactor Netty adapter.
  https://docs.spring.io/spring-framework/reference/web/webflux-websocket.html

- Spring Data Redis reactive API dùng `ReactiveRedisTemplate`.
  https://docs.spring.io/spring-data/redis/reference/redis/redis-reactive.html

- Redis Pub/Sub delivery semantic là `at-most-once`.
  https://redis.io/docs/latest/develop/interact/pubsub/

- NATS Core có at-most-once delivery; JetStream bổ sung persistence và delivery guarantees cao hơn.
  https://docs.nats.io/nats-concepts/what-is-nats
  https://docs.nats.io/nats-concepts/jetstream

- NATS authorization hỗ trợ permission theo publish/subscribe subject.
  https://docs.nats.io/running-a-nats-service/configuration/securing_nats/authorization

- Nginx WebSocket proxying yêu cầu xử lý rõ `Upgrade` và `Connection`.
  https://nginx.org/en/docs/http/websocket.html

- Nginx `worker_connections` bao gồm cả connection đến upstream, không chỉ client connection.
  https://nginx.org/en/docs/ngx_core_module.html#worker_connections

- OWASP Logging Cheat Sheet: access token và session identifier không nên được log trực tiếp.
  https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html

- Grafana k6 WebSocket testing; test mới nên dùng `k6/websockets`. Module `k6/experimental/websockets` đã deprecated.
  https://grafana.com/docs/k6/latest/using-k6/protocols/websockets/

- Spring Boot release lines hiện tại để lên kế hoạch upgrade khỏi `3.2.5`.
  https://spring.io/projects/spring-boot
  https://github.com/spring-projects/spring-boot/wiki/Supported-Versions
