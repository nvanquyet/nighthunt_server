"""
Standalone relay flow smoke test.

Run from NightHuntServer:
    python relay/tests/relay_flow_smoke.py

This intentionally avoids Unity, pytest, and aiohttp. It drives the relay UDP
protocol classes directly with capture transports so the client->hostPort and
hostPort->client routes are visible in the console.
"""
from __future__ import annotations

import asyncio
import sys
import types
from pathlib import Path
from typing import List, Tuple


def _install_aiohttp_stub_if_missing() -> None:
    try:
        import aiohttp  # noqa: F401
        return
    except ModuleNotFoundError:
        pass

    class _DummyWebType:
        pass

    web = types.SimpleNamespace(
        Application=_DummyWebType,
        Request=_DummyWebType,
        Response=_DummyWebType,
        json_response=lambda *args, **kwargs: None,
        run_app=lambda *args, **kwargs: None,
    )

    aiohttp = types.ModuleType("aiohttp")
    aiohttp.web = web
    sys.modules["aiohttp"] = aiohttp
    sys.modules["aiohttp.web"] = web


RELAY_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(RELAY_DIR))
_install_aiohttp_stub_if_missing()

from relay_server import (  # noqa: E402
    HOST_REGISTRATION_MAGIC,
    LITENET_CHANNELED_PROPERTY,
    LITENET_CONNECT_ACCEPT_PROPERTY,
    LITENET_CONNECT_REQUEST_PROPERTY,
    LITENET_PEER_NOT_FOUND_PROPERTY,
    RELAY_IDENTITY_HEADER_SIZE,
    RELAY_IDENTITY_MAGIC,
    RELAY_IDENTITY_VERSION,
    HostUpstreamProtocol,
    RelayProtocol,
    RelaySession,
    fnv1a_64,
    litenet_packet_property,
    parse_relay_identity,
    relay_payload,
)


Address = Tuple[str, int]


class CaptureTransport:
    def __init__(self, name: str):
        self.name = name
        self.sends: List[Tuple[bytes, Address]] = []

    def sendto(self, data: bytes, addr: Address) -> None:
        self.sends.append((data, addr))

    def close(self) -> None:
        pass

    def clear(self) -> None:
        self.sends.clear()

    def last(self) -> Tuple[bytes, Address]:
        if not self.sends:
            raise AssertionError(f"{self.name} did not send any packets")
        return self.sends[-1]


def identity_packet(token: str, peer_id: int, nonce: int, payload: bytes) -> bytes:
    header = bytearray(RELAY_IDENTITY_HEADER_SIZE)
    header[0:4] = RELAY_IDENTITY_MAGIC
    header[4] = RELAY_IDENTITY_VERSION
    header[8:16] = fnv1a_64(token).to_bytes(8, "big")
    header[16:24] = peer_id.to_bytes(8, "big")
    header[24:32] = nonce.to_bytes(8, "big")
    return bytes(header) + payload


def prop_of(packet: bytes) -> int:
    identity = parse_relay_identity(packet)
    prop = litenet_packet_property(relay_payload(packet, identity))
    if prop is None:
        raise AssertionError("packet has no LiteNetLib property")
    return prop


def assert_last_send(transport: CaptureTransport, expected_packet: bytes, expected_addr: Address, label: str) -> None:
    actual_packet, actual_addr = transport.last()
    if actual_packet != expected_packet or actual_addr != expected_addr:
        raise AssertionError(
            f"{label}: expected prop={prop_of(expected_packet)} addr={expected_addr}, "
            f"got prop={prop_of(actual_packet)} addr={actual_addr}"
        )
    print(f"PASS {label}: prop={prop_of(expected_packet)} addr={expected_addr[0]}:{expected_addr[1]}")


def assert_no_send(transport: CaptureTransport, label: str) -> None:
    if transport.sends:
        sent_packet, sent_addr = transport.sends[-1]
        raise AssertionError(
            f"{label}: expected no forward, got prop={prop_of(sent_packet)} addr={sent_addr}"
        )
    print(f"PASS {label}: no packet forwarded")


async def main() -> None:
    token = "tok12345678"
    host: Address = ("203.0.113.10", 40000)
    first_client: Address = ("198.51.100.50", 51000)
    rebound_client: Address = ("198.51.100.50", 51001)
    client_peer_id = 42
    host_peer_id = 7

    session = RelaySession(token, 17777, host_ports=[17778])
    session.register_host(host)

    host_port_transport = CaptureTransport("hostPort->host")
    downstream_transport = CaptureTransport("sessionPort->client")

    upstream = HostUpstreamProtocol(session, 17778, downstream_transport)
    upstream.connection_made(host_port_transport)
    upstream.datagram_received(HOST_REGISTRATION_MAGIC, host)

    relay = RelayProtocol(session)

    print("Relay flow smoke: client -> relay hostPort -> host -> relay -> client")

    connect = identity_packet(
        token,
        client_peer_id,
        1,
        bytes([LITENET_CONNECT_REQUEST_PROPERTY]) + b"connect",
    )
    await relay._forward_client_packet(connect, first_client)
    assert_last_send(host_port_transport, connect, host, "client connect forwarded to host")

    accept = identity_packet(
        token,
        host_peer_id,
        1,
        bytes([LITENET_CONNECT_ACCEPT_PROPERTY]) + b"accept",
    )
    upstream.datagram_received(accept, host)
    assert_last_send(downstream_transport, accept, first_client, "host accept forwarded to client")

    downstream_transport.clear()
    handshake_peer_not_found = identity_packet(
        token,
        host_peer_id,
        99,
        bytes([LITENET_PEER_NOT_FOUND_PROPERTY]) + b"stale-handshake-peer",
    )
    upstream.datagram_received(handshake_peer_not_found, host)
    assert_no_send(downstream_transport, "host PeerNotFound suppressed during handshake")

    client_game = identity_packet(
        token,
        client_peer_id,
        1,
        bytes([LITENET_CHANNELED_PROPERTY]) + b"client-game",
    )
    await relay._forward_client_packet(client_game, first_client)
    assert_last_send(host_port_transport, client_game, host, "client game packet forwarded to host")

    host_game = identity_packet(
        token,
        host_peer_id,
        2,
        bytes([LITENET_CHANNELED_PROPERTY]) + b"host-game",
    )
    upstream.datagram_received(host_game, host)
    assert_last_send(downstream_transport, host_game, first_client, "host game packet forwarded to client")
    if not upstream.has_established_game_exchange():
        raise AssertionError("game exchange was not marked established")
    print("PASS game exchange established")

    rebound_connect = identity_packet(
        token,
        client_peer_id,
        2,
        bytes([LITENET_CONNECT_REQUEST_PROPERTY]) + b"reconnect",
    )
    await relay._forward_client_packet(rebound_connect, rebound_client)
    assert_last_send(host_port_transport, rebound_connect, host, "rebound connect forwarded to same hostPort")

    if first_client in session.client_relays:
        raise AssertionError("old client endpoint is still mapped after identity rebound")
    if session.client_relays.get(rebound_client) is not upstream:
        raise AssertionError("rebound client endpoint is not mapped to the original upstream")
    print("PASS rebound endpoint replaced old endpoint without changing hostPort")

    rebound_client_game = identity_packet(
        token,
        client_peer_id,
        2,
        bytes([LITENET_CHANNELED_PROPERTY]) + b"rebound-client-game",
    )
    await relay._forward_client_packet(rebound_client_game, rebound_client)
    assert_last_send(host_port_transport, rebound_client_game, host, "rebound client game forwarded to host")

    rebound_host_game = identity_packet(
        token,
        host_peer_id,
        3,
        bytes([LITENET_CHANNELED_PROPERTY]) + b"rebound-host-game",
    )
    upstream.datagram_received(rebound_host_game, host)
    assert_last_send(downstream_transport, rebound_host_game, rebound_client, "rebound host game forwarded to new client")

    downstream_transport.clear()
    stale_peer_not_found = identity_packet(
        token,
        host_peer_id,
        1,
        bytes([LITENET_PEER_NOT_FOUND_PROPERTY]) + b"stale-rebound-peer",
    )
    upstream.datagram_received(stale_peer_not_found, host)
    assert_no_send(downstream_transport, "stale host PeerNotFound suppressed during rebound grace")

    print("PASS relay flow smoke completed")


if __name__ == "__main__":
    asyncio.run(main())
