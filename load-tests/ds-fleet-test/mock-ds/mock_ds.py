#!/usr/bin/env python3
"""
Mock Unity Dedicated Server — dùng cho DS Fleet load test.

Mô phỏng ĐÚNG vòng đời Unity DS:
  1. Startup  → POST /api/ds/register  (status: starting → ready)
  2. Players join (GAME_START_DELAY s) → heartbeat currentPlayers=SIMULATE_PLAYERS
                                         backend đổi status → in_game
                                         → findAvailable() KHÔNG trả về DS này nữa
                                         → trận tiếp theo SPAWN DS MỚI
  3. Game kết thúc (GAME_DURATION s)  → ngừng heartbeat
                                         backend cleanupDeadServers (90s) → reclaim
  4. FAIL_AFTER_SECONDS > 0           → crash giữa chừng (simulate container die)

Tại sao cần simulate in_game:
  - Nếu không gửi currentPlayers > 0, DS mãi ở status "ready"
  - findAvailable() tìm thấy DS "ready" → reuse cho trận mới → không bao giờ spawn DS thứ 2
  - Production: Unity client thực sự connect → backend nhận heartbeat có players > 0

ENV vars:
  SERVER_ID           - UUID từ /api/admin/ds/test-alloc
  SERVER_SECRET       - plain text secret từ devSecret
  GAME_PORT           - UDP port (default 7777)
  BACKEND_URL         - http://... (không có trailing slash)
  FAIL_AFTER_SECONDS  - 0 = không crash; >0 = crash sau X giây
  MOCK_DS_HTTP_PORT   - port HTTP health endpoint (default 9090)
  GAME_START_DELAY    - giây chờ trước khi "players join" (default 5)
  SIMULATE_PLAYERS    - số player giả lập (default 4, >0 để vào in_game)
  GAME_DURATION       - giây game chạy trước khi DS tự stop heartbeat (default 120)
"""

import os
import sys
import time
import threading
import signal

import requests
from http.server import BaseHTTPRequestHandler, HTTPServer
import json

# ── Config ─────────────────────────────────────────────────────────────────────
BACKEND_URL        = os.environ.get("BACKEND_URL", "http://localhost:8080").rstrip("/")
SERVER_ID          = os.environ.get("SERVER_ID", "")
SERVER_SECRET      = os.environ.get("SERVER_SECRET", "")
GAME_PORT          = int(os.environ.get("GAME_PORT", "7777"))
FAIL_AFTER         = int(os.environ.get("FAIL_AFTER_SECONDS", "0"))
HTTP_PORT          = int(os.environ.get("MOCK_DS_HTTP_PORT", "9090"))
GAME_START_DELAY   = int(os.environ.get("GAME_START_DELAY", "5"))    # giây trước khi players "join"
SIMULATE_PLAYERS   = int(os.environ.get("SIMULATE_PLAYERS", "4"))    # số player giả
GAME_DURATION      = int(os.environ.get("GAME_DURATION", "120"))     # giây game chạy
HEARTBEAT_INTERVAL = 30

# ── State ──────────────────────────────────────────────────────────────────────
state = {
    "registered":       False,
    "heartbeat_count":  0,
    "heartbeat_errors": 0,
    "started_at":       time.time(),
    "register_attempts":0,
    "last_heartbeat_at":None,
    "should_exit":      False,
    "game_phase":       "starting",   # starting → ready → in_game → ended
    "current_players":  0,
}

print(f"[MockDS] Starting — serverId={SERVER_ID} port={GAME_PORT} backend={BACKEND_URL}")
print(f"[MockDS] Game lifecycle: start_delay={GAME_START_DELAY}s players={SIMULATE_PLAYERS} duration={GAME_DURATION}s")
if FAIL_AFTER > 0:
    print(f"[MockDS] Will simulate FAILURE in {FAIL_AFTER}s")


# ── HTTP Health Handler ────────────────────────────────────────────────────────
class HealthHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass  # Suppress default access log

    def do_GET(self):
        if self.path == "/health":
            uptime = int(time.time() - state["started_at"])
            body = json.dumps({
                "status":           "ok" if state["registered"] else "starting",
                "serverId":         SERVER_ID,
                "port":             GAME_PORT,
                "registered":       state["registered"],
                "gamePhase":        state["game_phase"],
                "currentPlayers":   state["current_players"],
                "heartbeatCount":   state["heartbeat_count"],
                "heartbeatErrors":  state["heartbeat_errors"],
                "uptimeSeconds":    uptime,
                "lastHeartbeatAt":  state["last_heartbeat_at"],
                "failAfterSeconds": FAIL_AFTER,
                "gameDuration":     GAME_DURATION,
            }).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", len(body))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()


# ── Register ──────────────────────────────────────────────────────────────────
def do_register():
    """Call POST /api/ds/register — retry up to 8 times with backoff."""
    for attempt in range(1, 9):
        state["register_attempts"] = attempt
        try:
            resp = requests.post(
                f"{BACKEND_URL}/api/ds/register",
                json={
                    "serverId":     SERVER_ID,
                    "serverSecret": SERVER_SECRET,
                    "port":         GAME_PORT,
                    "maxPlayers":   4,
                },
                timeout=10,
            )
            if resp.status_code == 200:
                state["registered"]  = True
                state["game_phase"]  = "ready"
                print(f"[MockDS] Registered OK ✓ serverId={SERVER_ID} → status=ready")
                print(f"[MockDS]   Players will join in {GAME_START_DELAY}s → in_game")
                print(f"[MockDS]   Game will run {GAME_DURATION}s → DS reclaimed")
                return True
            else:
                print(f"[MockDS] Register attempt {attempt} failed: HTTP {resp.status_code} — {resp.text[:120]}")
        except Exception as exc:
            print(f"[MockDS] Register attempt {attempt} error: {exc}")
        time.sleep(min(2 ** attempt, 30))  # exponential backoff, max 30s

    print(f"[MockDS] FATAL: could not register after 8 attempts — exiting")
    os._exit(2)


# ── Heartbeat loop (game lifecycle) ───────────────────────────────────────────
def heartbeat_loop():
    """
    Mô phỏng đúng game lifecycle:
      Phase "ready"   → players=0, DS available cho findAvailable()
      Phase "in_game" → players>0, DS KHÔNG bị findAvailable() chọn → spawn DS mới
      Phase "ended"   → ngừng heartbeat → backend cleanupDeadServers reclaims sau 90s
    """
    # Wait for registration
    while not state["registered"] and not state["should_exit"]:
        time.sleep(1)

    game_start_at = None

    while not state["should_exit"]:
        uptime = time.time() - state["started_at"]

        # ── Failure injection ──────────────────────────────────────────────
        if FAIL_AFTER > 0 and uptime >= FAIL_AFTER:
            print(f"[MockDS] ⚡ SIMULATING FAILURE — uptime={uptime:.1f}s")
            os._exit(1)

        # ── Game phase transitions ─────────────────────────────────────────
        if state["game_phase"] == "ready" and uptime >= GAME_START_DELAY:
            # Players "join" the match
            state["game_phase"]    = "in_game"
            state["current_players"] = SIMULATE_PLAYERS
            game_start_at          = time.time()
            print(f"[MockDS] ▶ Players joined: currentPlayers={SIMULATE_PLAYERS} → status=in_game")
            print(f"[MockDS]   findAvailable() sẽ BỎ QUA DS này → trận mới sẽ spawn DS mới")

        elif state["game_phase"] == "in_game" and game_start_at is not None:
            game_elapsed = time.time() - game_start_at
            if game_elapsed >= GAME_DURATION:
                # Game ended — stop heartbeating → backend reclaims after 90s no-heartbeat
                state["game_phase"]    = "ended"
                state["current_players"] = 0
                print(f"[MockDS] ■ Game ended after {game_elapsed:.0f}s — stopping heartbeat")
                print(f"[MockDS]   Backend sẽ reclaim DS sau 90s không có heartbeat")
                break  # Exit loop → no more heartbeats → backend detects dead DS

        # ── Send heartbeat ─────────────────────────────────────────────────
        if state["game_phase"] == "ended":
            break

        try:
            resp = requests.post(
                f"{BACKEND_URL}/api/ds/heartbeat",
                json={
                    "serverId":       SERVER_ID,
                    "serverSecret":   SERVER_SECRET,
                    "currentPlayers": state["current_players"],
                },
                timeout=5,
            )
            if resp.status_code == 200:
                state["heartbeat_count"] += 1
                state["last_heartbeat_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
                print(f"[MockDS] ♥ Heartbeat #{state['heartbeat_count']} "
                      f"phase={state['game_phase']} players={state['current_players']}")
            else:
                state["heartbeat_errors"] += 1
                print(f"[MockDS] Heartbeat error: HTTP {resp.status_code}")
        except Exception as exc:
            state["heartbeat_errors"] += 1
            print(f"[MockDS] Heartbeat exception: {exc}")

        time.sleep(HEARTBEAT_INTERVAL)


# ── HTTP server ────────────────────────────────────────────────────────────────
def start_http_server():
    server = HTTPServer(("0.0.0.0", HTTP_PORT), HealthHandler)
    print(f"[MockDS] Health endpoint: http://0.0.0.0:{HTTP_PORT}/health")
    server.serve_forever()


# ── Graceful shutdown ──────────────────────────────────────────────────────────
def handle_signal(signum, frame):
    print(f"[MockDS] Signal {signum} received — shutting down cleanly")
    state["should_exit"] = True
    sys.exit(0)


# ── Main ──────────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    if not SERVER_ID or not SERVER_SECRET:
        print("[MockDS] ERROR: SERVER_ID and SERVER_SECRET must be set")
        sys.exit(1)

    signal.signal(signal.SIGTERM, handle_signal)
    signal.signal(signal.SIGINT, handle_signal)

    # Start HTTP health server in background
    threading.Thread(target=start_http_server, daemon=True).start()

    # Register (blocks until success or fatal error)
    threading.Thread(target=do_register, daemon=True).start()

    # Start heartbeat loop
    heartbeat_thread = threading.Thread(target=heartbeat_loop, daemon=True)
    heartbeat_thread.start()
    heartbeat_thread.join()
