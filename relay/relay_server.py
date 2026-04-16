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
  - The FIRST endpoint to connect to a session port is registered as the HOST.
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
from typing import Dict, Optional, Tuple

from aiohttp import web

# ── Logging ────────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
log = logging.getLogger("relay")

SESSION_IDLE_TTL  = 7200   # seconds — sessions idle this long auto-expire
CLIENT_IDLE_SECS  = 60     # seconds — unresponsive endpoints are pruned


# ── Session State ──────────────────────────────────────────────────────────────
class RelaySession:
    def __init__(self, token: str, port: int):
        self.token       = token
        self.port        = port
        self.host_addr:  Optional[Tuple[str, int]] = None
        # non-host endpoints: addr → last_seen timestamp
        self.clients:    Dict[Tuple[str, int], float] = {}
        # all known endpoints (host + clients) for fast lookup
        self.all_known:  Dict[Tuple[str, int], float] = {}
        self.created_at  = time.time()
        self.last_pkt    = time.time()

    def touch(self):
        self.last_pkt = time.time()

    def is_idle(self) -> bool:
        return (time.time() - self.last_pkt) > SESSION_IDLE_TTL

    def client_count(self) -> int:
        return len(self.clients)

    def register_endpoint(self, addr: Tuple[str, int]):
        """Register a new endpoint. First one becomes host if no host assigned yet."""
        now = time.time()
        self.all_known[addr] = now
        if self.host_addr is None:
            # First connection → host
            self.host_addr = addr
            log.info("[Relay] Host auto-registered: session=%s addr=%s:%d",
                     self.token[:8], addr[0], addr[1])
        elif addr != self.host_addr:
            self.clients[addr] = now


# ── UDP Relay Protocol ─────────────────────────────────────────────────────────
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

        is_new = addr not in session.all_known
        if is_new:
            session.register_endpoint(addr)

        session.all_known[addr] = time.time()

        if addr == session.host_addr:
            # ── Host → forward to all clients ─────────────────────────────
            stale = [c for c, t in list(session.clients.items())
                     if (time.time() - t) > CLIENT_IDLE_SECS]
            for s in stale:
                del session.clients[s]
                del session.all_known[s]

            for client_addr in list(session.clients):
                self._send(data, client_addr)
        else:
            # ── Client → forward to host ───────────────────────────────────
            session.clients[addr] = time.time()
            if session.host_addr is not None:
                self._send(data, session.host_addr)
            # else: host not connected yet → buffer? For now just drop.

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
    def __init__(self, port_start: int, port_end: int, loop):
        self.port_start = port_start
        self.port_end   = port_end
        self.loop       = loop
        self.sessions:   Dict[str, RelaySession] = {}   # token → session
        self.port_map:   Dict[int, str]          = {}   # port  → token
        self._free_ports = list(range(port_start, port_end + 1))

    def _alloc_port(self) -> Optional[int]:
        return self._free_ports.pop(0) if self._free_ports else None

    def _free_port(self, port: int):
        if port not in self._free_ports:
            self._free_ports.append(port)
            self._free_ports.sort()

    async def create_session(self, token: str) -> Optional[RelaySession]:
        if token in self.sessions:
            return self.sessions[token]  # idempotent

        port = self._alloc_port()
        if port is None:
            log.error("[Relay] No free ports!")
            return None

        session = RelaySession(token, port)
        try:
            transport, _ = await self.loop.create_datagram_endpoint(
                lambda: RelayProtocol(session),
                local_addr=("0.0.0.0", port),
            )
        except OSError as e:
            log.error("[Relay] Cannot bind port %d: %s", port, e)
            self._free_port(port)
            return None

        session._transport = transport
        self.sessions[token] = session
        self.port_map[port]  = token
        log.info("[Relay] Session created: token=%s port=%d", token[:8], port)
        return session

    async def close_session(self, token: str):
        session = self.sessions.pop(token, None)
        if session is None:
            return
        self.port_map.pop(session.port, None)
        self._free_port(session.port)
        try:
            session._transport.close()
        except Exception:
            pass
        log.info("[Relay] Session closed: token=%s port=%d", token[:8], session.port)

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

    session = await manager.create_session(token)
    if session is None:
        return web.json_response({"error": "no free ports"}, status=503)

    return web.json_response({"token": session.token, "port": session.port})


async def handle_close(request: web.Request) -> web.Response:
    manager: RelayManager = request.app["manager"]
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"error": "invalid JSON"}, status=400)
    await manager.close_session(str(body.get("token", "")))
    return web.json_response({"ok": True})


async def handle_list(request: web.Request) -> web.Response:
    manager: RelayManager = request.app["manager"]
    result = [
        {
            "token":    s.token[:8] + "...",
            "port":     s.port,
            "clients":  s.client_count(),
            "has_host": s.host_addr is not None,
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
    parser.add_argument("--host",       type=str, default="0.0.0.0")
    args = parser.parse_args()

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)

    manager = RelayManager(args.port_start, args.port_end, loop)

    app = web.Application()
    app["manager"] = manager
    app.router.add_post("/session/create", handle_create)
    app.router.add_post("/session/close",  handle_close)
    app.router.add_get( "/session/list",   handle_list)
    app.router.add_get( "/health",         handle_health)

    loop.create_task(cleanup_task(manager))

    log.info("NightHunt Relay | HTTP=%s:%d | UDP range=%d-%d",
             args.host, args.http_port, args.port_start, args.port_end)

    web.run_app(app, host=args.host, port=args.http_port, loop=loop, print=None)


if __name__ == "__main__":
    main()

Architecture
------------
All players (host + clients) connect OUTBOUND to relay:sessionPort.
This creates a NAT hole so the relay can send back to them.

Packet format (prefix added by Unity client before FishNet data):
  [8 bytes token prefix: first 8 chars of session token as ASCII]
  [1 byte flags: 0x01 = HOST, 0x00 = client]
  [N bytes FishNet/Tugboat payload]

Routing rules:
  - Packet from HOST endpoint → forward to ALL client endpoints in same session
  - Packet from CLIENT endpoint → forward to HOST endpoint only

Management HTTP API (control plane, internal only):
  POST /session/create   { "token": "<uuid>", "port": 0 (auto) }  → { "port": 7781 }
  POST /session/close    { "token": "<uuid>" }
  GET  /session/list     → [ { "token": ..., "port": ..., "clients": N } ]
  GET  /health           → { "status": "ok" }

Run:
  python relay_server.py --http-port 7776 --port-start 7777 --port-end 7900
"""

import asyncio
import json
import logging
import argparse
import sys
import time
from collections import defaultdict
from typing import Dict, Optional, Tuple

from aiohttp import web

# ── Logging ────────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
log = logging.getLogger("relay")

# ── Constants ──────────────────────────────────────────────────────────────────
TOKEN_PREFIX_LEN = 8   # first 8 ASCII chars of session UUID embedded in every packet
FLAGS_OFFSET     = TOKEN_PREFIX_LEN
PAYLOAD_OFFSET   = TOKEN_PREFIX_LEN + 1
HOST_FLAG        = 0x01
CLIENT_FLAG      = 0x00
SESSION_IDLE_TTL = 7200  # seconds (2 hours) — sessions older than this auto-expire


# ── Session State ──────────────────────────────────────────────────────────────
class RelaySession:
    def __init__(self, token: str, port: int):
        self.token      = token
        self.port       = port
        self.prefix     = token[:TOKEN_PREFIX_LEN].encode("ascii")
        self.host_addr: Optional[Tuple[str, int]] = None
        self.clients: Dict[Tuple[str, int], float] = {}  # addr → last_seen
        self.created_at = time.time()
        self.last_pkt   = time.time()

    def touch(self):
        self.last_pkt = time.time()

    def is_idle(self) -> bool:
        return (time.time() - self.last_pkt) > SESSION_IDLE_TTL

    def client_count(self) -> int:
        return len(self.clients)


# ── UDP Relay Protocol ─────────────────────────────────────────────────────────
class RelayProtocol(asyncio.DatagramProtocol):
    """Asyncio UDP protocol for one relay session (one port)."""

    def __init__(self, session: RelaySession):
        self.session   = session
        self.transport = None

    def connection_made(self, transport):
        self.transport = transport
        log.info("[Relay] Session %s listening on port %d", self.session.token[:8], self.session.port)

    def datagram_received(self, data: bytes, addr: Tuple[str, int]):
        session = self.session
        session.touch()

        if len(data) < PAYLOAD_OFFSET:
            return  # too short to contain prefix + flag

        prefix = data[:TOKEN_PREFIX_LEN]
        if prefix != session.prefix:
            # Packet for wrong session (shouldn't happen since each session has its own port)
            return

        is_host = (data[FLAGS_OFFSET] == HOST_FLAG)
        payload = data[PAYLOAD_OFFSET:]  # strip our header before forwarding

        if is_host:
            # Host registered or sent game data → record host endpoint
            if session.host_addr != addr:
                log.info("[Relay] Host registered: session=%s addr=%s:%d",
                         session.token[:8], addr[0], addr[1])
                session.host_addr = addr
            # Forward to all clients
            stale = []
            for client_addr, last_seen in list(session.clients.items()):
                if client_addr == addr:
                    continue
                if (time.time() - last_seen) > 60:   # 60s idle → consider disconnected
                    stale.append(client_addr)
                    continue
                self._send(payload, client_addr)
            for s in stale:
                del session.clients[s]
        else:
            # Client packet → record/refresh client endpoint
            session.clients[addr] = time.time()
            # Forward to host
            if session.host_addr is not None:
                self._send(payload, session.host_addr)
            # else: host not registered yet → drop (client should retry)

    def _send(self, payload: bytes, addr):
        try:
            self.transport.sendto(payload, addr)
        except Exception as e:
            log.debug("[Relay] Send error to %s: %s", addr, e)

    def error_received(self, exc):
        log.warning("[Relay] UDP error: %s", exc)

    def connection_lost(self, exc):
        log.info("[Relay] Session %s UDP socket closed", self.session.token[:8])


# ── Relay Server Manager ───────────────────────────────────────────────────────
class RelayManager:
    def __init__(self, port_start: int, port_end: int, loop):
        self.port_start   = port_start
        self.port_end     = port_end
        self.loop         = loop
        self.sessions: Dict[str, RelaySession] = {}     # token → session
        self.port_map:  Dict[int, str]         = {}     # port  → token
        self._free_ports = list(range(port_start, port_end + 1))

    def _alloc_port(self) -> Optional[int]:
        if not self._free_ports:
            return None
        return self._free_ports.pop(0)

    def _free_port(self, port: int):
        if port not in self._free_ports:
            self._free_ports.append(port)
            self._free_ports.sort()

    async def create_session(self, token: str, requested_port: int = 0) -> Optional[RelaySession]:
        if token in self.sessions:
            log.info("[Relay] Session %s already exists", token[:8])
            return self.sessions[token]

        port = requested_port if requested_port else self._alloc_port()
        if port is None:
            log.error("[Relay] No free ports available!")
            return None

        session = RelaySession(token, port)

        # Bind UDP socket for this session
        try:
            transport, protocol = await self.loop.create_datagram_endpoint(
                lambda: RelayProtocol(session),
                local_addr=("0.0.0.0", port),
            )
        except OSError as e:
            log.error("[Relay] Cannot bind port %d: %s", port, e)
            self._free_port(port)
            return None

        session._transport = transport  # keep reference to close later
        self.sessions[token] = session
        self.port_map[port]  = token
        log.info("[Relay] Created session token=%s port=%d", token[:8], port)
        return session

    async def close_session(self, token: str):
        session = self.sessions.pop(token, None)
        if session is None:
            return
        self.port_map.pop(session.port, None)
        self._free_port(session.port)
        try:
            session._transport.close()
        except Exception:
            pass
        log.info("[Relay] Closed session token=%s port=%d", token[:8], session.port)

    async def cleanup_idle(self):
        """Periodically remove sessions that have been idle for TTL."""
        stale = [t for t, s in self.sessions.items() if s.is_idle()]
        for token in stale:
            log.info("[Relay] Auto-expiring idle session %s", token[:8])
            await self.close_session(token)


# ── HTTP Management API ────────────────────────────────────────────────────────
async def handle_create(request: web.Request) -> web.Response:
    manager: RelayManager = request.app["manager"]
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"error": "invalid JSON"}, status=400)

    token = body.get("token", "")
    port  = int(body.get("port", 0))

    if not token or len(token) < TOKEN_PREFIX_LEN:
        return web.json_response({"error": "token must be at least 8 chars"}, status=400)

    session = await manager.create_session(token, port)
    if session is None:
        return web.json_response({"error": "no free ports or port in use"}, status=503)

    return web.json_response({
        "token":   session.token,
        "port":    session.port,
        "created": session.created_at,
    })


async def handle_close(request: web.Request) -> web.Response:
    manager: RelayManager = request.app["manager"]
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"error": "invalid JSON"}, status=400)

    token = body.get("token", "")
    await manager.close_session(token)
    return web.json_response({"ok": True})


async def handle_list(request: web.Request) -> web.Response:
    manager: RelayManager = request.app["manager"]
    result = [
        {
            "token":    s.token[:8] + "...",
            "port":     s.port,
            "clients":  s.client_count(),
            "has_host": s.host_addr is not None,
            "idle_sec": int(time.time() - s.last_pkt),
        }
        for s in manager.sessions.values()
    ]
    return web.json_response(result)


async def handle_health(request: web.Request) -> web.Response:
    manager: RelayManager = request.app["manager"]
    return web.json_response({
        "status":       "ok",
        "sessions":     len(manager.sessions),
        "free_ports":   len(manager._free_ports),
    })


# ── Background cleanup task ────────────────────────────────────────────────────
async def cleanup_task(manager: RelayManager):
    while True:
        await asyncio.sleep(60)
        await manager.cleanup_idle()


# ── Main ───────────────────────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(description="NightHunt UDP Relay Server")
    parser.add_argument("--http-port",  type=int, default=7776, help="HTTP management API port")
    parser.add_argument("--port-start", type=int, default=7777, help="UDP session port range start")
    parser.add_argument("--port-end",   type=int, default=7900, help="UDP session port range end")
    parser.add_argument("--host",       type=str, default="0.0.0.0", help="HTTP bind address")
    args = parser.parse_args()

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)

    manager = RelayManager(args.port_start, args.port_end, loop)

    app = web.Application()
    app["manager"] = manager
    app.router.add_post("/session/create", handle_create)
    app.router.add_post("/session/close",  handle_close)
    app.router.add_get( "/session/list",   handle_list)
    app.router.add_get( "/health",         handle_health)

    # Start background cleanup
    loop.create_task(cleanup_task(manager))

    log.info("NightHunt Relay starting: HTTP=%s:%d  UDP range=%d-%d",
             args.host, args.http_port, args.port_start, args.port_end)

    web.run_app(app, host=args.host, port=args.http_port, loop=loop, print=None)


if __name__ == "__main__":
    main()
