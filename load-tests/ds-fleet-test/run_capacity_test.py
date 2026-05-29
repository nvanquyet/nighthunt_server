#!/usr/bin/env python3
"""
DS Concurrent Capacity Test
============================
Trả lời câu hỏi: Backend chịu được bao nhiêu trận (DS) đồng thời?

Kịch bản thực tế:
  Player ghép trận → backend allocate 1 DS → players join (in_game) → chơi → DS thu hồi
  5v5 = 10 players/trận, 500 VU → lý thuyết 50 DS đồng thời
  → Test xem backend chịu được bao nhiêu DS concurrent thực sự?

Không cần Docker container cho mỗi DS — mỗi "virtual DS" chỉ là thread gửi HTTP:
  1. POST /admin/ds/test-alloc   → nhận serverId + devSecret + port
  2. POST /ds/register           → báo backend DS đã sẵn sàng (status=ready)
  3. POST /ds/heartbeat mỗi 30s với currentPlayers > 0 → backend mark in_game
     (findAvailable() bỏ qua DS in_game → buộc backend track riêng từng DS)

Ramp strategy:
  - Mỗi RAMP_INTERVAL giây: spawn thêm BATCH_SIZE virtual DS mới đồng thời
  - Dừng khi: lỗi liên tiếp >= STOP_THRESHOLD batch, hoặc đạt MAX_DS
  - Sustain: giữ tất cả DS heartbeating SUSTAIN_SEC giây ở peak

Metrics:
  - Peak concurrent DS
  - Alloc / register / heartbeat latency p50/p95/p99
  - Error rate từng phase
  - Điểm gãy (failure threshold)

Usage:
  python3 run_capacity_test.py --admin-secret SECRET [options]

  # Quick test: ramp tới 50 DS, batch 5, interval 10s
  python3 run_capacity_test.py --admin-secret SECRET --max-ds 50 --batch-size 5

  # Full stress: ramp tới 200 DS
  python3 run_capacity_test.py --admin-secret SECRET --max-ds 200 --batch-size 10 --ramp-sec 20

Requirements:
  pip install requests
"""

import argparse
import json
import statistics
import subprocess
import sys
import threading
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from typing import Optional

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

# ── Constants ──────────────────────────────────────────────────────────────────
DEFAULT_BACKEND      = "http://localhost:8080/api"
DEFAULT_MAX_DS       = 100
DEFAULT_BATCH_SIZE   = 10
DEFAULT_RAMP_SEC     = 15        # giây giữa các batch
DEFAULT_SUSTAIN_SEC  = 120       # giữ peak load bao lâu
DEFAULT_HB_INTERVAL  = 30        # heartbeat interval (giây)
DEFAULT_PLAYERS      = 4         # currentPlayers mỗi DS báo (>0 = in_game)
STOP_THRESHOLD       = 3         # batch liên tiếp thất bại hoàn toàn → dừng ramp
ALLOC_TIMEOUT        = 10        # HTTP timeout cho alloc/register
HB_TIMEOUT           = 8         # HTTP timeout cho heartbeat
REGION               = "test"
FAKE_IP              = "10.0.0.1"  # IP giả cho virtual DS


# ── Shared state (guarded by _lock) ───────────────────────────────────────────
_lock               = threading.Lock()
_ds_registry: list  = []   # mỗi entry: dict per virtual DS
_alloc_lat: list    = []   # alloc latency ms
_reg_lat: list      = []   # register latency ms
_hb_lat: list       = []   # heartbeat latency ms
_alloc_errors       = 0
_register_errors    = 0
_hb_errors          = 0
_total_allocated    = 0    # alloc+register succeeded
_peak_concurrent    = 0
_events: list       = []
_sys_snapshots: list = []  # system metrics snapshots during test


def _ts() -> str:
    return datetime.now(timezone.utc).strftime("%H:%M:%S")


def log(msg: str):
    print(f"[{_ts()}] {msg}", flush=True)


def _event(kind: str, detail: str = ""):
    _events.append({"time": _ts(), "kind": kind, "detail": detail})


# ── HTTP session ───────────────────────────────────────────────────────────────
def _make_session() -> requests.Session:
    s = requests.Session()
    retry = Retry(total=1, backoff_factor=0.2, status_forcelist=[429, 500, 502, 503])
    s.mount("http://", HTTPAdapter(max_retries=retry))
    s.mount("https://", HTTPAdapter(max_retries=retry))
    return s


# ── Virtual DS thread ──────────────────────────────────────────────────────────
def _virtual_ds(backend: str, admin_secret: str, idx: int,
                players: int, hb_interval: int, stop_evt: threading.Event):
    """
    Vòng đời 1 virtual DS (chạy trong thread riêng):
      alloc → register → heartbeat loop → exit khi stop_evt
    """
    global _alloc_errors, _register_errors, _total_allocated, _peak_concurrent, _hb_errors

    sess = _make_session()
    ah = {"X-Admin-Secret": admin_secret, "Content-Type": "application/json"}

    # ── 1. Alloc ──────────────────────────────────────────────────────────────
    t0 = time.time()
    try:
        r = sess.post(f"{backend}/admin/ds/test-alloc", headers=ah,
                      json={}, timeout=ALLOC_TIMEOUT)
        lat = (time.time() - t0) * 1000
        r.raise_for_status()
        data       = r.json()
        server_id  = data["data"]["serverId"]
        secret     = data["data"]["devSecret"]
        port       = data["data"].get("port", 7000 + idx)
    except Exception as e:
        with _lock:
            _alloc_errors += 1
        log(f"  [DS-{idx:04d}] ✗ ALLOC fail: {e}")
        return

    with _lock:
        _alloc_lat.append((time.time() - t0) * 1000)

    # ── 2. Register ───────────────────────────────────────────────────────────
    t0 = time.time()
    try:
        r = sess.post(f"{backend}/ds/register", json={
            "serverId":     server_id,
            "serverSecret": secret,
            "reportedIp":   FAKE_IP,
            "port":         port,
            "maxPlayers":   10,
            "status":       "ready",
        }, timeout=ALLOC_TIMEOUT)
        lat = (time.time() - t0) * 1000
        r.raise_for_status()
    except Exception as e:
        with _lock:
            _register_errors += 1
        log(f"  [DS-{idx:04d}] ✗ REGISTER fail ({server_id[:8]}…): {e}")
        return

    with _lock:
        _reg_lat.append(lat)

    # ── Mark alive ─────────────────────────────────────────────────────────────
    entry = {
        "idx":       idx,
        "serverId":  server_id,
        "alive":     True,
        "hb_ok":     0,
        "hb_fail":   0,
    }
    with _lock:
        _ds_registry.append(entry)
        _total_allocated += 1
        current = sum(1 for d in _ds_registry if d["alive"])
        if current > _peak_concurrent:
            _peak_concurrent = current

    # ── 3. Heartbeat loop ─────────────────────────────────────────────────────
    # currentPlayers > 0 → backend sets status=in_game
    # → findAvailable() bỏ qua DS này → trận kế spawn DS MỚI
    while not stop_evt.is_set():
        t0 = time.time()
        try:
            r = sess.post(f"{backend}/ds/heartbeat", json={
                "serverId":      server_id,
                "serverSecret":  secret,
                "currentPlayers": players,
            }, timeout=HB_TIMEOUT)
            lat = (time.time() - t0) * 1000
            r.raise_for_status()
            with _lock:
                entry["hb_ok"] += 1
                _hb_lat.append(lat)
        except Exception as e:
            with _lock:
                entry["hb_fail"] += 1
                _hb_errors += 1

        stop_evt.wait(timeout=hb_interval)

    with _lock:
        entry["alive"] = False


# ── System metrics collection ─────────────────────────────────────────────────
def _docker_stats_snapshot(backend_ctr: str, mysql_ctr: str) -> dict:
    """Single docker stats snapshot for both containers."""
    try:
        out = subprocess.check_output(
            ["docker", "stats", "--no-stream", "--format",
             "{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}",
             backend_ctr, mysql_ctr],
            timeout=6, text=True, stderr=subprocess.DEVNULL,
        )
        rows = {}
        for line in out.strip().splitlines():
            parts = line.split("\t")
            if len(parts) >= 4:
                rows[parts[0].strip()] = {
                    "cpu_pct":   parts[1].strip(),
                    "mem_usage": parts[2].strip(),
                    "mem_pct":   parts[3].strip(),
                }
        return rows
    except Exception:
        return {}


def _mysql_stats_snapshot(mysql_ctr: str, db_user: str, db_pass: str) -> dict:
    """Query MySQL SHOW STATUS for connection/query counters."""
    vars_sql = (
        "SHOW STATUS LIKE 'Threads_connected'; "
        "SHOW STATUS LIKE 'Max_used_connections'; "
        "SHOW STATUS LIKE 'Queries';"
    )
    try:
        out = subprocess.check_output(
            ["docker", "exec", mysql_ctr, "mysql",
             f"-u{db_user}", f"-p{db_pass}",
             "-e", vars_sql],
            timeout=6, text=True, stderr=subprocess.DEVNULL,
        )
        stats = {}
        for line in out.strip().splitlines():
            parts = line.split("\t")
            if len(parts) == 2 and parts[0] != "Variable_name":
                stats[parts[0]] = parts[1]
        return stats
    except Exception:
        return {}


def _metrics_collector(
    backend_ctr: str, mysql_ctr: str,
    db_user: str, db_pass: str,
    interval: int, stop_evt: threading.Event,
):
    """Background thread: snapshot docker + MySQL metrics every `interval` s."""
    while not stop_evt.wait(timeout=interval):
        snap = {
            "time":   _ts(),
            "docker": _docker_stats_snapshot(backend_ctr, mysql_ctr),
            "mysql":  _mysql_stats_snapshot(mysql_ctr, db_user, db_pass),
        }
        with _lock:
            _sys_snapshots.append(snap)


# ── Live reporter thread ───────────────────────────────────────────────────────
def _reporter(stop_evt: threading.Event, interval: int = 15):
    while not stop_evt.wait(timeout=interval):
        with _lock:
            alive       = sum(1 for d in _ds_registry if d["alive"])
            total_hb    = sum(d["hb_ok"]   for d in _ds_registry)
            total_hb_f  = sum(d["hb_fail"] for d in _ds_registry)
        log(f"  >> concurrent={alive}  peak={_peak_concurrent}  "
            f"allocated={_total_allocated}  alloc_err={_alloc_errors}  "
            f"hb_ok={total_hb}  hb_fail={total_hb_f}")


# ── Ramp phase ────────────────────────────────────────────────────────────────
def ramp(args, stop_evt: threading.Event, executor: ThreadPoolExecutor):
    batch_num            = 0
    consecutive_failures = 0
    ds_index             = 0

    log(f"═══ RAMP START: {args.batch_size} DS/batch, interval={args.ramp_sec}s, "
        f"max={args.max_ds} ═══")
    log(f"Each DS heartbeats {args.players} players every {args.hb_interval}s → in_game")

    while ds_index < args.max_ds and not stop_evt.is_set():
        batch_num  += 1
        batch_size  = min(args.batch_size, args.max_ds - ds_index)
        log(f"\n[BATCH {batch_num}] Spawning {batch_size} DS "
            f"(total attempted so far: {ds_index}) ...")

        alloc_err_before = _alloc_errors
        futures = [
            executor.submit(
                _virtual_ds,
                args.backend, args.admin_secret,
                ds_index + i,
                args.players, args.hb_interval,
                stop_evt,
            )
            for i in range(batch_size)
        ]
        ds_index += batch_size

        # Ngắn để alloc kịp xong trước khi check
        time.sleep(max(3, min(8, ALLOC_TIMEOUT)))

        with _lock:
            batch_alloc_errors = _alloc_errors - alloc_err_before

        if batch_alloc_errors >= batch_size:
            # Toàn bộ batch fail
            consecutive_failures += 1
            log(f"[BATCH {batch_num}] ✗ FULL FAIL "
                f"(errors={batch_alloc_errors}, consecutive={consecutive_failures})")
            _event("BATCH_FAIL",
                   f"batch={batch_num} errors={batch_alloc_errors}/{batch_size}")
            if consecutive_failures >= STOP_THRESHOLD:
                log(f"\n⚠  {STOP_THRESHOLD} consecutive failed batches — backend at capacity limit")
                _event("RAMP_STOPPED",
                       f"consecutive_failures={consecutive_failures} ds_attempted={ds_index}")
                break
        else:
            consecutive_failures = 0
            with _lock:
                alive = sum(1 for d in _ds_registry if d["alive"])
            log(f"[BATCH {batch_num}] ✓ OK  "
                f"errors={batch_alloc_errors}/{batch_size}  "
                f"concurrent={alive}  peak={_peak_concurrent}")
            _event("BATCH_OK",
                   f"batch={batch_num} errors={batch_alloc_errors}/{batch_size} "
                   f"concurrent={alive}")

        if ds_index < args.max_ds and not stop_evt.is_set():
            log(f"  Waiting {args.ramp_sec}s before next batch…")
            time.sleep(args.ramp_sec)

    with _lock:
        alive = sum(1 for d in _ds_registry if d["alive"])
    log(f"\n═══ RAMP END: attempted={ds_index}  concurrent={alive}  peak={_peak_concurrent} ═══")
    _event("RAMP_END", f"attempted={ds_index} peak={_peak_concurrent} concurrent={alive}")


# ── Percentile helper ──────────────────────────────────────────────────────────
def _pct(data: list, p: float) -> float:
    if not data:
        return 0.0
    s = sorted(data)
    return s[min(int(len(s) * p / 100), len(s) - 1)]


# ── Report ─────────────────────────────────────────────────────────────────────
def _verdict(peak: int, alloc_err: int, hb_err: int, hb_ok: int) -> str:
    total_alloc = _total_allocated + alloc_err
    alloc_rate  = _total_allocated / max(1, total_alloc)
    hb_rate     = hb_ok / max(1, hb_ok + hb_err)

    if alloc_err == 0 and hb_rate > 0.99:
        return (f"✅ EXCELLENT — {peak} trận đồng thời, không có lỗi. "
                f"Có thể tăng MAX_DS để tìm giới hạn thực.")
    elif alloc_rate > 0.95 and hb_rate > 0.95:
        return (f"✅ GOOD — {peak} trận đồng thời, tỷ lệ lỗi nhỏ "
                f"(alloc={alloc_rate:.1%} hb={hb_rate:.1%}). Backend ổn định.")
    elif alloc_rate > 0.80 and hb_rate > 0.85:
        return (f"⚠  WARNING — {peak} trận đồng thời, degraded "
                f"(alloc={alloc_rate:.1%} hb={hb_rate:.1%}). "
                f"Tăng tài nguyên nếu muốn scale hơn.")
    else:
        return (f"🔴 CRITICAL — Backend bão hoà tại ~{peak} DS "
                f"(alloc={alloc_rate:.1%} hb={hb_rate:.1%}). "
                f"Cần scale up hoặc tối ưu DB/connection pool.")


def build_report(args) -> dict:
    with _lock:
        alive       = sum(1 for d in _ds_registry if d["alive"])
        total_hb    = sum(d["hb_ok"]   for d in _ds_registry)
        total_hb_f  = sum(d["hb_fail"] for d in _ds_registry)
        attempted   = _total_allocated + _alloc_errors + _register_errors

    return {
        "test_time": datetime.now(timezone.utc).isoformat(),
        "config": {
            "backend":      args.backend,
            "max_ds":       args.max_ds,
            "batch_size":   args.batch_size,
            "ramp_sec":     args.ramp_sec,
            "sustain_sec":  args.sustain_sec,
            "players":      args.players,
            "hb_interval":  args.hb_interval,
        },
        "results": {
            "total_attempted":    attempted,
            "total_allocated":    _total_allocated,
            "alloc_errors":       _alloc_errors,
            "register_errors":    _register_errors,
            "peak_concurrent_ds": _peak_concurrent,
            "alive_at_end":       alive,
            "total_heartbeats":   total_hb,
            "hb_errors":          total_hb_f,
            "alloc_success_rate": f"{100*_total_allocated/max(1,attempted):.1f}%",
            "hb_success_rate":    f"{100*total_hb/max(1,total_hb+total_hb_f):.1f}%",
        },
        "latency_ms": {
            "alloc": {
                "p50": round(_pct(_alloc_lat, 50), 1),
                "p95": round(_pct(_alloc_lat, 95), 1),
                "p99": round(_pct(_alloc_lat, 99), 1),
                "avg": round(statistics.mean(_alloc_lat), 1) if _alloc_lat else 0,
                "samples": len(_alloc_lat),
            },
            "register": {
                "p50": round(_pct(_reg_lat, 50), 1),
                "p95": round(_pct(_reg_lat, 95), 1),
                "p99": round(_pct(_reg_lat, 99), 1),
                "avg": round(statistics.mean(_reg_lat), 1) if _reg_lat else 0,
                "samples": len(_reg_lat),
            },
            "heartbeat": {
                "p50": round(_pct(_hb_lat, 50), 1),
                "p95": round(_pct(_hb_lat, 95), 1),
                "p99": round(_pct(_hb_lat, 99), 1),
                "avg": round(statistics.mean(_hb_lat), 1) if _hb_lat else 0,
                "samples": len(_hb_lat),
            },
        },
        "events": _events,
        "verdict": _verdict(_peak_concurrent, _alloc_errors, total_hb_f, total_hb),
        "system_metrics": _build_sys_metrics_summary(),
    }


def _build_sys_metrics_summary() -> dict:
    """Summarise system_metrics snapshots: peak CPU, peak RAM, peak connections."""
    with _lock:
        snaps = list(_sys_snapshots)
    if not snaps:
        return {"note": "not collected (run with --collect-metrics)", "snapshots": []}

    def _pct_float(s: str) -> float:
        try:
            return float(s.rstrip("%"))
        except Exception:
            return 0.0

    peak_be_cpu = 0.0
    peak_be_mem_pct = 0.0
    peak_db_cpu = 0.0
    peak_db_mem_pct = 0.0
    peak_threads = 0
    peak_max_used = 0

    for snap in snaps:
        docker = snap.get("docker", {})
        for name, vals in docker.items():
            if "backend" in name:
                peak_be_cpu     = max(peak_be_cpu,     _pct_float(vals.get("cpu_pct", "0")))
                peak_be_mem_pct = max(peak_be_mem_pct, _pct_float(vals.get("mem_pct", "0")))
            elif "mysql" in name:
                peak_db_cpu     = max(peak_db_cpu,     _pct_float(vals.get("cpu_pct", "0")))
                peak_db_mem_pct = max(peak_db_mem_pct, _pct_float(vals.get("mem_pct", "0")))
        mysql = snap.get("mysql", {})
        peak_threads  = max(peak_threads,  int(mysql.get("Threads_connected", 0)))
        peak_max_used = max(peak_max_used, int(mysql.get("Max_used_connections", 0)))

    return {
        "snapshots": snaps,
        "peak": {
            "backend_cpu_pct":       round(peak_be_cpu, 1),
            "backend_mem_pct":       round(peak_be_mem_pct, 1),
            "mysql_cpu_pct":         round(peak_db_cpu, 1),
            "mysql_mem_pct":         round(peak_db_mem_pct, 1),
            "mysql_threads_peak":    peak_threads,
            "mysql_max_used_conn":   peak_max_used,
        },
    }


def print_summary(report: dict):
    r = report["results"]
    l = report["latency_ms"]
    m = report.get("system_metrics", {}).get("peak", {})
    log("")
    log("═" * 60)
    log("  DS CONCURRENT CAPACITY TEST — KẾT QUẢ")
    log("═" * 60)
    log(f"  Peak concurrent DS (trận đồng thời):  {r['peak_concurrent_ds']}")
    log(f"  Tổng DS cấp phát thành công:          {r['total_allocated']} / {r['total_attempted']}")
    log(f"  Alloc success rate:                   {r['alloc_success_rate']}")
    log(f"  Heartbeat success rate:               {r['hb_success_rate']}")
    log(f"  Alloc latency  p50/p95/p99:           "
        f"{l['alloc']['p50']}ms / {l['alloc']['p95']}ms / {l['alloc']['p99']}ms")
    log(f"  Register latency p50/p95/p99:         "
        f"{l['register']['p50']}ms / {l['register']['p95']}ms / {l['register']['p99']}ms")
    log(f"  Heartbeat latency p50/p95/p99:        "
        f"{l['heartbeat']['p50']}ms / {l['heartbeat']['p95']}ms / {l['heartbeat']['p99']}ms")
    if m:
        log("")
        log("  ── System metrics at peak ──────────────────────────")
        log(f"  Backend  CPU peak:         {m.get('backend_cpu_pct', 'N/A')}%")
        log(f"  Backend  RAM peak:         {m.get('backend_mem_pct', 'N/A')}%")
        log(f"  MySQL    CPU peak:         {m.get('mysql_cpu_pct', 'N/A')}%")
        log(f"  MySQL    RAM peak:         {m.get('mysql_mem_pct', 'N/A')}%")
        log(f"  MySQL Threads_connected:   {m.get('mysql_threads_peak', 'N/A')}")
        log(f"  MySQL Max_used_conn:       {m.get('mysql_max_used_conn', 'N/A')}")
    log(f"\n  VERDICT: {report['verdict']}")
    log("═" * 60)


# ── Parse args ─────────────────────────────────────────────────────────────────
def parse_args():
    p = argparse.ArgumentParser(
        description="DS Concurrent Capacity Test — tìm giới hạn số trận đồng thời")
    p.add_argument("--backend",      default=DEFAULT_BACKEND,
                   help=f"Backend API base URL (default: {DEFAULT_BACKEND})")
    p.add_argument("--admin-secret", required=True,
                   help="Giá trị header X-Admin-Secret")
    p.add_argument("--max-ds",       type=int, default=DEFAULT_MAX_DS,
                   help=f"Tối đa số DS thử spawn (default: {DEFAULT_MAX_DS})")
    p.add_argument("--batch-size",   type=int, default=DEFAULT_BATCH_SIZE,
                   help=f"Số DS mỗi batch (default: {DEFAULT_BATCH_SIZE})")
    p.add_argument("--ramp-sec",     type=int, default=DEFAULT_RAMP_SEC,
                   help=f"Giây giữa các batch (default: {DEFAULT_RAMP_SEC})")
    p.add_argument("--sustain-sec",  type=int, default=DEFAULT_SUSTAIN_SEC,
                   help=f"Giây giữ peak load (default: {DEFAULT_SUSTAIN_SEC})")
    p.add_argument("--players",      type=int, default=DEFAULT_PLAYERS,
                   help=f"currentPlayers mỗi DS báo (>0 = in_game, default: {DEFAULT_PLAYERS})")
    p.add_argument("--hb-interval",  type=int, default=DEFAULT_HB_INTERVAL,
                   help=f"Heartbeat interval giây (default: {DEFAULT_HB_INTERVAL})")
    p.add_argument("--output",           default="ds_capacity_report.json",
                   help="File JSON report đầu ra (default: ds_capacity_report.json)")
    p.add_argument("--collect-metrics",  action="store_true",
                   help="Thu thập docker stats + MySQL metrics trong lúc test (tăng độ tin cậy report)")
    p.add_argument("--backend-container", default="nighthunt-backend",
                   help="Tên Docker container backend (default: nighthunt-backend)")
    p.add_argument("--mysql-container",  default="nighthunt-mysql",
                   help="Tên Docker container MySQL (default: nighthunt-mysql)")
    p.add_argument("--db-user",          default="nighthunt",
                   help="MySQL user (default: nighthunt)")
    p.add_argument("--db-pass",          default="nighthunt",
                   help="MySQL password (default: nighthunt)")
    p.add_argument("--metrics-interval", type=int, default=15,
                   help="Giây giữa mỗi lần thu thập system metrics (default: 15)")
    return p.parse_args()


# ── Main ───────────────────────────────────────────────────────────────────────
def main():
    args = parse_args()

    log("═" * 60)
    log("  DS CONCURRENT CAPACITY TEST")
    log("═" * 60)
    log(f"  Backend:     {args.backend}")
    log(f"  Ramp:        {args.batch_size} DS/batch, mỗi {args.ramp_sec}s, tối đa {args.max_ds}")
    log(f"  Sustain:     {args.sustain_sec}s sau khi ramp xong")
    log(f"  Players/DS:  {args.players} (currentPlayers > 0 → in_game)")
    log(f"  Heartbeat:   mỗi {args.hb_interval}s")
    log(f"  Stop cond:   {STOP_THRESHOLD} batch liên tiếp thất bại hoàn toàn")
    if args.collect_metrics:
        log(f"  Sys metrics: docker+MySQL mỗi {args.metrics_interval}s")
    log("═" * 60)

    _event("TEST_START", f"max_ds={args.max_ds} batch={args.batch_size}")

    stop_evt = threading.Event()

    # Reporter thread
    rep_stop = threading.Event()
    rep = threading.Thread(target=_reporter, args=(rep_stop, 15), daemon=True)
    rep.start()

    # System metrics collector thread (optional)
    metrics_stop = threading.Event()
    if args.collect_metrics:
        metrics_thr = threading.Thread(
            target=_metrics_collector,
            args=(args.backend_container, args.mysql_container,
                  args.db_user, args.db_pass,
                  args.metrics_interval, metrics_stop),
            daemon=True,
        )
        metrics_thr.start()

    # Thread pool — mỗi DS dùng 1 thread (heartbeat loop blocking)
    executor = ThreadPoolExecutor(max_workers=args.max_ds + 32, thread_name_prefix="vds")

    try:
        # Phase 1: Ramp
        ramp(args, stop_evt, executor)

        # Phase 2: Sustain
        with _lock:
            alive = sum(1 for d in _ds_registry if d["alive"])
        log(f"\n══ SUSTAIN: giữ {alive} DS × {args.hb_interval}s heartbeat trong {args.sustain_sec}s ══")
        _event("SUSTAIN_START", f"alive={alive}")

        sustain_end = time.time() + args.sustain_sec
        while time.time() < sustain_end and not stop_evt.is_set():
            time.sleep(10)
            with _lock:
                alive = sum(1 for d in _ds_registry if d["alive"])
            remaining = int(sustain_end - time.time())
            log(f"  … {alive} DS alive, còn {remaining}s")

    except KeyboardInterrupt:
        log("\n⚡ Interrupted by user")
        _event("INTERRUPTED")
    finally:
        log("\nTeardown: dừng tất cả DS threads…")
        rep_stop.set()
        metrics_stop.set()
        stop_evt.set()
        executor.shutdown(wait=False)

    _event("TEST_END")

    report = build_report(args)
    out = args.output
    with open(out, "w") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)

    print_summary(report)
    log(f"\n  Full report: {out}")


if __name__ == "__main__":
    main()
