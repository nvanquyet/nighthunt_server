# Night Hunt Backend Migration - Implementation TODO

> Updated: 2026-06-02
> Scope: backend realtime migration, Unity WS handshake, deployment automation, load-test entrypoints.
>
> Mermaid diagrams, runtime layers, current transition flow and capacity model:
> [`Realtime Architecture Runtime Guide 2026.md`](./Realtime%20Architecture%20Runtime%20Guide%202026.md)
>
> `Must Run Before Production Cutover` means implemented code that still needs staging/VPS validation.
> `Remaining Migration Work` means implementation that is intentionally not finished yet.

## Completed In This Slice

- [x] Restore backend compile baseline for the current dirty tree.
- [x] Add authenticated one-time realtime ticket endpoint: `POST /api/realtime/tickets`.
- [x] Store tickets in Redis as `ws:ticket:<ticket>` with short TTL.
- [x] Scaffold standalone `realtime-gateway` Gradle module using Netty, no Spring MVC/JPA/Hibernate.
- [x] Implement gateway ticket consume during WebSocket handshake.
- [x] Implement gateway single-device replacement, heartbeat, presence lease and route lease in Redis.
- [x] Implement gateway metrics endpoint: `:9091/health` and `:9091/metrics`.
- [x] Implement NATS outbound subscriber for gateway-directed user events.
- [x] Add Spring `RealtimeConnectionManager` primary adapter that publishes realtime events to NATS only.
- [x] Add Redis route cache for backend user event publishing.
- [x] Add gateway `connected` and `disconnected` events over NATS.
- [x] Move gateway connect/disconnect side effects into Spring gateway presence subscriber.
- [x] Validate gateway presence events by fresh `connectionId` before applying side effects.
- [x] Use atomic Redis Lua update for room route membership.
- [x] Add MySQL outbox table `V39__create_realtime_outbox.sql`.
- [x] Add JetStream outbox publisher with retry/backoff.
- [x] Enqueue durable `events.ds.ready` and `events.match.ended`.
- [x] Change Unity client WS handshake from `?token=<jwt>` to `?ticket=<one-time-ticket>`.
- [x] Keep Unity event payload contract unchanged.
- [x] Update k6 script to request per-VU ticket before WS connect.
- [x] Require unique credentials per k6 VU so single-device replacement cannot falsify CCU results.
- [x] Upgrade realtime load generation to the recommended `k6/websockets` global event-loop API.
- [x] Add k6 realtime certification runner.
- [x] Add NATS and realtime-gateway to compose.
- [x] Route `/api/ws/` through realtime-gateway in Nginx.
- [x] Redact query string from Nginx access logs by logging `$uri` instead of `$request`.
- [x] Raise Nginx `worker_connections` for high WS targets.
- [x] Raise Nginx and realtime-gateway file descriptor limits for high WS targets.
- [x] Configure gateway accept backlog and prefer Linux native Netty `epoll` with NIO fallback.
- [x] Use a GLIBC gateway runtime image so the official Netty native `epoll` transport can load.
- [x] Restrict MySQL, Redis, backend and dashboard host bindings to loopback.
- [x] Move phpMyAdmin behind optional Compose profile `tools`.
- [x] Stop publishing relay management HTTP port.
- [x] Update CI to run backend + gateway tests.
- [x] Update CI to build/push gateway image.
- [x] Add smoke check for realtime ticket issue, first WSS upgrade and replay rejection.
- [x] Remove legacy Servlet `GameWebSocketHandler`.
- [x] Remove partial `ReactiveGameWebSocketHandler`.
- [x] Remove Spring local WebSocket config and JWT `/ws/` bypass.
- [x] Remove `spring-boot-starter-websocket` and `spring-boot-starter-webflux` from business-api.
- [x] Remove noop realtime publisher so disabled NATS fails fast instead of dropping events.
- [x] Read Spring active realtime connection count from Redis gateway routes.
- [x] Add unit tests for NATS-only connection manager, stale presence-event handling and gateway registry release behavior.
- [x] Preserve premade party members as one matchmaking unit with explicit team assignment.
- [x] Support host-controlled Fill Party for underfilled ranked teams.
- [x] Keep fill teammates temporary: do not insert fillers into `party_members`.
- [x] Reset original ranked party status to `IDLE/NONE` after match end.
- [x] Add matchmaking queue metadata migration `V40__add_matchmaking_group_metadata.sql`.
- [x] Guard cluster-wide matchmaking ticks with Redis token lock, Lua renew and compare-delete release.
- [x] Serialize party queue/cancel mutations with MySQL pessimistic row lock.

## Must Run Before Production Cutover

- [ ] Deploy to staging with `REALTIME_NATS_ENABLED=true` and `JETSTREAM_OUTBOX_ENABLED=true`.
- [ ] Confirm NATS JetStream stream `NIGHTHUNT_EVENTS` is created and persists events.
- [ ] Confirm `/api/ws/game` is served by `nighthunt-realtime-gateway`, not backend.
- [ ] Confirm Unity login connects through ticket flow on a fresh install.
- [ ] Confirm reconnect gets a new ticket every attempt.
- [ ] Confirm `ds_ready` and `match_ended` events reach clients through gateway.
- [ ] Run `load-tests/k6/run-realtime-certification.ps1` from an external generator.
- [ ] Run JMeter REST tests while k6 WS test is holding connections.
- [ ] Record single-node capacity envelope: max stable CCU, p95/p99 ping RTT, CPU, heap/direct memory, Redis latency, NATS latency.
- [ ] Run gateway restart drain/reconnect test.
- [ ] Run NATS outage/recovery test and confirm outbox backlog drains.
- [ ] Run Redis outage behavior test.
- [ ] Validate Docker Compose on VPS because local workstation currently has no Docker CLI.
- [ ] Run party Fill Party staging scenarios: locked underfilled team, temporary fill team, match-end party reset.

## Remaining Migration Work

- [ ] Add gateway graceful drain with reconnect backoff before rolling restart.
- [ ] Add gateway connect/message rate limits, Origin validation and protocol-version validation.
- [ ] Configure NATS credentials and least-privilege subject permissions.
- [ ] Move all durable invite/party/match allocation workflows from Redis Pub/Sub to outbox + JetStream.
- [ ] Replace dashboard Docker socket access with an internal load-test runner/orchestrator.
- [ ] Extract DS container lifecycle from backend into a dedicated `ds-orchestrator`.
- [ ] Put actuator metrics behind internal network/VPN only; expose only a minimal public health endpoint if needed.
- [ ] Add Prometheus/Grafana dashboard for gateway, NATS, Redis, JVM and MySQL.
- [ ] Add two-gateway staging test with `gatewayId` route verification.
- [ ] Add region latency probe and region-local DS allocation in a later feature phase.
