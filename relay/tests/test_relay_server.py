"""
Tests for relay_server.py

Run (from repo root):
    python -m pytest relay/tests/test_relay_server.py -v

Requires:
    pip install aiohttp pytest pytest-asyncio

conftest.py adds relay/ to sys.path so `import relay_server` works.
"""
import asyncio
import pytest
import pytest_asyncio
import time
from unittest.mock import AsyncMock, MagicMock, patch

# conftest.py adds relay/ to sys.path
import relay_server
from relay_server import (
    HOST_REGISTRATION_MAGIC,
    HANDSHAKE_RELAY_GRACE_SECS,
    LITENET_CHANNELED_PROPERTY,
    LITENET_CONNECT_REQUEST_PROPERTY,
    LITENET_DISCONNECT_PROPERTY,
    LITENET_UNRELIABLE_PROPERTY,
    LITENET_UNCONNECTED_PROPERTY,
    RELAY_IDENTITY_HEADER_SIZE,
    RELAY_IDENTITY_MAGIC,
    RELAY_IDENTITY_VERSION,
    RelaySession,
    RelayManager,
    RelayProtocol,
    HostUpstreamProtocol,
    fnv1a_64,
    handle_create,
    handle_close,
    handle_set_host,
    handle_list,
    handle_health,
)
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer


# ── Helpers ───────────────────────────────────────────────────────────────────

def make_app(port_start: int = 17777, port_end: int = 17800) -> web.Application:
    """
    Build a test aiohttp app with an isolated RelayManager.

    `create_datagram_endpoint` is patched so no real UDP sockets are opened;
    each created session gets a mock transport so close_session works cleanly.
    """
    loop = asyncio.get_event_loop()
    manager = RelayManager(port_start, port_end, loop)
    app = web.Application()
    app["manager"] = manager
    app.router.add_post("/session/create",   handle_create)
    app.router.add_post("/session/close",    handle_close)
    app.router.add_post("/session/set-host", handle_set_host)
    app.router.add_get( "/session/list",     handle_list)
    app.router.add_get( "/health",           handle_health)
    return app


def _mock_datagram_endpoint():
    """Return a context manager that patches loop.create_datagram_endpoint."""
    mock_transport = MagicMock()
    mock_transport.close = MagicMock()

    async def _fake_endpoint(protocol_factory, **kwargs):
        proto = protocol_factory()
        proto.transport = mock_transport
        return mock_transport, proto

    return patch.object(
        asyncio.get_event_loop(), "create_datagram_endpoint", side_effect=_fake_endpoint
    )


def identity_packet(session_token: str, peer_id: int, nonce: int, payload: bytes) -> bytes:
    header = bytearray(RELAY_IDENTITY_HEADER_SIZE)
    header[0:4] = RELAY_IDENTITY_MAGIC
    header[4] = RELAY_IDENTITY_VERSION
    header[8:16] = fnv1a_64(session_token).to_bytes(8, "big")
    header[16:24] = peer_id.to_bytes(8, "big")
    header[24:32] = nonce.to_bytes(8, "big")
    return bytes(header) + payload


# ── RelaySession unit tests ───────────────────────────────────────────────────

class TestRelaySession:

    def test_touch_updates_last_pkt(self):
        s = RelaySession("token-abc123", 17777)
        before = s.last_pkt
        time.sleep(0.01)
        s.touch()
        assert s.last_pkt > before

    def test_is_idle_false_for_fresh_session(self):
        s = RelaySession("token-fresh", 17777)
        assert s.is_idle() is False

    def test_register_host_sets_host_addr(self):
        s = RelaySession("tok12345678", 17777)
        addr = ("1.2.3.4", 9000)
        s.register_host(addr)
        assert s.host_addr == addr
        assert addr in s.all_known
        assert len(s.clients) == 0  # Host not in clients dict

    def test_register_endpoint_second_becomes_client(self):
        s = RelaySession("tok12345678", 17777)
        host = ("1.2.3.4", 9000)
        client = ("5.6.7.8", 9001)
        s.register_host(host)
        s.register_endpoint(client)
        assert s.host_addr == host
        assert client in s.clients
        assert client in s.all_known

    def test_register_endpoint_host_reregisters_does_not_add_to_clients(self):
        s = RelaySession("tok12345678", 17777)
        host = ("1.2.3.4", 9000)
        s.register_host(host)
        s.register_host(host)  # Same host, second time
        assert len(s.clients) == 0

    def test_client_count(self):
        s = RelaySession("tok12345678", 17777)
        s.register_host(("h", 1))
        s.register_endpoint(("c1", 2))
        s.register_endpoint(("c2", 3))
        assert s.client_count() == 2

    def test_host_upstream_learns_distinct_nat_endpoint(self):
        s = RelaySession("tok12345678", 17777, host_ports=[17778])
        main_host = ("203.0.113.10", 40000)
        upstream_host = ("203.0.113.10", 40001)
        s.register_host(main_host)

        downstream = MagicMock()
        upstream = HostUpstreamProtocol(s, 17778, downstream)
        upstream.connection_made(MagicMock())
        upstream.datagram_received(HOST_REGISTRATION_MAGIC, upstream_host)

        assert s.host_addr == main_host
        assert upstream.host_addr == upstream_host
        assert s.has_host() is True
        assert s.registered_host_upstream_count() == 1

    def test_wrapped_host_registration_is_detected(self):
        token = "tok12345678"
        s = RelaySession(token, 17777, host_ports=[17778])
        host = ("203.0.113.10", 40000)
        upstream_host = ("203.0.113.10", 40001)
        registration = identity_packet(
            token,
            peer_id=99,
            nonce=1,
            payload=bytes([LITENET_UNCONNECTED_PROPERTY]) + HOST_REGISTRATION_MAGIC,
        )

        relay = RelayProtocol(s)
        relay.datagram_received(registration, host)

        upstream = HostUpstreamProtocol(s, 17778, MagicMock())
        upstream.connection_made(MagicMock())
        upstream.datagram_received(registration, upstream_host)

        assert s.host_addr == host
        assert upstream.host_addr == upstream_host

    @pytest.mark.asyncio
    async def test_client_packet_uses_upstream_specific_host_endpoint(self):
        s = RelaySession("tok12345678", 17777, host_ports=[17778])
        main_host = ("203.0.113.10", 40000)
        upstream_host = ("203.0.113.10", 40001)
        client = ("198.51.100.50", 51000)
        s.register_host(main_host)

        downstream = MagicMock()
        upstream_transport = MagicMock()
        upstream = HostUpstreamProtocol(s, 17778, downstream)
        upstream.connection_made(upstream_transport)
        upstream.datagram_received(HOST_REGISTRATION_MAGIC, upstream_host)

        relay = RelayProtocol(s)
        packet = bytes([LITENET_CONNECT_REQUEST_PROPERTY]) + b"fishnet-connect"
        await relay._forward_client_packet(packet, client)

        upstream_transport.sendto.assert_called_once_with(packet, upstream_host)

    @pytest.mark.asyncio
    async def test_identity_peer_reuses_upstream_when_endpoint_changes(self):
        token = "tok12345678"
        s = RelaySession(token, 17777, host_ports=[17778])
        host = ("203.0.113.10", 40000)
        first_addr = ("198.51.100.50", 51000)
        rebound_addr = ("198.51.100.50", 51001)
        peer_id = 42
        s.register_host(host)

        upstream_transport = MagicMock()
        upstream = HostUpstreamProtocol(s, 17778, MagicMock())
        upstream.connection_made(upstream_transport)
        upstream.datagram_received(HOST_REGISTRATION_MAGIC, host)

        relay = RelayProtocol(s)
        first_packet = identity_packet(token, peer_id, 1, bytes([LITENET_CONNECT_REQUEST_PROPERTY]) + b"connect")
        rebound_packet = identity_packet(token, peer_id, 2, bytes([0]) + b"game-data")

        await relay._forward_client_packet(first_packet, first_addr)
        await relay._forward_client_packet(rebound_packet, rebound_addr)

        assert first_addr not in s.clients
        assert first_addr not in s.client_relays
        assert s.client_relays[rebound_addr] is upstream
        assert s.client_peer_relays[peer_id] is upstream
        assert upstream.client_addr == rebound_addr
        assert upstream.client_peer_id == peer_id
        assert s.upstream_recycle_count == 0
        assert upstream_transport.sendto.call_args_list[0].args == (first_packet, host)
        assert upstream_transport.sendto.call_args_list[-1].args == (rebound_packet, host)

    @pytest.mark.asyncio
    async def test_identity_wrapped_handshake_host_disconnect_is_suppressed(self):
        token = "tok12345678"
        s = RelaySession(token, 17777, host_ports=[17778])
        host = ("203.0.113.10", 40000)
        client = ("198.51.100.50", 51000)
        peer_id = 42
        s.register_host(host)

        downstream = MagicMock()
        upstream_transport = MagicMock()
        upstream = HostUpstreamProtocol(s, 17778, downstream)
        upstream.connection_made(upstream_transport)
        upstream.datagram_received(HOST_REGISTRATION_MAGIC, host)

        relay = RelayProtocol(s)
        await relay._forward_client_packet(
            identity_packet(token, peer_id, 1, bytes([LITENET_CONNECT_REQUEST_PROPERTY]) + b"connect"),
            client)

        disconnect_packet = identity_packet(token, 7, 99, bytes([LITENET_DISCONNECT_PROPERTY]) + b"bye")
        upstream.datagram_received(disconnect_packet, host)

        downstream.sendto.assert_not_called()
        assert s.clients[client] > 0
        assert s.client_relays[client] is upstream
        assert s.client_peer_relays[peer_id] is upstream
        assert s.free_host_ports == []
        assert s.cooling_host_ports == {}

    @pytest.mark.asyncio
    async def test_identity_wrapped_established_host_disconnect_releases_upstream(self):
        token = "tok12345678"
        s = RelaySession(token, 17777, host_ports=[17778])
        host = ("203.0.113.10", 40000)
        client = ("198.51.100.50", 51000)
        peer_id = 42
        s.register_host(host)

        downstream = MagicMock()
        upstream_transport = MagicMock()
        upstream = HostUpstreamProtocol(s, 17778, downstream)
        upstream.connection_made(upstream_transport)
        upstream.datagram_received(HOST_REGISTRATION_MAGIC, host)

        relay = RelayProtocol(s)
        await relay._forward_client_packet(
            identity_packet(token, peer_id, 1, bytes([LITENET_CONNECT_REQUEST_PROPERTY]) + b"connect"),
            client)
        await relay._forward_client_packet(
            identity_packet(token, peer_id, 1, bytes([LITENET_CHANNELED_PROPERTY]) + b"client-game"),
            client)
        upstream.datagram_received(
            identity_packet(token, 7, 99, bytes([LITENET_CHANNELED_PROPERTY]) + b"host-game"),
            host)
        downstream.reset_mock()

        disconnect_packet = identity_packet(token, 7, 99, bytes([LITENET_DISCONNECT_PROPERTY]) + b"bye")
        upstream.datagram_received(disconnect_packet, host)

        downstream.sendto.assert_called_with(disconnect_packet, client)
        assert client not in s.clients
        assert client not in s.client_relays
        assert peer_id not in s.client_peer_relays
        assert s.free_host_ports == []
        assert list(s.cooling_host_ports.keys()) == [17778]

    @pytest.mark.asyncio
    async def test_recycles_oldest_client_when_upstreams_are_exhausted(self):
        s = RelaySession("tok12345678", 17777, host_ports=[17778])
        host = ("203.0.113.10", 40000)
        first_client = ("198.51.100.50", 51000)
        retry_client = ("198.51.100.50", 51001)
        s.register_host(host)

        upstream_transport = MagicMock()
        upstream = HostUpstreamProtocol(s, 17778, MagicMock())
        upstream.connection_made(upstream_transport)
        upstream.datagram_received(HOST_REGISTRATION_MAGIC, host)

        relay = RelayProtocol(s)
        first_packet = bytes([LITENET_CONNECT_REQUEST_PROPERTY]) + b"first-connect"
        retry_packet = bytes([LITENET_CONNECT_REQUEST_PROPERTY]) + b"retry-connect"
        await relay._forward_client_packet(first_packet, first_client)
        upstream.client_assigned_at -= HANDSHAKE_RELAY_GRACE_SECS + 1
        await relay._forward_client_packet(retry_packet, retry_client)

        assert first_client not in s.clients
        assert first_client not in s.client_relays
        assert retry_client in s.clients
        assert s.client_relays[retry_client] is upstream
        assert upstream.client_addr == retry_client
        assert s.upstream_recycle_count == 1
        assert upstream_transport.sendto.call_args_list[-1].args == (retry_packet, host)

    @pytest.mark.asyncio
    async def test_active_client_upstream_is_not_recycled(self):
        s = RelaySession("tok12345678", 17777, host_ports=[17778])
        host = ("203.0.113.10", 40000)
        first_client = ("198.51.100.50", 51000)
        retry_client = ("198.51.100.50", 51001)
        s.register_host(host)

        upstream_transport = MagicMock()
        upstream = HostUpstreamProtocol(s, 17778, MagicMock())
        upstream.connection_made(upstream_transport)
        upstream.datagram_received(HOST_REGISTRATION_MAGIC, host)

        relay = RelayProtocol(s)
        await relay._forward_client_packet(bytes([LITENET_CONNECT_REQUEST_PROPERTY]) + b"first-connect", first_client)
        await relay._forward_client_packet(bytes([0]) + b"game-data", first_client)
        upstream.client_assigned_at -= HANDSHAKE_RELAY_GRACE_SECS + 1
        await relay._forward_client_packet(bytes([LITENET_CONNECT_REQUEST_PROPERTY]) + b"retry-connect", retry_client)

        assert s.client_relays[first_client] is upstream
        assert retry_client not in s.client_relays
        assert retry_client not in s.clients
        assert upstream.client_addr == first_client
        assert s.upstream_recycle_count == 0

    @pytest.mark.asyncio
    async def test_host_disconnect_releases_client_upstream(self):
        s = RelaySession("tok12345678", 17777, host_ports=[17778])
        host = ("203.0.113.10", 40000)
        client = ("198.51.100.50", 51000)
        s.register_host(host)

        downstream = MagicMock()
        upstream_transport = MagicMock()
        upstream = HostUpstreamProtocol(s, 17778, downstream)
        upstream.connection_made(upstream_transport)
        upstream.datagram_received(HOST_REGISTRATION_MAGIC, host)

        relay = RelayProtocol(s)
        await relay._forward_client_packet(bytes([LITENET_CONNECT_REQUEST_PROPERTY]) + b"connect", client)
        await relay._forward_client_packet(bytes([LITENET_UNRELIABLE_PROPERTY]) + b"client-game", client)
        upstream.datagram_received(bytes([LITENET_CHANNELED_PROPERTY]) + b"host-game", host)
        downstream.reset_mock()
        assert s.free_host_ports == []

        disconnect_packet = bytes([LITENET_DISCONNECT_PROPERTY]) + b"bye"
        upstream.datagram_received(disconnect_packet, host)

        downstream.sendto.assert_called_with(disconnect_packet, client)
        assert client not in s.clients
        assert client not in s.client_relays
        assert s.free_host_ports == []
        assert list(s.cooling_host_ports.keys()) == [17778]
        assert upstream.client_addr is None

    @pytest.mark.asyncio
    async def test_client_disconnect_releases_client_upstream(self):
        s = RelaySession("tok12345678", 17777, host_ports=[17778])
        host = ("203.0.113.10", 40000)
        client = ("198.51.100.50", 51000)
        s.register_host(host)

        upstream_transport = MagicMock()
        upstream = HostUpstreamProtocol(s, 17778, MagicMock())
        upstream.connection_made(upstream_transport)
        upstream.datagram_received(HOST_REGISTRATION_MAGIC, host)

        relay = RelayProtocol(s)
        await relay._forward_client_packet(bytes([LITENET_CONNECT_REQUEST_PROPERTY]) + b"connect", client)
        await relay._forward_client_packet(bytes([LITENET_UNRELIABLE_PROPERTY]) + b"client-game", client)
        upstream.datagram_received(bytes([LITENET_CHANNELED_PROPERTY]) + b"host-game", host)
        assert s.free_host_ports == []

        disconnect_packet = bytes([LITENET_DISCONNECT_PROPERTY]) + b"bye"
        await relay._forward_client_packet(disconnect_packet, client)

        upstream_transport.sendto.assert_any_call(disconnect_packet, host)
        assert client not in s.clients
        assert client not in s.client_relays
        assert s.free_host_ports == []
        assert list(s.cooling_host_ports.keys()) == [17778]
        assert upstream.client_addr is None


# ── RelayManager unit tests ───────────────────────────────────────────────────

class TestRelayManager:

    def test_alloc_port_sequential(self):
        loop = asyncio.new_event_loop()
        mgr = RelayManager(20000, 20005, loop)
        assert mgr._alloc_port() == 20000
        assert mgr._alloc_port() == 20001

    def test_alloc_port_returns_none_when_exhausted(self):
        loop = asyncio.new_event_loop()
        mgr = RelayManager(20000, 20000, loop)
        mgr._alloc_port()  # consume only port
        assert mgr._alloc_port() is None

    def test_free_port_returns_port_to_pool(self):
        loop = asyncio.new_event_loop()
        mgr = RelayManager(20000, 20001, loop)
        mgr._alloc_port()  # 20000
        mgr._alloc_port()  # 20001
        mgr._free_port(20000)
        assert 20000 in mgr._free_ports

    def test_free_ports_remain_sorted(self):
        loop = asyncio.new_event_loop()
        mgr = RelayManager(20000, 20003, loop)
        for _ in range(4):
            mgr._alloc_port()
        mgr._free_port(20002)
        mgr._free_port(20000)
        assert mgr._free_ports == sorted(mgr._free_ports)


# ── HTTP API integration tests ────────────────────────────────────────────────

@pytest.fixture
def event_loop():
    """Use a single event loop per test module."""
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()


@pytest.mark.asyncio
class TestHttpApi:

    @pytest_asyncio.fixture
    async def client(self):
        app = make_app(17777, 17850)
        manager: RelayManager = app["manager"]

        # Patch create_datagram_endpoint on the manager's loop so no real
        # UDP sockets are opened during HTTP API tests.
        mock_transport = MagicMock()
        mock_transport.close = MagicMock()

        async def _fake_endpoint(protocol_factory, **kwargs):
            proto = protocol_factory()
            proto.transport = mock_transport
            return mock_transport, proto

        manager.loop.create_datagram_endpoint = _fake_endpoint  # type: ignore

        server = TestServer(app)
        test_client = TestClient(server)
        await test_client.start_server()
        yield test_client
        await test_client.close()

    # ── GET /health ──────────────────────────────────────────────────────────

    async def test_health_returns_ok(self, client):
        resp = await client.get("/health")
        assert resp.status == 200
        body = await resp.json()
        assert body["status"] == "ok"
        assert "sessions" in body
        assert "free_ports" in body

    async def test_health_shows_correct_free_port_count(self, client):
        resp = await client.get("/health")
        body = await resp.json()
        # Port range 17777-17850 = 74 ports
        assert body["free_ports"] == 74

    # ── POST /session/create ─────────────────────────────────────────────────

    async def test_create_session_returns_port(self, client):
        resp = await client.post("/session/create",
                                 json={"token": "abcdefgh-1234-5678-90ab-cdef01234567"})
        assert resp.status == 200
        body = await resp.json()
        assert "port" in body
        assert body["port"] >= 17777
        assert body["token"] == "abcdefgh-1234-5678-90ab-cdef01234567"

    async def test_create_session_idempotent(self, client):
        token = "idempotent-token-123456789"
        resp1 = await client.post("/session/create", json={"token": token})
        resp2 = await client.post("/session/create", json={"token": token})
        body1 = await resp1.json()
        body2 = await resp2.json()
        assert body1["port"] == body2["port"]

    async def test_create_session_token_too_short(self, client):
        resp = await client.post("/session/create", json={"token": "short"})
        assert resp.status == 400
        body = await resp.json()
        assert "error" in body

    async def test_create_session_invalid_json(self, client):
        resp = await client.post("/session/create",
                                 data="not-json",
                                 headers={"Content-Type": "application/json"})
        assert resp.status == 400

    async def test_create_session_missing_token(self, client):
        resp = await client.post("/session/create", json={})
        assert resp.status == 400

    # ── POST /session/close ──────────────────────────────────────────────────

    async def test_close_existing_session(self, client):
        token = "close-test-token-0000000001"
        await client.post("/session/create", json={"token": token})

        resp = await client.post("/session/close", json={"token": token})
        assert resp.status == 200
        body = await resp.json()
        assert body["ok"] is True

        # Verify the session is gone from the list
        list_resp = await client.get("/session/list")
        sessions = await list_resp.json()
        tokens_present = [s["token"] for s in sessions]
        assert not any(token[:8] in t for t in tokens_present)

    async def test_close_nonexistent_session_returns_ok(self, client):
        """Closing a nonexistent session should be a no-op (idempotent)."""
        resp = await client.post("/session/close", json={"token": "ghost-token-000000"})
        assert resp.status == 200
        assert (await resp.json())["ok"] is True

    # ── POST /session/set-host ───────────────────────────────────────────────

    async def test_set_host_updates_host_addr(self, client):
        token = "sethost-token-000000000001"
        await client.post("/session/create", json={"token": token})

        resp = await client.post("/session/set-host",
                                 json={"token": token, "host_addr": "203.0.113.5:9876"})
        assert resp.status == 200
        body = await resp.json()
        assert body["ok"] is True
        assert body["host_set"] is True

    async def test_set_host_unknown_token_returns_404(self, client):
        resp = await client.post("/session/set-host",
                                 json={"token": "unknown-token-000000000", "host_addr": "1.2.3.4:8000"})
        assert resp.status == 404

    async def test_set_host_malformed_addr_returns_400(self, client):
        token = "sethost-bad-addr-000000001"
        await client.post("/session/create", json={"token": token})

        resp = await client.post("/session/set-host",
                                 json={"token": token, "host_addr": "not-an-addr"})
        assert resp.status == 400

    async def test_set_host_missing_token_returns_400(self, client):
        resp = await client.post("/session/set-host", json={})
        assert resp.status == 400

    # ── GET /session/list ────────────────────────────────────────────────────

    async def test_list_is_empty_on_start(self, client):
        resp = await client.get("/session/list")
        assert resp.status == 200
        assert await resp.json() == []

    async def test_list_shows_created_session(self, client):
        token = "listtoken-00000000000001"
        await client.post("/session/create", json={"token": token})

        resp = await client.get("/session/list")
        sessions = await resp.json()
        assert len(sessions) >= 1
        assert any(s["token"].startswith(token[:8]) for s in sessions)

    async def test_list_shows_client_count(self, client):
        token = "listtoken-count-00000001"
        await client.post("/session/create", json={"token": token})

        resp = await client.get("/session/list")
        sessions = await resp.json()
        session = next(s for s in sessions if s["token"].startswith(token[:8]))
        assert session["clients"] == 0
        assert session["has_host"] is False

    # ── Port exhaustion ──────────────────────────────────────────────────────

    async def test_no_free_ports_returns_503(self):
        """A manager with only 1 port should return 503 after one session."""
        app = make_app(port_start=19999, port_end=19999)
        manager: RelayManager = app["manager"]

        mock_transport = MagicMock()
        mock_transport.close = MagicMock()

        async def _fake_endpoint(protocol_factory, **kwargs):
            proto = protocol_factory()
            proto.transport = mock_transport
            return mock_transport, proto

        manager.loop.create_datagram_endpoint = _fake_endpoint  # type: ignore

        server = TestServer(app)
        test_client = TestClient(server)
        await test_client.start_server()
        try:
            token1 = "firsttoken-000000000001"
            await test_client.post("/session/create", json={"token": token1})

            token2 = "secondtoken-00000000002"
            resp = await test_client.post("/session/create", json={"token": token2})
            assert resp.status == 503
        finally:
            await test_client.close()
