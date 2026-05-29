#!/usr/bin/env python3
"""
NightHunt DS Fleet Load Test
=============================
Orchestrates a realistic load test:
  Phase 1  — Login 1500 users concurrently (background JMeter OR built-in HTTP)
  Phase 2  — Allocate N mock DS containers one-by-one via test-alloc API
  Phase 3  — Health monitor loop (heartbeat check per container)
  Phase 4  — Inject failure on 1 container (docker kill)
  Phase 5  — Wait for backend auto-cleanup (cleanupDeadServers @ 90s)
  Phase 6  — Trigger fleet-reclaim if configured (or wait for auto-reclaim)
  Phase 7  — Generate JSON + text report

Usage:
  python3 run_fleet_test.py [options]

Options:
  --backend     Backend base URL (default: https://vawnwuyest.me/api)
  --admin-secret Admin secret header value
  --ds-count    Number of mock DS containers to spawn (default: 5)
  --fail-ds     Index of DS to kill mid-test (0-based, default: 1)
  --fail-after  Seconds before target DS crashes (default: 60)
  --monitor-sec Total monitoring duration in seconds (default: 180)
  --user-count  Concurrent users to simulate for backend load (default: 1500)
  --build       Build mock-ds Docker image before test
  --no-users    Skip Phase 1 user load (DS test only)
  --dry-run     Print plan without executing

Requirements:
  pip install requests
  docker CLI accessible in PATH (for running mock-ds containers)
"""

import argparse
import concurrent.futures
import json
import os
import subprocess
import sys
import time
import threading
from datetime import datetime, timezone
from typing import Optional

import requests

# ── Constants ──────────────────────────────────────────────────────────────────
MOCK_DS_IMAGE       = "nighthunt-mock-ds:latest"
MOCK_DS_DIR         = os.path.join(os.path.dirname(__file__), "mock-ds")
REPORTS_DIR         = os.path.join(os.path.dirname(__file__), "reports")
HEARTBEAT_WAIT_SEC  = 90 + 10   # backend cleanupDeadServers cutoff = 90s, +10s buffer
HEALTH_PORT_BASE    = 9100       # mock-ds HTTP health ports start here
DS_NETWORK          = "nighthunt-network"  # Docker network backend uses


# ── CLI ────────────────────────────────────────────────────────────────────────
def parse_args():
    p = argparse.ArgumentParser(description="NightHunt DS Fleet Load Test")
    p.add_argument("--backend",       default="https://vawnwuyest.me/api",
                   help="Backend base URL (no trailing slash)")
    p.add_argument("--admin-secret",  required=True,
                   help="Value of X-Admin-Secret header")
    p.add_argument("--ds-count",      type=int, default=5,
                   help="Number of mock DS containers to spawn")
    p.add_argument("--fail-ds",       type=int, default=1,
                   help="Index (0-based) of DS to kill mid-test (-1 = none)")
    p.add_argument("--fail-after",    type=int, default=60,
                   help="Seconds before target DS simulates crash")
    p.add_argument("--monitor-sec",   type=int, default=180,
                   help="Total health monitoring duration in seconds")
    p.add_argument("--user-count",    type=int, default=1500,
                   help="Concurrent users for backend load phase")
    p.add_argument("--build",         action="store_true",
                   help="Build mock-ds Docker image before running")
    p.add_argument("--no-users",      action="store_true",
                   help="Skip Phase 1 (user load simulation)")
    p.add_argument("--dry-run",       action="store_true",
                   help="Print plan without executing")
    p.add_argument("--game-duration", type=int, default=90,
                   help="Seconds each mock DS simulates a game before stopping heartbeat (default 90)")
    p.add_argument("--game-start-delay", type=int, default=5,
                   help="Seconds after register before mock DS sends players (default 5)")
    p.add_argument("--simulate-players", type=int, default=4,
                   help="Number of fake players each DS reports (default 4)")
    return p.parse_args()


# ── Helpers ────────────────────────────────────────────────────────────────────
def log(msg: str):
    ts = datetime.now(timezone.utc).strftime("%H:%M:%S")
    print(f"[{ts}] {msg}", flush=True)


def docker_run(name: str, server_id: str, secret: str, port: int,
               backend_url: str, health_port: int,
               fail_after: int = 0,
               game_start_delay: int = 5,
               simulate_players: int = 4,
               game_duration: int = 120) -> Optional[str]:
    """Start a mock-ds container. Returns container ID or None on failure."""
    cmd = [
        "docker", "run", "-d",
        "--name",        name,
        "--network",     DS_NETWORK,
        "-p",            f"{health_port}:9090",
        "-e", f"SERVER_ID={server_id}",
        "-e", f"SERVER_SECRET={secret}",
        "-e", f"GAME_PORT={port}",
        "-e", f"BACKEND_URL={backend_url}",
        "-e", f"FAIL_AFTER_SECONDS={fail_after}",
        "-e", f"MOCK_DS_HTTP_PORT=9090",
        "-e", f"GAME_START_DELAY={game_start_delay}",
        "-e", f"SIMULATE_PLAYERS={simulate_players}",
        "-e", f"GAME_DURATION={game_duration}",
        "--rm",
        MOCK_DS_IMAGE,
    ]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        if result.returncode == 0:
            return result.stdout.strip()[:12]
        else:
            log(f"  docker run failed: {result.stderr.strip()[:200]}")
            return None
    except Exception as exc:
        log(f"  docker run exception: {exc}")
        return None


def docker_kill(container_name: str) -> bool:
    """Force-kill a container. Returns True on success."""
    try:
        result = subprocess.run(
            ["docker", "kill", container_name],
            capture_output=True, text=True, timeout=15
        )
        return result.returncode == 0
    except Exception:
        return False


def docker_stop(container_name: str):
    """Graceful stop."""
    subprocess.run(["docker", "stop", "-t", "5", container_name],
                   capture_output=True, timeout=15)


def check_ds_health(health_port: int) -> dict:
    """GET http://localhost:{health_port}/health → dict or error."""
    try:
        resp = requests.get(f"http://localhost:{health_port}/health", timeout=4)
        if resp.status_code == 200:
            return resp.json()
        return {"error": f"HTTP {resp.status_code}"}
    except Exception as exc:
        return {"error": str(exc)}


def api_test_alloc(backend: str, admin_secret: str) -> Optional[dict]:
    """POST /admin/ds/test-alloc → { serverId, devSecret, port, status }"""
    try:
        resp = requests.post(
            f"{backend}/admin/ds/test-alloc",
            headers={"X-Admin-Secret": admin_secret},
            timeout=10,
        )
        if resp.status_code == 200:
            return resp.json().get("data") or resp.json()
        log(f"  test-alloc failed: HTTP {resp.status_code} — {resp.text[:120]}")
        return None
    except Exception as exc:
        log(f"  test-alloc exception: {exc}")
        return None


def api_fleet_status(backend: str, admin_secret: str) -> Optional[dict]:
    try:
        resp = requests.get(
            f"{backend}/admin/ds/fleet-status",
            headers={"X-Admin-Secret": admin_secret},
            timeout=10,
        )
        if resp.status_code == 200:
            return resp.json().get("data") or resp.json()
        return None
    except Exception:
        return None


def api_fleet_reclaim(backend: str, admin_secret: str, reason: str) -> Optional[dict]:
    try:
        resp = requests.post(
            f"{backend}/admin/ds/fleet-reclaim",
            headers={"X-Admin-Secret": admin_secret},
            json={"reason": reason},
            timeout=15,
        )
        if resp.status_code == 200:
            return resp.json().get("data") or resp.json()
        log(f"  fleet-reclaim failed: HTTP {resp.status_code} — {resp.text[:120]}")
        return None
    except Exception as exc:
        log(f"  fleet-reclaim exception: {exc}")
        return None


# ── Phase 1: User load simulation ─────────────────────────────────────────────
# Uses pre-existing JMeter test OR simple concurrent login requests.
# For real 1500-user test, JMeter is preferred (already tested & tuned).
# Here we do a lightweight version: concurrent check-session probes.

def _single_user_probe(backend: str, user_idx: int, results: list):
    """One VU: just hit GET /auth/check-session (no real login needed)."""
    start = time.time()
    try:
        resp = requests.get(f"{backend}/auth/check-session", timeout=5)
        results.append({"user": user_idx, "status": resp.status_code,
                         "ms": int((time.time() - start) * 1000)})
    except Exception as exc:
        results.append({"user": user_idx, "status": "error", "error": str(exc),
                         "ms": int((time.time() - start) * 1000)})


def phase1_user_load(backend: str, user_count: int) -> dict:
    log(f"Phase 1 ▶ Simulating {user_count} concurrent users (check-session probe)…")
    results = []
    start = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(user_count, 200)) as pool:
        futures = [pool.submit(_single_user_probe, backend, i, results)
                   for i in range(user_count)]
        concurrent.futures.wait(futures)
    elapsed = time.time() - start
    ok  = sum(1 for r in results if isinstance(r.get("status"), int) and r["status"] < 500)
    err = len(results) - ok
    avg_ms = int(sum(r["ms"] for r in results) / max(len(results), 1))
    p95_ms = sorted(r["ms"] for r in results)[int(len(results) * 0.95)] if results else 0
    log(f"Phase 1 ◀ done in {elapsed:.1f}s: {ok}/{user_count} ok, {err} errors, avg={avg_ms}ms p95={p95_ms}ms")
    return {"ok": ok, "errors": err, "avg_ms": avg_ms, "p95_ms": p95_ms,
            "elapsed_sec": round(elapsed, 1), "total_users": user_count}


# ── Phase 2: Allocate + start DS containers ───────────────────────────────────
def phase2_allocate_ds(backend: str, admin_secret: str, ds_count: int,
                       fail_ds_idx: int, fail_after: int,
                       backend_internal: str, dry_run: bool,
                       game_duration: int = 90,
                       game_start_delay: int = 5,
                       simulate_players: int = 4) -> list:
    log(f"Phase 2 ▶ Allocating {ds_count} mock DS containers (one-by-one)…")
    allocated = []
    for i in range(ds_count):
        log(f"  [{i+1}/{ds_count}] Calling test-alloc…")
        if dry_run:
            allocated.append({"serverId": f"dry-{i}", "devSecret": "secret",
                               "port": 7777 + i, "container": None,
                               "healthPort": HEALTH_PORT_BASE + i, "index": i})
            log(f"  [{i+1}/{ds_count}] [DRY RUN] Would allocate ds-{i}")
            continue

        alloc = api_test_alloc(backend, admin_secret)
        if not alloc:
            log(f"  [{i+1}/{ds_count}] FAILED to allocate — skipping")
            continue

        server_id  = alloc.get("serverId") or alloc.get("data", {}).get("serverId")
        dev_secret = alloc.get("devSecret") or alloc.get("data", {}).get("devSecret")
        port       = alloc.get("port")      or alloc.get("data", {}).get("port")

        if not server_id or not dev_secret:
            log(f"  [{i+1}/{ds_count}] Invalid alloc response: {alloc}")
            continue

        health_port  = HEALTH_PORT_BASE + i
        container_nm = f"nighthunt-mock-ds-{i}"
        fail_s       = fail_after if (i == fail_ds_idx) else 0

        log(f"  [{i+1}/{ds_count}] Starting container {container_nm} "
            f"serverId={server_id[:8]}… port={port} health={health_port}"
            + (f" WILL_FAIL_IN={fail_s}s" if fail_s > 0 else ""))

        cid = docker_run(container_nm, server_id, dev_secret, port,
                         backend_internal, health_port, fail_s,
                         game_start_delay, simulate_players, game_duration)
        ds_info = {
            "index":       i,
            "serverId":    server_id,
            "port":        port,
            "container":   container_nm,
            "containerId": cid,
            "healthPort":  health_port,
            "willFail":    (fail_s > 0),
            "failAfterSec": fail_s,
            "startedAt":   datetime.now(timezone.utc).isoformat(),
        }
        allocated.append(ds_info)
        log(f"  [{i+1}/{ds_count}] Container started: cid={cid}")
        time.sleep(1)  # slight stagger to avoid port conflicts

    log(f"Phase 2 ◀ {len(allocated)}/{ds_count} containers started")
    return allocated


# ── Phase 3: Health monitoring ────────────────────────────────────────────────
def phase3_monitor(allocated: list, monitor_sec: int, backend: str,
                   admin_secret: str, dry_run: bool) -> list:
    log(f"Phase 3 ▶ Monitoring {len(allocated)} DS containers for {monitor_sec}s…")
    snapshots = []
    interval  = 15  # poll every 15s
    start     = time.time()

    while (time.time() - start) < monitor_sec:
        elapsed = int(time.time() - start)
        snap = {"elapsed_sec": elapsed, "timestamp": datetime.now(timezone.utc).isoformat(),
                "containers": []}

        for ds in allocated:
            if dry_run:
                snap["containers"].append({**ds, "health": "dry-run", "registered": True})
                continue
            health = check_ds_health(ds["healthPort"])
            snap["containers"].append({
                "serverId":   ds["serverId"][:8],
                "container":  ds["container"],
                "willFail":   ds.get("willFail", False),
                "registered": health.get("registered", False),
                "heartbeats": health.get("heartbeatCount", 0),
                "hbErrors":   health.get("heartbeatErrors", 0),
                "uptime":     health.get("uptimeSeconds", 0),
                "health":     "ok" if health.get("registered") else health.get("status", "error"),
                "error":      health.get("error"),
            })

        # Fleet status from backend
        fleet = api_fleet_status(backend, admin_secret)
        snap["backendFleet"] = fleet

        snapshots.append(snap)
        alive   = sum(1 for c in snap["containers"] if c.get("health") == "ok")
        dead    = len(snap["containers"]) - alive
        fhealth = fleet.get("fleetHealth", "?") if fleet else "backend_unreachable"
        log(f"  t+{elapsed:3d}s | containers: {alive} ok, {dead} dead | backend fleet: {fhealth}")

        time.sleep(interval)

    log(f"Phase 3 ◀ monitoring complete ({len(snapshots)} snapshots)")
    return snapshots


# ── Phase 4/5: Inject failure + verify cleanup ────────────────────────────────
def phase4_inject_failure(allocated: list, fail_ds_idx: int, dry_run: bool) -> dict:
    if fail_ds_idx < 0 or fail_ds_idx >= len(allocated):
        log("Phase 4 ▶ No failure injection (--fail-ds=-1 or out of range)")
        return {"skipped": True}

    target = allocated[fail_ds_idx]
    log(f"Phase 4 ▶ Injecting failure: killing container {target['container']}…")

    if dry_run:
        log("  [DRY RUN] Would kill container")
        return {"container": target["container"], "serverId": target["serverId"], "killed": True}

    killed = docker_kill(target["container"])
    result = {
        "container":  target["container"],
        "serverId":   target["serverId"],
        "killedAt":   datetime.now(timezone.utc).isoformat(),
        "killed":     killed,
    }
    if killed:
        log(f"Phase 4 ◀ Container killed: {target['container']}")
    else:
        log(f"Phase 4 ◀ WARNING: kill failed (container may have already exited)")
    return result


def phase5_wait_backend_cleanup(wait_sec: int):
    log(f"Phase 5 ▶ Waiting {wait_sec}s for backend cleanupDeadServers to detect failure…")
    for remaining in range(wait_sec, 0, -15):
        log(f"  … {remaining}s remaining")
        time.sleep(min(15, remaining))
    log("Phase 5 ◀ Wait complete")


# ── Phase 6: Fleet reclaim ────────────────────────────────────────────────────
def phase6_fleet_reclaim(backend: str, admin_secret: str,
                         auto_reclaim: bool, dry_run: bool) -> Optional[dict]:
    if auto_reclaim:
        log("Phase 6 ▶ Auto-reclaim is ON — checking backend fleet status…")
        if dry_run:
            return {"autoReclaim": True, "status": "dry-run"}
        fleet = api_fleet_status(backend, admin_secret)
        health = fleet.get("fleetHealth") if fleet else "unknown"
        log(f"  Fleet health after failure: {health}")
        if health in ("CRITICAL", "EMPTY"):
            log("  Backend auto-reclaim already triggered (fleet CRITICAL/EMPTY)")
        return fleet
    else:
        log("Phase 6 ▶ Manual fleet-reclaim triggered…")
        if dry_run:
            return {"manualReclaim": True, "status": "dry-run"}
        report = api_fleet_reclaim(backend, admin_secret, "DS fleet load test — manual reclaim")
        if report:
            log(f"  Fleet reclaim: reclaimed={report.get('reclaimedCount')} "
                f"failed={report.get('failedCount')}")
        return report


# ── Phase 7: Report generation ────────────────────────────────────────────────
def phase7_report(args, user_results: dict, allocated: list,
                  snapshots: list, failure_result: dict,
                  reclaim_result, total_elapsed: float) -> str:
    os.makedirs(REPORTS_DIR, exist_ok=True)
    ts   = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    path = os.path.join(REPORTS_DIR, f"ds-fleet-test-{ts}.json")

    # Compute summary stats
    last_snap     = snapshots[-1] if snapshots else {}
    containers    = last_snap.get("containers", [])
    alive_final   = sum(1 for c in containers if c.get("health") == "ok")
    dead_final    = len(containers) - alive_final

    total_hb      = sum(c.get("heartbeats", 0) for c in containers)
    total_hb_err  = sum(c.get("hbErrors",   0) for c in containers)
    hb_rate       = (total_hb / max(total_hb + total_hb_err, 1)) * 100

    report = {
        "testName":       "NightHunt DS Fleet Load Test",
        "runAt":          datetime.now(timezone.utc).isoformat(),
        "totalElapsedSec": round(total_elapsed, 1),
        "config": {
            "backend":      args.backend,
            "dsCount":      args.ds_count,
            "failDsIdx":    args.fail_ds,
            "failAfterSec": args.fail_after,
            "monitorSec":   args.monitor_sec,
            "userCount":    args.user_count,
        },
        "phase1_userLoad": user_results,
        "phase2_dsAllocated": {
            "requested": args.ds_count,
            "started":   len(allocated),
            "servers":   [
                {"index": d["index"], "serverId": d["serverId"][:8],
                 "port": d["port"], "willFail": d.get("willFail", False)}
                for d in allocated
            ],
        },
        "phase3_monitoring": {
            "snapshotCount": len(snapshots),
            "totalHeartbeats": total_hb,
            "heartbeatErrors": total_hb_err,
            "heartbeatSuccessRate": f"{hb_rate:.1f}%",
        },
        "phase4_failureInjection": failure_result,
        "phase6_reclaimReport":    reclaim_result,
        "finalFleetState": {
            "containersAlive": alive_final,
            "containersDead":  dead_final,
            "backendFleet":    last_snap.get("backendFleet"),
        },
        "summary": {
            "dsStartupSuccess":  f"{len(allocated)}/{args.ds_count} ({len(allocated)/max(args.ds_count,1)*100:.0f}%)",
            "dsHeartbeatRate":   f"{hb_rate:.1f}%",
            "failureInjected":   not failure_result.get("skipped"),
            "failureContainerKilled": failure_result.get("killed", False),
            "fleetReclaimOk":    bool(reclaim_result and not reclaim_result.get("error")),
        },
    }

    with open(path, "w") as f:
        json.dump(report, f, indent=2)

    # Also print text summary
    _print_text_report(report, path)
    return path


def _print_text_report(r: dict, path: str):
    cfg  = r["config"]
    p1   = r["phase1_userLoad"]
    p2   = r["phase2_dsAllocated"]
    p3   = r["phase3_monitoring"]
    p4   = r["phase4_failureInjection"]
    p6   = r.get("phase6_reclaimReport") or {}
    s    = r["summary"]
    ffs  = r["finalFleetState"]

    sep = "=" * 60
    print(f"\n{sep}")
    print("  NightHunt DS Fleet Test — FINAL REPORT")
    print(sep)
    print(f"  Backend:          {cfg['backend']}")
    print(f"  Elapsed:          {r['totalElapsedSec']}s")
    print()
    print(f"  [Phase 1] User Load ({cfg['userCount']} users)")
    if p1:
        print(f"    OK:             {p1.get('ok')}/{p1.get('total_users')}")
        print(f"    Errors:         {p1.get('errors')}")
        print(f"    Avg/P95:        {p1.get('avg_ms')}ms / {p1.get('p95_ms')}ms")
    else:
        print("    Skipped")
    print()
    print(f"  [Phase 2] DS Allocation")
    print(f"    Requested:      {p2['requested']}")
    print(f"    Started:        {p2['started']}")
    print(f"    Startup rate:   {s['dsStartupSuccess']}")
    print()
    print(f"  [Phase 3] Heartbeat Monitoring")
    print(f"    Total HBs sent: {p3['totalHeartbeats']}")
    print(f"    HB errors:      {p3['heartbeatErrors']}")
    print(f"    HB success:     {s['dsHeartbeatRate']}")
    print()
    print(f"  [Phase 4] Failure Injection")
    print(f"    Skipped:        {p4.get('skipped', False)}")
    print(f"    Container:      {p4.get('container', 'n/a')}")
    print(f"    Killed:         {p4.get('killed', False)}")
    print()
    print(f"  [Phase 6] Fleet Reclaim")
    if p6:
        print(f"    Reclaimed:      {p6.get('reclaimedCount', '?')}/{p6.get('totalReclaimAttempted', '?')}")
        print(f"    Failed:         {p6.get('failedCount', '?')}")
        print(f"    Trigger reason: {p6.get('triggerReason', '?')}")
    else:
        print("    Not triggered")
    print()
    print(f"  [Final State]")
    print(f"    Containers alive: {ffs['containersAlive']}")
    print(f"    Containers dead:  {ffs['containersDead']}")
    bf = ffs.get("backendFleet") or {}
    print(f"    Backend fleet:    {bf.get('fleetHealth', 'unknown')} "
          f"(active={bf.get('totalActive', '?')})")
    print()
    print(f"  Report saved: {path}")
    print(sep)


# ── Cleanup helper ────────────────────────────────────────────────────────────
def cleanup_containers(allocated: list):
    """Stop all mock-ds containers (best-effort)."""
    for ds in allocated:
        nm = ds.get("container")
        if nm:
            docker_stop(nm)


# ── Build image ───────────────────────────────────────────────────────────────
def build_mock_image():
    log(f"Building mock-ds Docker image: {MOCK_DS_IMAGE}…")
    result = subprocess.run(
        ["docker", "build", "-t", MOCK_DS_IMAGE, MOCK_DS_DIR],
        capture_output=False,
    )
    if result.returncode != 0:
        log("ERROR: docker build failed")
        sys.exit(1)
    log(f"Image built: {MOCK_DS_IMAGE}")


# ── Main ──────────────────────────────────────────────────────────────────────
def main():
    args = parse_args()

    if args.dry_run:
        log("=== DRY RUN MODE — no actual containers or HTTP calls ===")

    log("=" * 60)
    log("NightHunt DS Fleet Load Test")
    log("=" * 60)
    log(f"Backend:      {args.backend}")
    log(f"DS count:     {args.ds_count}")
    log(f"Fail DS idx:  {args.fail_ds} (FAIL_AFTER={args.fail_after}s)")
    log(f"Monitor:      {args.monitor_sec}s")
    log(f"User load:    {'skipped' if args.no_users else args.user_count}")
    log(f"Game lifecycle: start_delay={args.game_start_delay}s "
        f"players={args.simulate_players} duration={args.game_duration}s")
    log(f"(Each DS: ready → in_game after {args.game_start_delay}s → reclaimed after {args.game_duration}s)")
    log("")

    # Build image if requested
    if args.build:
        build_mock_image()

    # Verify image exists (skip in dry-run)
    if not args.dry_run:
        result = subprocess.run(
            ["docker", "image", "inspect", MOCK_DS_IMAGE],
            capture_output=True
        )
        if result.returncode != 0:
            log(f"ERROR: Docker image '{MOCK_DS_IMAGE}' not found.")
            log(f"Run with --build to build it first, or:")
            log(f"  cd {MOCK_DS_DIR} && docker build -t {MOCK_DS_IMAGE} .")
            sys.exit(1)

    test_start    = time.time()
    user_results  = {}
    allocated     = []

    # Backend internal URL (for containers on same Docker network)
    backend_internal = "http://nighthunt-backend:8080"

    try:
        # ── Phase 1: User load ──────────────────────────────────────────────
        if not args.no_users and not args.dry_run:
            user_results = phase1_user_load(args.backend, args.user_count)
        else:
            log("Phase 1 ▶ Skipped (--no-users or dry-run)")

        # ── Phase 2: Allocate DS ────────────────────────────────────────────
        allocated = phase2_allocate_ds(
            args.backend, args.admin_secret, args.ds_count,
            args.fail_ds, args.fail_after,
            backend_internal, args.dry_run,
            game_duration=args.game_duration,
            game_start_delay=args.game_start_delay,
            simulate_players=args.simulate_players,
        )
        if not allocated:
            log("ERROR: No DS containers allocated — aborting")
            sys.exit(1)

        # Wait for containers to register (give them ~15s)
        if not args.dry_run:
            log("Waiting 15s for containers to register with backend…")
            time.sleep(15)

        # ── Phase 3: Health monitoring ──────────────────────────────────────
        snapshots = phase3_monitor(
            allocated, args.monitor_sec, args.backend,
            args.admin_secret, args.dry_run
        )

        # ── Phase 4: Inject failure ─────────────────────────────────────────
        failure_result = phase4_inject_failure(allocated, args.fail_ds, args.dry_run)

        # ── Phase 5: Wait for backend to detect failure ─────────────────────
        if not failure_result.get("skipped"):
            phase5_wait_backend_cleanup(HEARTBEAT_WAIT_SEC)

        # ── Phase 6: Fleet reclaim ──────────────────────────────────────────
        reclaim_result = phase6_fleet_reclaim(
            args.backend, args.admin_secret,
            auto_reclaim=False,  # always manual trigger in test
            dry_run=args.dry_run
        )

        # ── Phase 7: Report ─────────────────────────────────────────────────
        total_elapsed = time.time() - test_start
        report_path = phase7_report(
            args, user_results, allocated, snapshots,
            failure_result, reclaim_result, total_elapsed
        )
        log(f"Test complete. Report: {report_path}")

    except KeyboardInterrupt:
        log("Interrupted — cleaning up containers…")
    finally:
        if not args.dry_run:
            log("Stopping all mock-ds containers…")
            cleanup_containers(allocated)
            log("Cleanup done.")


if __name__ == "__main__":
    main()
