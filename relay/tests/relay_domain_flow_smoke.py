"""
Real-domain UDP relay flow smoke test.

Run from NightHuntServer:
    python relay/tests/relay_domain_flow_smoke.py --relay-host vawnwuyest.me

If the relay HTTP control API is not exposed publicly, pass an existing session
port pair from backend/relay logs instead:
    python relay/tests/relay_domain_flow_smoke.py --relay-host vawnwuyest.me --token <token> --session-port 7787 --host-port 7788

This does not use Unity. It opens local UDP sockets to act as a host and a
client, then verifies packet routing through the real relay process.
"""
from __future__ import annotations

import argparse
import json
import secrets
import socket
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Optional, Tuple


HOST_REGISTRATION_MAGIC = b"NH_RELAY_HOST"
RELAY_IDENTITY_MAGIC = b"NHR1"
RELAY_IDENTITY_VERSION = 1
RELAY_IDENTITY_HEADER_SIZE = 32
LITENET_UNRELIABLE_PROPERTY = 0
LITENET_CHANNELED_PROPERTY = 1
LITENET_CONNECT_REQUEST_PROPERTY = 5
LITENET_CONNECT_ACCEPT_PROPERTY = 6
LITENET_PEER_NOT_FOUND_PROPERTY = 14


Address = Tuple[str, int]


@dataclass
class RelaySessionInfo:
    token: str
    session_port: int
    host_port: int
    created_by_test: bool


def fnv1a_64(value: str) -> int:
    result = 14695981039346656037
    for byte in (value or "").encode("utf-8"):
        result ^= byte
        result = (result * 1099511628211) & 0xFFFFFFFFFFFFFFFF
    return result or 1


def identity_packet(token: str, peer_id: int, nonce: int, payload: bytes) -> bytes:
    header = bytearray(RELAY_IDENTITY_HEADER_SIZE)
    header[0:4] = RELAY_IDENTITY_MAGIC
    header[4] = RELAY_IDENTITY_VERSION
    header[8:16] = fnv1a_64(token).to_bytes(8, "big")
    header[16:24] = peer_id.to_bytes(8, "big")
    header[24:32] = nonce.to_bytes(8, "big")
    return bytes(header) + payload


def prop_of(packet: bytes) -> Optional[int]:
    if len(packet) >= RELAY_IDENTITY_HEADER_SIZE and packet[:4] == RELAY_IDENTITY_MAGIC:
        packet = packet[RELAY_IDENTITY_HEADER_SIZE:]
    if not packet:
        return None
    return packet[0] & 0x1F


def build_token() -> str:
    return "codex" + secrets.token_hex(8)


def post_json(url: str, body: dict, timeout: float) -> dict:
    payload = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        data = response.read().decode("utf-8")
    return json.loads(data)


def get_json(url: str, bearer_token: Optional[str], timeout: float) -> dict:
    headers = {}
    if bearer_token:
        headers["Authorization"] = f"Bearer {bearer_token}"
    request = urllib.request.Request(url, headers=headers, method="GET")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        data = response.read().decode("utf-8")
    return json.loads(data)


def unwrap_api_response(response: dict) -> dict:
    data = response.get("data")
    return data if isinstance(data, dict) else response


def read_relay_info(url: str, bearer_token: Optional[str], timeout: float) -> Tuple[Optional[str], RelaySessionInfo]:
    response = unwrap_api_response(get_json(url, bearer_token, timeout))
    host_ports = response.get("relayHostPorts") or response.get("hostPorts") or []
    token = response.get("relayToken") or response.get("sessionToken") or response.get("token")
    port = response.get("relayPort") or response.get("port")
    host = response.get("relayHost")

    if not token or not port or not host_ports:
        raise RuntimeError(f"relay info response does not contain token/port/hostPorts: {response!r}")

    return (
        str(host).strip() if host else None,
        RelaySessionInfo(
            token=str(token),
            session_port=int(port),
            host_port=int(host_ports[0]),
            created_by_test=False,
        ),
    )


def create_session(http_url: str, token: str, timeout: float) -> RelaySessionInfo:
    response = post_json(
        http_url.rstrip("/") + "/session/create",
        {"token": token, "hostUpstreamCount": 1},
        timeout,
    )
    host_ports = response.get("hostPorts") or []
    if not response.get("port") or not host_ports:
        raise RuntimeError(f"invalid /session/create response: {response!r}")
    return RelaySessionInfo(
        token=str(response.get("token") or token),
        session_port=int(response["port"]),
        host_port=int(host_ports[0]),
        created_by_test=True,
    )


def close_session(http_url: str, token: str, timeout: float) -> None:
    try:
        post_json(http_url.rstrip("/") + "/session/close", {"token": token}, timeout)
    except Exception as exc:
        print(f"WARN could not close relay session token={token[:8]}: {exc}")


def send_repeated(sock: socket.socket, packet: bytes, addr: Address, count: int = 3, delay: float = 0.05) -> None:
    for _ in range(count):
        sock.sendto(packet, addr)
        time.sleep(delay)


def drain(sock: socket.socket, duration: float = 0.15) -> None:
    old_timeout = sock.gettimeout()
    deadline = time.monotonic() + duration
    try:
        while time.monotonic() < deadline:
            sock.settimeout(max(0.01, deadline - time.monotonic()))
            try:
                sock.recvfrom(65535)
            except socket.timeout:
                break
    finally:
        sock.settimeout(old_timeout)


def recv_expected(sock: socket.socket, expected: bytes, timeout: float, label: str) -> Address:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        sock.settimeout(max(0.01, deadline - time.monotonic()))
        try:
            packet, addr = sock.recvfrom(65535)
        except socket.timeout:
            break

        if packet == expected:
            print(f"PASS {label}: prop={prop_of(packet)} from={addr[0]}:{addr[1]}")
            return addr

        print(
            f"INFO {label}: ignored packet prop={prop_of(packet)} "
            f"len={len(packet)} from={addr[0]}:{addr[1]}"
        )

    raise AssertionError(f"{label}: timed out waiting for prop={prop_of(expected)} len={len(expected)}")


def assert_no_packet(sock: socket.socket, timeout: float, label: str) -> None:
    drain(sock, 0.05)
    sock.settimeout(timeout)
    try:
        packet, addr = sock.recvfrom(65535)
    except socket.timeout:
        print(f"PASS {label}: no packet received for {timeout:.2f}s")
        return

    raise AssertionError(
        f"{label}: expected no packet, got prop={prop_of(packet)} "
        f"len={len(packet)} from={addr[0]}:{addr[1]}"
    )


def resolve_session(args: argparse.Namespace) -> RelaySessionInfo:
    if args.relay_info_url:
        print(f"INFO reading relay info via {args.relay_info_url}")
        relay_host, session = read_relay_info(args.relay_info_url, args.auth_token, args.http_timeout)
        if relay_host:
            args.relay_host = relay_host
        return session

    token = args.token or build_token()
    if args.session_port and args.host_port:
        return RelaySessionInfo(token, args.session_port, args.host_port, created_by_test=False)

    if args.session_port or args.host_port:
        raise ValueError("--session-port and --host-port must be passed together")

    http_url = args.http_url or f"http://{args.relay_host}:7776"
    print(f"INFO creating relay session via {http_url}/session/create token={token[:8]}")
    return create_session(http_url, token, args.http_timeout)


def run_flow(args: argparse.Namespace, session: RelaySessionInfo) -> None:
    relay_session_addr = (args.relay_host, session.session_port)
    relay_host_addr = (args.relay_host, session.host_port)
    client_peer_id = args.client_peer_id
    host_peer_id = args.host_peer_id

    host_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    client_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    rebound_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    try:
        for sock in (host_sock, client_sock, rebound_sock):
            sock.bind(("0.0.0.0", 0))
            sock.settimeout(args.udp_timeout)

        print(
            "INFO local sockets: "
            f"host={host_sock.getsockname()[0]}:{host_sock.getsockname()[1]} "
            f"client={client_sock.getsockname()[0]}:{client_sock.getsockname()[1]} "
            f"rebound={rebound_sock.getsockname()[0]}:{rebound_sock.getsockname()[1]}"
        )
        print(
            "INFO relay ports: "
            f"session={args.relay_host}:{session.session_port} "
            f"hostPort={args.relay_host}:{session.host_port} token={session.token[:8]}"
        )

        send_repeated(host_sock, HOST_REGISTRATION_MAGIC, relay_session_addr)
        send_repeated(host_sock, HOST_REGISTRATION_MAGIC, relay_host_addr)
        print("PASS host registration packets sent to session port and hostPort")

        connect = identity_packet(
            session.token,
            client_peer_id,
            1,
            bytes([LITENET_CONNECT_REQUEST_PROPERTY]) + b"domain-connect",
        )
        send_repeated(client_sock, connect, relay_session_addr)
        recv_expected(host_sock, connect, args.udp_timeout, "client connect reached host via relay hostPort")

        accept = identity_packet(
            session.token,
            host_peer_id,
            1,
            bytes([LITENET_CONNECT_ACCEPT_PROPERTY]) + b"domain-accept",
        )
        send_repeated(host_sock, accept, relay_host_addr)
        recv_expected(client_sock, accept, args.udp_timeout, "host accept reached client via relay session port")

        peer_not_found = identity_packet(
            session.token,
            host_peer_id,
            99,
            bytes([LITENET_PEER_NOT_FOUND_PROPERTY]) + b"domain-stale-handshake-peer",
        )
        send_repeated(host_sock, peer_not_found, relay_host_addr)
        assert_no_packet(client_sock, args.quiet_timeout, "host PeerNotFound suppressed during handshake")

        client_game = identity_packet(
            session.token,
            client_peer_id,
            1,
            bytes([LITENET_CHANNELED_PROPERTY]) + b"domain-client-game",
        )
        send_repeated(client_sock, client_game, relay_session_addr)
        recv_expected(host_sock, client_game, args.udp_timeout, "client game reached host")

        host_game = identity_packet(
            session.token,
            host_peer_id,
            2,
            bytes([LITENET_CHANNELED_PROPERTY]) + b"domain-host-game",
        )
        send_repeated(host_sock, host_game, relay_host_addr)
        recv_expected(client_sock, host_game, args.udp_timeout, "host game reached client")

        rebound_connect = identity_packet(
            session.token,
            client_peer_id,
            2,
            bytes([LITENET_CONNECT_REQUEST_PROPERTY]) + b"domain-reconnect",
        )
        send_repeated(rebound_sock, rebound_connect, relay_session_addr)
        recv_expected(host_sock, rebound_connect, args.udp_timeout, "rebound connect reached same hostPort")

        rebound_client_game = identity_packet(
            session.token,
            client_peer_id,
            2,
            bytes([LITENET_UNRELIABLE_PROPERTY]) + b"domain-rebound-client-game",
        )
        send_repeated(rebound_sock, rebound_client_game, relay_session_addr)
        recv_expected(host_sock, rebound_client_game, args.udp_timeout, "rebound client game reached host")

        rebound_host_game = identity_packet(
            session.token,
            host_peer_id,
            3,
            bytes([LITENET_CHANNELED_PROPERTY]) + b"domain-rebound-host-game",
        )
        send_repeated(host_sock, rebound_host_game, relay_host_addr)
        recv_expected(rebound_sock, rebound_host_game, args.udp_timeout, "host game reached rebound client endpoint")
        assert_no_packet(client_sock, args.quiet_timeout, "old client endpoint did not receive rebound host game")

        stale_peer_not_found = identity_packet(
            session.token,
            host_peer_id,
            1,
            bytes([LITENET_PEER_NOT_FOUND_PROPERTY]) + b"domain-stale-rebound-peer",
        )
        send_repeated(host_sock, stale_peer_not_found, relay_host_addr)
        assert_no_packet(rebound_sock, args.quiet_timeout, "stale host PeerNotFound suppressed during rebound grace")

        print("PASS real-domain relay UDP flow completed")
    finally:
        host_sock.close()
        client_sock.close()
        rebound_sock.close()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Smoke-test a real UDP relay domain without Unity.")
    parser.add_argument("--relay-host", default="vawnwuyest.me", help="Relay DNS name or IP.")
    parser.add_argument("--relay-info-url", default=None, help="Backend URL returning relayToken/relayPort/relayHostPorts.")
    parser.add_argument("--auth-token", default=None, help="Bearer token for --relay-info-url.")
    parser.add_argument("--http-url", default=None, help="Relay HTTP control URL. Default: http://<relay-host>:7776")
    parser.add_argument("--token", default=None, help="Session token. Auto-generated when omitted.")
    parser.add_argument("--session-port", type=int, default=None, help="Existing relay session UDP port.")
    parser.add_argument("--host-port", type=int, default=None, help="Existing relay host upstream UDP port.")
    parser.add_argument("--http-timeout", type=float, default=5.0)
    parser.add_argument("--udp-timeout", type=float, default=5.0)
    parser.add_argument("--quiet-timeout", type=float, default=1.25)
    parser.add_argument("--skip-close", action="store_true", help="Do not close sessions created by this test.")
    parser.add_argument("--client-peer-id", type=int, default=42)
    parser.add_argument("--host-peer-id", type=int, default=7)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    session: Optional[RelaySessionInfo] = None
    try:
        session = resolve_session(args)
        run_flow(args, session)
        return 0
    except (OSError, urllib.error.URLError, TimeoutError) as exc:
        print(f"FAIL network error: {exc}")
        return 2
    except Exception as exc:
        print(f"FAIL relay domain flow: {exc}")
        return 1
    finally:
        if session is not None and session.created_by_test and not args.skip_close:
            http_url = args.http_url or f"http://{args.relay_host}:7776"
            close_session(http_url, session.token, args.http_timeout)


if __name__ == "__main__":
    raise SystemExit(main())
