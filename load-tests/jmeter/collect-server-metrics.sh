#!/usr/bin/env bash
# =============================================================================
# collect-server-metrics.sh
# Polls /api/admin/ds/system-metrics every 5s during a JMeter run.
# Outputs a CSV that maps to the CPU% and RAM columns in your stress test table.
#
# Usage:
#   chmod +x collect-server-metrics.sh
#   ./collect-server-metrics.sh 500vu   # label for this scenario
#   # (run this BEFORE starting JMeter, stop with Ctrl+C after JMeter finishes)
#
# Output: results/server-metrics-<LABEL>.csv
# =============================================================================
set -euo pipefail

LABEL="${1:-test}"
BASE_URL="https://vawnwuyest.me"
ADMIN_SECRET="${ADMIN_SECRET:-}"          # export ADMIN_SECRET=... before running
INTERVAL=5                                # seconds between polls
OUT_DIR="$(dirname "$0")/results"
OUT_FILE="${OUT_DIR}/server-metrics-${LABEL}.csv"

if [[ -z "$ADMIN_SECRET" ]]; then
  echo "[ERROR] Set ADMIN_SECRET env var first:"
  echo "  export ADMIN_SECRET=nh_ds_xxxxx"
  exit 1
fi

mkdir -p "$OUT_DIR"

echo "Timestamp,CPU_%,JVM_Heap_MB,JVM_Heap_%,DB_Active,DB_Max,Uptime_s,Active_DS" \
  | tee "$OUT_FILE"

echo "[INFO] Collecting metrics → $OUT_FILE (Ctrl+C to stop)"
echo "[INFO] Polling every ${INTERVAL}s from $BASE_URL"

while true; do
  TS=$(date +"%Y-%m-%dT%H:%M:%S")

  RESPONSE=$(curl -s -w "\n%{http_code}" \
    -H "X-Admin-Secret: ${ADMIN_SECRET}" \
    "${BASE_URL}/api/admin/ds/system-metrics" 2>/dev/null || echo "")

  HTTP_CODE=$(echo "$RESPONSE" | tail -1)
  BODY=$(echo "$RESPONSE" | head -n -1)

  if [[ "$HTTP_CODE" != "200" ]]; then
    echo "$TS,ERROR,,,,,," | tee -a "$OUT_FILE"
    sleep "$INTERVAL"
    continue
  fi

  # Parse JSON fields using grep+sed (no jq dependency required)
  CPU=$(echo "$BODY"    | grep -oP '"systemCpuPercent"\s*:\s*\K[0-9.]+' | head -1)
  HEAP=$(echo "$BODY"   | grep -oP '"heapUsedMb"\s*:\s*\K[0-9.]+' | head -1)
  HEAPPCT=$(echo "$BODY"| grep -oP '"heapPercent"\s*:\s*\K[0-9.]+' | head -1)
  DBACT=$(echo "$BODY"  | grep -oP '"activeConnections"\s*:\s*\K[0-9]+' | head -1)
  DBMAX=$(echo "$BODY"  | grep -oP '"maxConnections"\s*:\s*\K[0-9]+' | head -1)
  UPTIME=$(echo "$BODY" | grep -oP '"uptimeSeconds"\s*:\s*\K[0-9]+' | head -1)
  ACTIVE_DS=$(echo "$BODY" | grep -oP '"activeDedicatedServers"\s*:\s*\K[0-9]+' | head -1)

  ROW="${TS},${CPU:-?},${HEAP:-?},${HEAPPCT:-?},${DBACT:-?},${DBMAX:-?},${UPTIME:-?},${ACTIVE_DS:-0}"
  echo "$ROW" | tee -a "$OUT_FILE"

  sleep "$INTERVAL"
done
