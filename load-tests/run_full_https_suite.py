#!/usr/bin/env python3
"""
NightHunt Full HTTPS Test Suite
===============================

Orchestrates a full-scope validation protocol across:
  1. HTTPS auth + steady-state API probes
  2. WSS connect / ping / reconnect / force-logout behavior
  3. Ranked queue HTTP flow over HTTPS
  4. Existing JMeter HTTP load scenarios (optional)
  5. Existing DS capacity / fleet scenarios (optional)

This script does not replace the tuned JMeter and DS harnesses already in the
repository. It composes them into one repeatable protocol and adds the missing
WebSocket checks that were not previously covered.

Requirements:
  pip install requests websockets

Examples:
  python3 load-tests/run_full_https_suite.py \
    --api-base https://vawnwuyest.me/api \
    --username-prefix nh_stress_ \
    --password StressTest@123 \
    --start-user 1 \
    --user-count 20 \
    --ws-users 10 \
    --admin-secret nh_ds_xxx \
    --run-jmeter --run-capacity --run-fleet
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import ssl
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlencode, urlparse

try:
    import requests
    from requests.adapters import HTTPAdapter
    from urllib3.util.retry import Retry
except ImportError:
    requests = None
    HTTPAdapter = None
    Retry = None


ROOT_DIR = Path(__file__).resolve().parents[1]
JMETER_SCRIPT = ROOT_DIR / "load-tests" / "jmeter" / "run-all-scenarios.sh"
CAPACITY_SCRIPT = ROOT_DIR / "load-tests" / "ds-fleet-test" / "run_capacity_test.py"
FLEET_SCRIPT = ROOT_DIR / "load-tests" / "ds-fleet-test" / "run_fleet_test.py"
REPORT_DIR = ROOT_DIR / "load-tests" / "reports" / "full-suite"


@dataclass(slots=True)
class LoginContext:
    username: str
    password: str
    user_id: int | None
    access_token: str
    session_id: str


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def log(message: str) -> None:
    print(f"[{utc_now()}] {message}", flush=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="NightHunt full HTTPS/WSS protocol runner")
    parser.add_argument("--api-base", default="https://vawnwuyest.me/api",
                        help="HTTPS backend base URL, including /api")
    parser.add_argument("--ws-url", default="",
                        help="Override WSS endpoint. Default derives from --api-base -> wss://host/ws/game")
    parser.add_argument("--username-prefix", default="nh_stress_",
                        help="Username prefix for pre-created stress users")
    parser.add_argument("--password", default="StressTest@123",
                        help="Password for the stress users")
    parser.add_argument("--start-user", type=int, default=1,
                        help="First numeric suffix to use for username prefix")
    parser.add_argument("--user-count", type=int, default=10,
                        help="Number of users to log in for the suite")
    parser.add_argument("--ws-users", type=int, default=5,
                        help="Subset of logged-in users to validate via WSS")
    parser.add_argument("--workers", type=int, default=16,
                        help="Concurrent workers for login and API probes")
    parser.add_argument("--game-mode", default="2v2",
                        help="Matchmaking queue mode for queue probes")
    parser.add_argument("--platform", default="PC",
                        help="Platform string sent to ranked queue probes")
    parser.add_argument("--map-id", default="",
                        help="Optional mapId for ranked queue probes")
    parser.add_argument("--queue-users", type=int, default=2,
                        help="How many users to use for ranked queue HTTPS probes")
    parser.add_argument("--queue-start-user", type=int, default=0,
                        help="Optional first numeric suffix for queue probes; 0 means reuse --start-user range")
    parser.add_argument("--http-iterations", type=int, default=2,
                        help="Authenticated API probe iterations per selected user")
    parser.add_argument("--request-timeout", type=int, default=15,
                        help="HTTP request timeout in seconds")
    parser.add_argument("--report-dir", default=str(REPORT_DIR),
                        help="Directory where suite JSON reports are written")
    parser.add_argument("--admin-secret", default=os.environ.get("ADMIN_SECRET", ""),
                        help="Admin secret for DS scripts")
    parser.add_argument("--jmeter-home", default=os.environ.get("JMETER_HOME", ""),
                        help="JMETER_HOME override when --run-jmeter is enabled")
    parser.add_argument("--run-jmeter", action="store_true",
                        help="Run load-tests/jmeter/run-all-scenarios.sh")
    parser.add_argument("--run-capacity", action="store_true",
                        help="Run ds-fleet-test/run_capacity_test.py")
    parser.add_argument("--run-fleet", action="store_true",
                        help="Run ds-fleet-test/run_fleet_test.py")
    parser.add_argument("--capacity-max-ds", type=int, default=30,
                        help="--max-ds passed to run_capacity_test.py")
    parser.add_argument("--capacity-batch-size", type=int, default=5,
                        help="--batch-size passed to run_capacity_test.py")
    parser.add_argument("--fleet-ds-count", type=int, default=5,
                        help="--ds-count passed to run_fleet_test.py")
    parser.add_argument("--fleet-user-count", type=int, default=500,
                        help="--user-count passed to run_fleet_test.py")
    parser.add_argument("--skip-http", action="store_true",
                        help="Skip authenticated HTTPS API probes")
    parser.add_argument("--skip-ws", action="store_true",
                        help="Skip WSS connect / ping / force-logout probes")
    parser.add_argument("--skip-queue", action="store_true",
                        help="Skip ranked queue HTTPS probes")
    parser.add_argument("--insecure", action="store_true",
                        help="Disable TLS verification for HTTPS/WSS")
    return parser.parse_args()


def derive_ws_url(api_base: str) -> str:
    parsed = urlparse(api_base)
    scheme = "wss" if parsed.scheme == "https" else "ws"
    base_path = parsed.path.rstrip("/")
    return f"{scheme}://{parsed.netloc}{base_path}/ws/game"


def build_retry_session(insecure: bool) -> requests.Session:
    if requests is None or HTTPAdapter is None or Retry is None:
        raise RuntimeError(
            "Missing Python load-test dependencies. "
            "Install them with: pip install -r load-tests/requirements.txt"
        )
    session = requests.Session()
    retry = Retry(
        total=2,
        backoff_factor=0.2,
        status_forcelist=[429, 500, 502, 503, 504],
        allowed_methods=["GET", "POST", "DELETE"],
    )
    adapter = HTTPAdapter(max_retries=retry, pool_connections=50, pool_maxsize=50)
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    session.verify = not insecure
    return session


def request_json(session: requests.Session, method: str, url: str,
                 timeout: int, **kwargs: Any) -> tuple[int, Any, float]:
    start = time.perf_counter()
    response = session.request(method, url, timeout=timeout, **kwargs)
    elapsed_ms = (time.perf_counter() - start) * 1000
    try:
        payload = response.json()
    except ValueError:
        payload = response.text
    return response.status_code, payload, elapsed_ms


def login_one(index: int, args: argparse.Namespace) -> LoginContext:
    username = f"{args.username_prefix}{args.start_user + index}"
    return login_username(username, args)


def login_username(username: str, args: argparse.Namespace) -> LoginContext:
    session = build_retry_session(args.insecure)
    status, payload, _elapsed = request_json(
        session,
        "POST",
        f"{args.api_base}/auth/login",
        timeout=args.request_timeout,
        json={"identifier": username, "password": args.password},
        headers={"Content-Type": "application/json"},
    )
    if status != 200:
        raise RuntimeError(f"login failed for {username}: http={status} payload={payload}")
    if not isinstance(payload, dict):
        raise RuntimeError(f"login returned non-JSON payload for {username}: {payload}")
    response_data = payload.get("data") if isinstance(payload.get("data"), dict) else payload
    return LoginContext(
        username=username,
        password=args.password,
        user_id=response_data.get("userId"),
        access_token=response_data["accessToken"],
        session_id=response_data["sessionId"],
    )


def login_users(args: argparse.Namespace) -> dict[str, Any]:
    start = time.perf_counter()
    contexts: list[LoginContext] = []
    errors: list[str] = []
    with ThreadPoolExecutor(max_workers=min(args.workers, args.user_count)) as pool:
        future_map = {pool.submit(login_one, idx, args): idx for idx in range(args.user_count)}
        for future in as_completed(future_map):
            try:
                contexts.append(future.result())
            except Exception as exc:
                errors.append(str(exc))
    contexts.sort(key=lambda ctx: ctx.username)
    elapsed_ms = (time.perf_counter() - start) * 1000
    return {
        "ok": len(contexts),
        "errors": errors,
        "elapsedMs": round(elapsed_ms, 2),
        "users": contexts,
    }


def auth_headers(ctx: LoginContext) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {ctx.access_token}",
        "X-Session-Id": ctx.session_id,
        "Content-Type": "application/json",
    }


def issue_realtime_ticket(ctx: LoginContext, args: argparse.Namespace) -> str:
    with build_retry_session(args.insecure) as session:
        status, payload, _elapsed = request_json(
            session,
            "POST",
            f"{args.api_base}/realtime/tickets",
            timeout=args.request_timeout,
            headers=auth_headers(ctx),
        )
    if status != 200:
        raise RuntimeError(f"realtime ticket failed for {ctx.username}: http={status} payload={payload}")
    if not isinstance(payload, dict):
        raise RuntimeError(f"realtime ticket returned non-JSON payload for {ctx.username}: {payload}")
    response_data = payload.get("data") if isinstance(payload.get("data"), dict) else payload
    ticket = response_data.get("ticket")
    if not ticket:
        raise RuntimeError(f"realtime ticket missing for {ctx.username}: payload={payload}")
    return str(ticket)


def run_authenticated_http_probe(ctx: LoginContext, args: argparse.Namespace) -> dict[str, Any]:
    session = build_retry_session(args.insecure)
    endpoints = [
        ("GET", "/auth/check-session", None),
        ("GET", "/profile", None),
        ("GET", "/friends", None),
        ("GET", "/game-modes/available", None),
        ("GET", "/match/history", None),
    ]
    results: list[dict[str, Any]] = []
    for _ in range(args.http_iterations):
        for method, path, body in endpoints:
            status, payload, elapsed_ms = request_json(
                session,
                method,
                f"{args.api_base}{path}",
                timeout=args.request_timeout,
                headers=auth_headers(ctx),
                json=body,
            )
            results.append({
                "endpoint": path,
                "status": status,
                "ok": status < 400,
                "elapsedMs": round(elapsed_ms, 2),
                "error": payload if status >= 400 else None,
            })
    return {
        "username": ctx.username,
        "results": results,
        "errorCount": sum(1 for item in results if not item["ok"]),
    }


def run_http_phase(contexts: list[LoginContext], args: argparse.Namespace) -> dict[str, Any]:
    start = time.perf_counter()
    selected = contexts[:min(len(contexts), args.ws_users or len(contexts))]
    probes: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=min(args.workers, max(len(selected), 1))) as pool:
        future_map = {pool.submit(run_authenticated_http_probe, ctx, args): ctx.username for ctx in selected}
        for future in as_completed(future_map):
            probes.append(future.result())
    probes.sort(key=lambda item: item["username"])
    total_requests = sum(len(item["results"]) for item in probes)
    total_errors = sum(item["errorCount"] for item in probes)
    elapsed_ms = (time.perf_counter() - start) * 1000
    return {
        "users": len(selected),
        "totalRequests": total_requests,
        "totalErrors": total_errors,
        "errorPct": round((total_errors / total_requests * 100) if total_requests else 0.0, 4),
        "elapsedMs": round(elapsed_ms, 2),
        "probes": probes,
    }


async def open_ws_connection(ctx: LoginContext, args: argparse.Namespace) -> dict[str, Any]:
    try:
        import websockets
    except ImportError as exc:
        raise RuntimeError("Missing dependency: pip install websockets") from exc

    ssl_context = None
    if args.ws_url.startswith("wss://"):
        ssl_context = ssl.create_default_context()
        if args.insecure:
            ssl_context.check_hostname = False
            ssl_context.verify_mode = ssl.CERT_NONE

    ticket = issue_realtime_ticket(ctx, args)
    uri = f"{args.ws_url}?{urlencode({'ticket': ticket})}"
    start = time.perf_counter()
    async with websockets.connect(uri, ssl=ssl_context, open_timeout=args.request_timeout) as ws:
        first_message = await asyncio.wait_for(ws.recv(), timeout=args.request_timeout)
        await ws.send(json.dumps({"type": "ping"}))
        pong_message = await asyncio.wait_for(ws.recv(), timeout=args.request_timeout)
        elapsed_ms = (time.perf_counter() - start) * 1000
    replay_rejected = False
    try:
        async with websockets.connect(uri, ssl=ssl_context, open_timeout=args.request_timeout):
            pass
    except Exception:
        replay_rejected = True
    return {
        "username": ctx.username,
        "connected": "\"type\":\"connected\"" in first_message or '"type":"connected"' in first_message,
        "pong": "\"type\":\"pong\"" in pong_message or '"type":"pong"' in pong_message,
        "ticketReplayRejected": replay_rejected,
        "firstMessage": first_message,
        "pongMessage": pong_message,
        "elapsedMs": round(elapsed_ms, 2),
    }


async def force_logout_probe(ctx: LoginContext, args: argparse.Namespace) -> dict[str, Any]:
    try:
        import websockets
    except ImportError as exc:
        raise RuntimeError("Missing dependency: pip install websockets") from exc

    ssl_context = None
    if args.ws_url.startswith("wss://"):
        ssl_context = ssl.create_default_context()
        if args.insecure:
            ssl_context.check_hostname = False
            ssl_context.verify_mode = ssl.CERT_NONE

    ticket = issue_realtime_ticket(ctx, args)
    uri = f"{args.ws_url}?{urlencode({'ticket': ticket})}"
    session = build_retry_session(args.insecure)
    async with websockets.connect(uri, ssl=ssl_context, open_timeout=args.request_timeout) as ws:
        await asyncio.wait_for(ws.recv(), timeout=args.request_timeout)
        status, payload, _elapsed = request_json(
            session,
            "POST",
            f"{args.api_base}/auth/login",
            timeout=args.request_timeout,
            json={"identifier": ctx.username, "password": ctx.password},
            headers={"Content-Type": "application/json"},
        )
        if status != 200:
            return {
                "username": ctx.username,
                "ok": False,
                "reason": f"second login failed: http={status} payload={payload}",
            }
        try:
            event = await asyncio.wait_for(ws.recv(), timeout=args.request_timeout)
        except Exception as exc:
            return {
                "username": ctx.username,
                "ok": False,
                "reason": f"no force_logout event observed: {exc}",
            }
        return {
            "username": ctx.username,
            "ok": "force_logout" in event,
            "event": event,
        }


async def run_ws_connect_probes(contexts: list[LoginContext], args: argparse.Namespace) -> list[dict[str, Any]]:
    return await asyncio.gather(*(open_ws_connection(ctx, args) for ctx in contexts))


def run_ws_phase(contexts: list[LoginContext], args: argparse.Namespace) -> dict[str, Any]:
    selected = contexts[:min(args.ws_users, len(contexts))]
    connect_results = asyncio.run(run_ws_connect_probes(selected, args))
    force_logout_result = None
    if selected:
        force_logout_result = asyncio.run(force_logout_probe(selected[0], args))
    failures = [
        item for item in connect_results
        if not (item["connected"] and item["pong"] and item["ticketReplayRejected"])
    ]
    return {
        "users": len(selected),
        "connectProbe": connect_results,
        "forceLogoutProbe": force_logout_result,
        "errorCount": len(failures) + (0 if not force_logout_result or force_logout_result.get("ok") else 1),
    }


def queue_probe_one(ctx: LoginContext, args: argparse.Namespace) -> dict[str, Any]:
    session = build_retry_session(args.insecure)
    body: dict[str, str] = {"gameMode": args.game_mode, "platform": args.platform}
    if args.map_id:
        body["mapId"] = args.map_id
    status_enter, payload_enter, enter_ms = request_json(
        session,
        "POST",
        f"{args.api_base}/matchmaking/queue",
        timeout=args.request_timeout,
        headers=auth_headers(ctx),
        json=body,
    )
    status_status, payload_status, status_ms = request_json(
        session,
        "GET",
        f"{args.api_base}/matchmaking/queue/status",
        timeout=args.request_timeout,
        headers=auth_headers(ctx),
    )
    status_leave, payload_leave, leave_ms = request_json(
        session,
        "DELETE",
        f"{args.api_base}/matchmaking/queue",
        timeout=args.request_timeout,
        headers=auth_headers(ctx),
    )
    enter_error_code = payload_enter.get("errorCode") if isinstance(payload_enter, dict) else None
    precondition_blocked = enter_error_code in {"PARTY_002", "ROOM_014"}
    return {
        "username": ctx.username,
        "enter": {"status": status_enter, "elapsedMs": round(enter_ms, 2), "payload": payload_enter},
        "status": {"status": status_status, "elapsedMs": round(status_ms, 2), "payload": payload_status},
        "leave": {"status": status_leave, "elapsedMs": round(leave_ms, 2), "payload": payload_leave},
        "preconditionBlocked": precondition_blocked,
        "ok": (status_enter < 400 and status_status < 400 and status_leave < 400) or precondition_blocked,
    }


def run_queue_phase(contexts: list[LoginContext], args: argparse.Namespace) -> dict[str, Any]:
    if args.queue_start_user > 0:
        selected = [
            login_username(f"{args.username_prefix}{args.queue_start_user + offset}", args)
            for offset in range(args.queue_users)
        ]
    else:
        selected = contexts[:min(args.queue_users, len(contexts))]
    probes = [queue_probe_one(ctx, args) for ctx in selected]
    return {
        "users": len(selected),
        "gameMode": args.game_mode,
        "queueStartUser": args.queue_start_user or args.start_user,
        "errorCount": sum(1 for item in probes if not item["ok"] and not item["preconditionBlocked"]),
        "preconditionBlockedCount": sum(1 for item in probes if item["preconditionBlocked"]),
        "probes": probes,
    }


def run_subprocess(command: list[str], cwd: Path, env: dict[str, str]) -> dict[str, Any]:
    start = time.perf_counter()
    result = subprocess.run(command, cwd=str(cwd), env=env, capture_output=True, text=True)
    elapsed_ms = (time.perf_counter() - start) * 1000
    return {
        "command": command,
        "exitCode": result.returncode,
        "elapsedMs": round(elapsed_ms, 2),
        "stdoutTail": result.stdout[-4000:],
        "stderrTail": result.stderr[-4000:],
    }


def docker_image_exists(image_name: str) -> bool:
    result = subprocess.run(
        ["docker", "image", "inspect", image_name],
        capture_output=True,
        text=True,
    )
    return result.returncode == 0


def run_jmeter_phase(args: argparse.Namespace) -> dict[str, Any]:
    env = os.environ.copy()
    if args.jmeter_home:
        env["JMETER_HOME"] = args.jmeter_home
    env["NH_USERNAME"] = f"{args.username_prefix}{args.start_user}"
    env["NH_PASSWORD"] = args.password
    if args.admin_secret:
        env["ADMIN_SECRET"] = args.admin_secret
    return run_subprocess(["bash", str(JMETER_SCRIPT)], ROOT_DIR, env)


def run_capacity_phase(args: argparse.Namespace) -> dict[str, Any]:
    env = os.environ.copy()
    command = [
        sys.executable,
        str(CAPACITY_SCRIPT),
        "--backend", args.api_base,
        "--admin-secret", args.admin_secret,
        "--max-ds", str(args.capacity_max_ds),
        "--batch-size", str(args.capacity_batch_size),
    ]
    return run_subprocess(command, ROOT_DIR, env)


def run_fleet_phase(args: argparse.Namespace) -> dict[str, Any]:
    env = os.environ.copy()
    command = [
        sys.executable,
        str(FLEET_SCRIPT),
        "--backend", args.api_base,
        "--admin-secret", args.admin_secret,
        "--ds-count", str(args.fleet_ds_count),
        "--user-count", str(args.fleet_user_count),
    ]
    if not docker_image_exists("nighthunt-mock-ds:latest"):
        command.append("--build")
    return run_subprocess(command, ROOT_DIR, env)


def summarize(report: dict[str, Any]) -> dict[str, Any]:
    login_phase = report.get("login", {})
    http_phase = report.get("http", {})
    ws_phase = report.get("ws", {})
    queue_phase = report.get("queue", {})
    return {
        "timestamp": report["timestamp"],
        "apiBase": report["config"]["apiBase"],
        "wsUrl": report["config"]["wsUrl"],
        "loginOk": login_phase.get("ok", 0),
        "loginErrors": len(login_phase.get("errors", [])),
        "httpErrorPct": http_phase.get("errorPct"),
        "wsErrors": ws_phase.get("errorCount"),
        "queueErrors": queue_phase.get("errorCount"),
        "jmeterExitCode": (report.get("jmeter") or {}).get("exitCode"),
        "capacityExitCode": (report.get("capacity") or {}).get("exitCode"),
        "fleetExitCode": (report.get("fleet") or {}).get("exitCode"),
    }


def main() -> int:
    args = parse_args()
    args.ws_url = args.ws_url or derive_ws_url(args.api_base)
    report_dir = Path(args.report_dir)
    report_dir.mkdir(parents=True, exist_ok=True)

    report: dict[str, Any] = {
        "timestamp": utc_now(),
        "config": {
            "apiBase": args.api_base,
            "wsUrl": args.ws_url,
            "userCount": args.user_count,
            "wsUsers": args.ws_users,
            "queueUsers": args.queue_users,
            "gameMode": args.game_mode,
            "insecure": args.insecure,
        },
    }

    log(f"Logging in {args.user_count} users via HTTPS: {args.api_base}")
    login_phase = login_users(args)
    contexts = login_phase.pop("users")
    report["login"] = login_phase
    if not contexts:
        report["summary"] = summarize(report)
        output = report_dir / f"full-suite-{datetime.now().strftime('%Y%m%d-%H%M%S')}.json"
        output.write_text(json.dumps(report, indent=2), encoding="utf-8")
        log(f"No users logged in successfully. Report: {output}")
        return 1

    if not args.skip_http:
        log("Running authenticated HTTPS API probes")
        report["http"] = run_http_phase(contexts, args)

    if not args.skip_queue:
        log("Running ranked queue HTTPS probes")
        report["queue"] = run_queue_phase(contexts, args)

    if not args.skip_ws:
        log(f"Running WSS probes against {args.ws_url}")
        report["ws"] = run_ws_phase(contexts, args)

    if args.run_jmeter:
        log("Running existing JMeter HTTPS scenarios")
        report["jmeter"] = run_jmeter_phase(args)

    if args.run_capacity:
        if not args.admin_secret:
            report["capacity"] = {"skipped": True, "reason": "--admin-secret is required"}
        else:
            log("Running DS capacity script")
            report["capacity"] = run_capacity_phase(args)

    if args.run_fleet:
        if not args.admin_secret:
            report["fleet"] = {"skipped": True, "reason": "--admin-secret is required"}
        else:
            log("Running DS fleet script")
            report["fleet"] = run_fleet_phase(args)

    report["summary"] = summarize(report)
    output = report_dir / f"full-suite-{datetime.now().strftime('%Y%m%d-%H%M%S')}.json"
    output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    log(f"Full suite report written to {output}")

    summary = report["summary"]
    hard_fail = (
        summary.get("loginErrors", 0) > 0
        or (summary.get("wsErrors") or 0) > 0
        or (summary.get("queueErrors") or 0) > 0
        or ((summary.get("jmeterExitCode") not in (None, 0)))
        or ((summary.get("capacityExitCode") not in (None, 0)))
        or ((summary.get("fleetExitCode") not in (None, 0)))
    )
    return 1 if hard_fail else 0


if __name__ == "__main__":
    raise SystemExit(main())
