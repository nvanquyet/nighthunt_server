"""
NightHunt UDP Relay Server
==========================
Transparent UDP relay for custom game sessions (cross-network, no port-forward needed).

Architecture
------------
All players (host + clients) connect OUTBOUND to relay:sessionPort.
This creates a NAT hole so the relay can send data back to them.

The relay is TRANSPARENT — it does NOT modify FishNet/Tugboat packets.
Session identification is by port (each session gets its own UDP port), not by packet header.

Host identification:
  - The Unity host sends NH_RELAY_HOST through LiteNetLib SendUnconnectedMessage.
    LiteNetLib prepends a small packet header; the relay matches the magic payload.
  - Or: the backend calls POST /session/set-host { "token": "...", "host_addr": "ip:port" }
    after the host registers via the relay's heartbeat.

Routing rules:
  - Packet from HOST endpoint → forwarded to ALL client endpoints in same session
  - Packet from CLIENT endpoint → forwarded to HOST endpoint only

Management HTTP API (internal only — backend calls this):
  POST /session/create   { "token": "<uuid>" }          → { "port": 7781 }
  POST /session/close    { "token": "<uuid>" }           → { "ok": true }
  POST /session/set-host { "token": "<uuid>", "is_host_first": true }
  GET  /session/list                                     → [...]
  GET  /health                                           → { "status": "ok" }

Run:
  python relay_server.py --http-port 7776 --port-start 7777 --port-end 7900
"""

import asyncio
import json
import logging
import argparse
import sys
import time
from typing import Dict, List, Optional, Tuple

from aiohttp import web

HOST_REGISTRATION_MAGIC = b"NH_RELAY_HOST"
LITENET_UNCONNECTED_PROPERTY = 8

# ── Logging ────────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
log = logging.getLogger("relay")

SESSION_IDLE_TTL  = 7200   # seconds — sessions idle this long auto-expire
CLIENT_IDLE_SECS  = 60     # seconds — unresponsive endpoints are pruned


def is_host_registration_packet(data: bytes) -> bool:
    """
    Unity sends the registration through LiteNetLib SendUnconnectedMessage.
    LiteNetLib prepends its own packet header, so the relay scans the packet
    payload instead of requiring byte-for-byte equality with the magic string.
    """
    return HOST_REGISTRATION_MAGIC in data


def litenet_packet_property(data: bytes) -> Optional[int]:
    if not data:
        return None
    return data[0] & 0x1F


# ── Session State ──────────────────────────────────────────────────────────────
class RelaySession:
    def __init__(self, token: str, port: int, host_ports: Optional[List[int]] = None):
        self.token       = token
        self.port        = port
        self.host_ports: List[int] = list(host_ports or [])
        self.free_host_ports: List[int] = list(self.host_ports)
        self.host_addr:  Optional[Tuple[str, int]] = None
        # non-host endpoints: addr → last_seen timestamp
        self.clients:    Dict[Tuple[str, int], float] = {}
        # all known endpoints (host + clients) for fast lookup
        self.all_known:  Dict[Tuple[str, int], float] = {}
        self.client_relays: Dict[Tuple[str, int], "HostUpstreamProtocol"] = {}
        self.host_upstreams: Dict[int, "HostUpstreamProtocol"] = {}
        self.packets_before_host_logged = 0
        self.upstream_recycle_count = 0
        self.created_at  = time.time()
        self.last_pkt    = time.time()

    def touch(self):
        self.last_pkt = time.time()

    def is_idle(self) -> bool:
        return (time.time() - self.last_pkt) > SESSION_IDLE_TTL

    def client_count(self) -> int:
        return len(self.clients)

    def has_host(self) -> bool:
        if self.host_addr is not None:
            return True
        return any(relay.host_addr is not None for relay in self.host_upstreams.values())

    def registered_host_upstream_count(self) -> int:
        return sum(1 for relay in self.host_upstreams.values() if relay.host_addr is not None)

    def register_host(self, addr: Tuple[str, int]):
        self.all_known[addr] = time.time()
        if addr in self.clients:
            self.remove_client(addr)
        self.host_addr = addr
        log.info("[Relay] Host registered: session=%s addr=%s:%d",
                 self.token[:8], addr[0], addr[1])

    def register_endpoint(self, addr: Tuple[str, int]):
        now = time.time()
        self.all_known[addr] = now
        if addr != self.host_addr:
            self.clients[addr] = now

    def remove_client(self, addr: Tuple[str, int]):
        self.clients.pop(addr, None)
        self.all_known.pop(addr, None)
        relay = self.client_relays.pop(addr, None)
        if relay is not None:
            relay.release_client()
            if relay.host_port not in self.free_host_ports:
                self.free_host_ports.append(relay.host_port)
                self.free_host_ports.sort()

    def recycle_oldest_client_relay(self) -> Optional["HostUpstreamProtocol"]:
        relay_clients = [addr for addr in self.client_relays if addr in self.clients]
        if not relay_clients:
            return None

        oldest = min(relay_clients, key=lambda a: self.clients.get(a, 0))
        relay = self.client_relays.pop(oldest, None)
        self.clients.pop(oldest, None)
        self.all_known.pop(oldest, None)
        if relay is None:
            return None

        self.upstream_recycle_count += 1
        relay.release_client()
        log.warning("[Relay] Recycled oldest client upstream: session=%s oldClient=%s:%d hostPort=%d recycleCount=%d",
                    self.token[:8], oldest[0], oldest[1], relay.host_port, self.upstream_recycle_count)
        return relay

    def close_relays(self):
        for relay in list(self.host_upstreams.values()):
            try:
                relay.close()
            except Exception:
                pass
        self.client_relays.clear()
        self.host_upstreams.clear()
        self.free_host_ports.clear()

    def bind_host_upstream(self, host_port: int, protocol: "HostUpstreamProtocol"):
        self.host_upstreams[host_port] = protocol

    def acquire_host_upstream(self, client_addr: Tuple[str, int]) -> Optional["HostUpstreamProtocol"]:
        now = time.time()
        self.clients[client_addr] = now
        self.all_known[client_addr] = now

        relay = self.client_relays.get(client_addr)
        if relay is not None:
            return relay

        relay = None
        host_port = None
        if self.free_host_ports:
            host_port = self.free_host_ports.pop(0)
            relay = self.host_upstreams.get(host_port)
            if relay is None:
                log.warning("[Relay] Host upstream port not bound: session=%s port=%d",
                            self.token[:8], host_port)
                return None
        else:
            relay = self.recycle_oldest_client_relay()
            if relay is None:
                log.warning("[Relay] No host upstream ports left: session=%s client=%s:%d clients=%d assigned=%d",
                            self.token[:8], client_addr[0], client_addr[1],
                            len(self.clients), len(self.client_relays))
                return None
            host_port = relay.host_port

        relay.assign_client(client_addr)
        self.client_relays[client_addr] = relay
        host_addr = relay.get_host_addr()
        log.info("[Relay] Client upstream assigned: session=%s client=%s:%d hostPort=%d host=%s",
                 self.token[:8], client_addr[0], client_addr[1], host_port,
                 f"{host_addr[0]}:{host_addr[1]}" if host_addr else "unset")
        return relay


# ── UDP Relay Protocol ─────────────────────────────────────────────────────────
class HostUpstreamProtocol(asyncio.DatagramProtocol):
    """One host-facing UDP socket mapping host replies back to one client."""

    def __init__(self, session: RelaySession, host_port: int, downstream_transport):
        self.session = session
        self.host_port = host_port
        self.host_addr: Optional[Tuple[str, int]] = None
        self.client_addr: Optional[Tuple[str, int]] = None
        self.downstream_transport = downstream_transport
        self.transport = None
        self._fallback_logged = False
        self._client_forward_logged = False
        self._host_forward_logged = False

    def connection_made(self, transport):
        self.transport = transport
        self.session.bind_host_upstream(self.host_port, self)
        log.info("[Relay] Host upstream listening: session=%s port=%d",
                 self.session.token[:8], self.host_port)

    def assign_client(self, client_addr: Tuple[str, int]):
        self.client_addr = client_addr
        self._client_forward_logged = False
        self._host_forward_logged = False

    def release_client(self):
        self.client_addr = None
        self._client_forward_logged = False
        self._host_forward_logged = False

    def close(self):
        if self.transport is not None:
            self.transport.close()

    def register_host(self, addr: Tuple[str, int]):
        self.session.all_known[addr] = time.time()
        if self.host_addr != addr:
            self.host_addr = addr
            log.info("[Relay] Host upstream registered: session=%s port=%d addr=%s:%d",
                     self.session.token[:8], self.host_port, addr[0], addr[1])

        if self.session.host_addr is None:
            self.session.register_host(addr)

    def get_host_addr(self) -> Optional[Tuple[str, int]]:
        if self.host_addr is not None:
            return self.host_addr

        if self.session.host_addr is not None and not self._fallback_logged:
            self._fallback_logged = True
            log.warning("[Relay] Host upstream missing punch: session=%s port=%d; "
                        "falling back to main host endpoint %s:%d",
                        self.session.token[:8], self.host_port,
                        self.session.host_addr[0], self.session.host_addr[1])
        return self.session.host_addr

    def datagram_received(self, data: bytes, addr: Tuple[str, int]):
        self.session.touch()

        if is_host_registration_packet(data):
            self.register_host(addr)
            log.debug("[Relay] Host upstream punch: session=%s port=%d addr=%s:%d",
                      self.session.token[:8], self.host_port, addr[0], addr[1])
            return

        if self.host_addr is None:
            prop = litenet_packet_property(data)
            if prop == LITENET_UNCONNECTED_PROPERTY:
                self.register_host(addr)
                log.warning(
                    "[Relay] Host upstream registered by unconnected-packet fallback: "
                    "session=%s port=%d addr=%s:%d len=%d head=%s tail=%s",
                    self.session.token[:8],
                    self.host_port,
                    addr[0],
                    addr[1],
                    len(data),
                    data[:16].hex(),
                    data[-16:].hex(),
                )
                return

        host_addr = self.get_host_addr()
        if host_addr is None:
            log.debug("[Relay] Dropping upstream packet before host punch: session=%s port=%d addr=%s:%d",
                      self.session.token[:8], self.host_port, addr[0], addr[1])
            return

        if addr != host_addr:
            log.debug("[Relay] Dropping upstream packet from non-host addr=%s:%d expected=%s",
                      addr[0], addr[1], host_addr)
            return

        if self.client_addr is None:
            log.debug("[Relay] Dropping host packet on unassigned upstream: session=%s port=%d",
                      self.session.token[:8], self.host_port)
            return

        self.session.clients[self.client_addr] = time.time()
        self.session.all_known[self.client_addr] = time.time()
        try:
            self.downstream_transport.sendto(data, self.client_addr)
            if not self._host_forward_logged:
                self._host_forward_logged = True
                log.info("[Relay] Host packet forwarded: session=%s hostPort=%d client=%s:%d len=%d prop=%s",
                         self.session.token[:8], self.host_port,
                         self.client_addr[0], self.client_addr[1],
                         len(data), litenet_packet_property(data))
        except Exception as e:
            log.debug("[Relay] Downstream send error to %s: %s", self.client_addr, e)

    def error_received(self, exc):
        log.debug("[Relay] Host upstream UDP error port=%d client=%s: %s",
                  self.host_port, self.client_addr, exc)


class RelayProtocol(asyncio.DatagramProtocol):
    """Asyncio UDP protocol bound to one relay session port."""

    def __init__(self, session: RelaySession):
        self.session   = session
        self.transport = None

    def connection_made(self, transport):
        self.transport = transport
        log.info("[Relay] Listening: session=%s port=%d", self.session.token[:8], self.session.port)

    def datagram_received(self, data: bytes, addr: Tuple[str, int]):
        session = self.session
        session.touch()

        if is_host_registration_packet(data):
            session.register_host(addr)
            return

        if session.host_addr is None:
            prop = litenet_packet_property(data)
            if prop == LITENET_UNCONNECTED_PROPERTY:
                session.register_host(addr)
                log.warning(
                    "[Relay] Host registered by unconnected-packet fallback: session=%s addr=%s:%d len=%d head=%s tail=%s",
                    session.token[:8],
                    addr[0],
                    addr[1],
                    len(data),
                    data[:16].hex(),
                    data[-16:].hex(),
                )
                return

            session.packets_before_host_logged += 1
            if session.packets_before_host_logged <= 8:
                log.warning(
                    "[Relay] Dropping packet before host registration: session=%s addr=%s:%d len=%d prop=%s head=%s tail=%s",
                    session.token[:8],
                    addr[0],
                    addr[1],
                    len(data),
                    prop,
                    data[:16].hex(),
                    data[-16:].hex(),
                )
            return

        is_new = addr not in session.all_known
        if is_new:
            session.register_endpoint(addr)

        session.all_known[addr] = time.time()

        if addr == session.host_addr:
            # ── Host → forward to all clients ─────────────────────────────
            stale = [c for c, t in list(session.clients.items())
                     if (time.time() - t) > CLIENT_IDLE_SECS]
            for s in stale:
                session.remove_client(s)

            for client_addr in list(session.clients):
                self._send(data, client_addr)
        else:
            # ── Client → forward to host ───────────────────────────────────
            session.clients[addr] = time.time()
            if session.host_addr is not None:
                asyncio.create_task(self._forward_client_packet(data, addr))
            # else: host not connected yet → buffer? For now just drop.

    async def _forward_client_packet(self, data: bytes, client_addr: Tuple[str, int]):
        session = self.session

        relay = session.acquire_host_upstream(client_addr)
        if relay is None or relay.transport is None:
            return

        host_addr = relay.get_host_addr()
        if host_addr is None:
            log.warning("[Relay] Cannot forward client packet before host endpoint is known: "
                        "session=%s client=%s:%d hostPort=%d",
                        session.token[:8], client_addr[0], client_addr[1], relay.host_port)
            return

        try:
            relay.transport.sendto(data, host_addr)
            if not relay._client_forward_logged:
                relay._client_forward_logged = True
                log.info("[Relay] Client packet forwarded: session=%s client=%s:%d hostPort=%d host=%s:%d len=%d prop=%s",
                         session.token[:8], client_addr[0], client_addr[1],
                         relay.host_port, host_addr[0], host_addr[1],
                         len(data), litenet_packet_property(data))
        except Exception as e:
            log.debug("[Relay] Upstream send error from %s to host %s: %s",
                      client_addr, host_addr, e)

    def _send(self, payload: bytes, addr):
        try:
            self.transport.sendto(payload, addr)
        except Exception as e:
            log.debug("[Relay] Send error to %s: %s", addr, e)

    def error_received(self, exc):
        log.warning("[Relay] UDP error on port %d: %s", self.session.port, exc)

    def connection_lost(self, exc):
        log.info("[Relay] Port %d closed (session=%s)", self.session.port, self.session.token[:8])


# ── Relay Server Manager ───────────────────────────────────────────────────────
class RelayManager:
    def __init__(self, port_start: int, port_end: int, loop, default_host_upstreams: int = 4):
        self.port_start = port_start
        self.port_end   = port_end
        self.loop       = loop
        self.default_host_upstreams = max(1, default_host_upstreams)
        self.sessions:   Dict[str, RelaySession] = {}   # token → session
        self.port_map:   Dict[int, str]          = {}   # port  → token
        self._free_ports = list(range(port_start, port_end + 1))

    def _alloc_port(self) -> Optional[int]:
        return self._free_ports.pop(0) if self._free_ports else None

    def _free_port(self, port: int):
        if port not in self._free_ports:
            self._free_ports.append(port)
            self._free_ports.sort()

    def _alloc_ports(self, count: int) -> Optional[List[int]]:
        ports: List[int] = []
        for _ in range(count):
            port = self._alloc_port()
            if port is None:
                for allocated in ports:
                    self._free_port(allocated)
                return None
            ports.append(port)
        return ports

    async def create_session(self, token: str, host_upstream_count: Optional[int] = None) -> Optional[RelaySession]:
        if token in self.sessions:
            return self.sessions[token]  # idempotent

        upstream_count = max(1, host_upstream_count or self.default_host_upstreams)
        ports = self._alloc_ports(1 + upstream_count)
        if ports is None:
            log.error("[Relay] No free ports!")
            return None

        port = ports[0]
        host_ports = ports[1:]
        session = RelaySession(token, port, host_ports)
        bound_transports = []
        try:
            transport, _ = await self.loop.create_datagram_endpoint(
                lambda: RelayProtocol(session),
                local_addr=("0.0.0.0", port),
            )
            bound_transports.append(transport)
            for host_port in host_ports:
                host_transport, _ = await self.loop.create_datagram_endpoint(
                    lambda p=host_port: HostUpstreamProtocol(session, p, transport),
                    local_addr=("0.0.0.0", host_port),
                )
                bound_transports.append(host_transport)
        except OSError as e:
            log.error("[Relay] Cannot bind session port block %s: %s", ports, e)
            for bound in bound_transports:
                try:
                    bound.close()
                except Exception:
                    pass
            for allocated in ports:
                self._free_port(allocated)
            return None

        session._transport = transport
        self.sessions[token] = session
        for allocated in ports:
            self.port_map[allocated] = token
        log.info("[Relay] Session created: token=%s port=%d hostPorts=%s",
                 token[:8], port, host_ports)
        return session

    async def close_session(self, token: str):
        session = self.sessions.pop(token, None)
        if session is None:
            return
        session.close_relays()
        for allocated in [session.port] + list(session.host_ports):
            self.port_map.pop(allocated, None)
            self._free_port(allocated)
        try:
            session._transport.close()
        except Exception:
            pass
        log.info("[Relay] Session closed: token=%s port=%d hostPorts=%s",
                 token[:8], session.port, session.host_ports)

    async def cleanup_idle(self):
        stale = [t for t, s in self.sessions.items() if s.is_idle()]
        for token in stale:
            log.info("[Relay] Expiring idle session %s", token[:8])
            await self.close_session(token)


# ── HTTP Management API ────────────────────────────────────────────────────────
async def handle_create(request: web.Request) -> web.Response:
    manager: RelayManager = request.app["manager"]
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"error": "invalid JSON"}, status=400)

    token = str(body.get("token", "")).strip()
    if len(token) < 8:
        return web.json_response({"error": "token must be at least 8 chars"}, status=400)

    host_upstream_count = body.get("hostUpstreamCount")
    try:
        host_upstream_count = int(host_upstream_count) if host_upstream_count is not None else None
    except (TypeError, ValueError):
        return web.json_response({"error": "hostUpstreamCount must be an integer"}, status=400)

    session = await manager.create_session(token, host_upstream_count)
    if session is None:
        return web.json_response({"error": "no free ports"}, status=503)

    return web.json_response({
        "token": session.token,
        "port": session.port,
        "hostPorts": session.host_ports,
    })


async def handle_close(request: web.Request) -> web.Response:
    manager: RelayManager = request.app["manager"]
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"error": "invalid JSON"}, status=400)
    await manager.close_session(str(body.get("token", "")))
    return web.json_response({"ok": True})


async def handle_set_host(request: web.Request) -> web.Response:
    """
    POST /session/set-host { "token": "...", "host_addr": "ip:port" }

    Explicitly override the host endpoint for a session.
    Used by the backend after the Unity host connects via WebSocket heartbeat
    and its real UDP source address is known.
    """
    manager: RelayManager = request.app["manager"]
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"error": "invalid JSON"}, status=400)

    token     = str(body.get("token", "")).strip()
    host_addr = str(body.get("host_addr", "")).strip()

    if not token:
        return web.json_response({"error": "token required"}, status=400)

    session = manager.sessions.get(token)
    if session is None:
        return web.json_response({"error": "session not found"}, status=404)

    if host_addr:
        try:
            ip, port_str = host_addr.rsplit(":", 1)
            parsed_addr  = (ip, int(port_str))
            session.register_host(parsed_addr)
            log.info("[Relay] Host overridden via API: session=%s addr=%s:%d",
                     token[:8], parsed_addr[0], parsed_addr[1])
        except (ValueError, IndexError):
            return web.json_response({"error": "host_addr must be 'ip:port'"}, status=400)

    return web.json_response({
        "ok":       True,
        "token":    token,
        "host_set": session.host_addr is not None,
    })


async def handle_list(request: web.Request) -> web.Response:
    manager: RelayManager = request.app["manager"]
    result = [
        {
            "token":    s.token[:8] + "...",
            "port":     s.port,
            "host_ports": s.host_ports,
            "clients":  s.client_count(),
            "assigned_upstreams": len(s.client_relays),
            "has_host": s.has_host(),
            "registered_host_upstreams": s.registered_host_upstream_count(),
            "host_upstream_ports": s.host_ports,
            "free_host_ports": s.free_host_ports,
            "upstream_recycles": s.upstream_recycle_count,
            "idle_sec": int(time.time() - s.last_pkt),
        }
        for s in manager.sessions.values()
    ]
    return web.json_response(result)


async def handle_health(request: web.Request) -> web.Response:
    manager: RelayManager = request.app["manager"]
    return web.json_response({
        "status":     "ok",
        "sessions":   len(manager.sessions),
        "free_ports": len(manager._free_ports),
    })


# ── Background cleanup ─────────────────────────────────────────────────────────
async def cleanup_task(manager: RelayManager):
    while True:
        await asyncio.sleep(60)
        await manager.cleanup_idle()


# ── Main ───────────────────────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(description="NightHunt UDP Relay Server")
    parser.add_argument("--http-port",  type=int, default=7776)
    parser.add_argument("--port-start", type=int, default=7777)
    parser.add_argument("--port-end",   type=int, default=7900)
    parser.add_argument("--host-upstreams", type=int, default=4)
    parser.add_argument("--host",       type=str, default="0.0.0.0")
    args = parser.parse_args()

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)

    manager = RelayManager(args.port_start, args.port_end, loop, args.host_upstreams)

    app = web.Application()
    app["manager"] = manager
    app.router.add_post("/session/create",   handle_create)
    app.router.add_post("/session/close",    handle_close)
    app.router.add_post("/session/set-host", handle_set_host)
    app.router.add_get( "/session/list",     handle_list)
    app.router.add_get( "/health",           handle_health)

    loop.create_task(cleanup_task(manager))

    log.info("NightHunt Relay | HTTP=%s:%d | UDP range=%d-%d",
             args.host, args.http_port, args.port_start, args.port_end)

    web.run_app(app, host=args.host, port=args.http_port, loop=loop, print=None)


if __name__ == "__main__":
    main()
