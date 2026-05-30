#!/usr/bin/env bash
# =============================================================================
# run-all-scenarios.sh
# Runs 3 stress test scenarios sequentially and generates HTML reports.
# Results match the table format: Throughput / Avg / p95 / p99 / Error% / CPU / RAM
#
# Prerequisites:
#   1. Apache JMeter installed: https://jmeter.apache.org/download_jmeter.cgi
#      - Set JMETER_HOME (e.g. export JMETER_HOME=/opt/jmeter)
#   2. Install JMeter Plugins Manager for graphs:
#      - Download: https://jmeter-plugins.org/wiki/PluginsManager/
#      - Place plugins-manager.jar in $JMETER_HOME/lib/ext/
#      - Required plugins: jpgc-graphs-basic, jpgc-perfmon, jpgc-synthesis
#   3. Set test credentials:
#      export NH_USERNAME="your_test_account"
#      export NH_PASSWORD="your_password"
#      export ADMIN_SECRET="nh_ds_xxxxx"
#
# Run: chmod +x run-all-scenarios.sh && ./run-all-scenarios.sh
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JMX="${SCRIPT_DIR}/nighthunt-stress-test.jmx"
RESULTS_DIR="${SCRIPT_DIR}/results"
REPORTS_DIR="${SCRIPT_DIR}/reports"

# ── Validate prerequisites ─────────────────────────────────────────────────
if [[ -z "${JMETER_HOME:-}" ]]; then
  # Try common install locations
  for d in /opt/jmeter /usr/local/jmeter ~/apache-jmeter*; do
    if [[ -x "${d}/bin/jmeter" ]]; then
      JMETER_HOME="$d"
      break
    fi
  done
fi

if [[ -z "${JMETER_HOME:-}" ]] || [[ ! -x "${JMETER_HOME}/bin/jmeter" ]]; then
  echo "[ERROR] JMETER_HOME not set or jmeter binary not found."
  echo "  Download: https://jmeter.apache.org/download_jmeter.cgi"
  echo "  Then: export JMETER_HOME=/path/to/apache-jmeter-5.6.3"
  exit 1
fi

NH_USERNAME="${NH_USERNAME:-testuser1}"
NH_PASSWORD="${NH_PASSWORD:-Test@123456}"
ADMIN_SECRET="${ADMIN_SECRET:-}"

echo "╔══════════════════════════════════════════════════════════╗"
echo "║  NightHunt Backend Stress Test — 3 Scenarios             ║"
echo "║  Target: https://vawnwuyest.me                           ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""
echo "JMeter: ${JMETER_HOME}/bin/jmeter"
echo "User:   ${NH_USERNAME}"
echo ""

mkdir -p "$RESULTS_DIR" "$REPORTS_DIR"

# ── Fresh run cleanup ─────────────────────────────────────────────────────
echo "[INFO] Removing previous JMeter reports/results ..."
find "$REPORTS_DIR" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
find "$RESULTS_DIR" -mindepth 1 -maxdepth 1 -type f \( -name '*.jtl' -o -name '*.log' -o -name 'server-metrics-*.csv' \) -delete
echo "[INFO] Cleanup complete. Fresh run will produce only: 500vu / 1000vu / 2000vu"

# ── Run one scenario ───────────────────────────────────────────────────────
run_scenario() {
  local LABEL="$1"
  local VU="$2"
  local RAMPUP="$3"
  local DURATION="$4"

  local JTL="${RESULTS_DIR}/${LABEL}.jtl"
  local HTML_REPORT="${REPORTS_DIR}/${LABEL}"

  echo ""
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "  Scenario: ${LABEL}"
  echo "  Virtual Users: ${VU}  |  Ramp-up: ${RAMPUP}s  |  Duration: ${DURATION}s"
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

  # Start server metrics collector in background
  if [[ -n "$ADMIN_SECRET" ]]; then
    export ADMIN_SECRET
    bash "${SCRIPT_DIR}/collect-server-metrics.sh" "$LABEL" &
    COLLECTOR_PID=$!
    echo "[INFO] Server metrics collector PID: $COLLECTOR_PID"
  fi

  # Remove old JTL if exists
  rm -f "$JTL"
  rm -rf "$HTML_REPORT"

  # Run JMeter headless
  "${JMETER_HOME}/bin/jmeter" \
    -n \
    -t "$JMX" \
    -Jvusers="$VU" \
    -Jrampup="$RAMPUP" \
    -Jduration="$DURATION" \
    -Jusername="$NH_USERNAME" \
    -Jpassword="$NH_PASSWORD" \
    -l "$JTL" \
    -e -o "$HTML_REPORT" \
    -j "${RESULTS_DIR}/${LABEL}-jmeter.log" \
    2>&1 | tail -20

  # Stop metrics collector
  if [[ -n "${COLLECTOR_PID:-}" ]]; then
    kill "$COLLECTOR_PID" 2>/dev/null || true
    unset COLLECTOR_PID
  fi

  # Print quick summary
  echo ""
  echo "[RESULT] ${LABEL} — HTML report: ${HTML_REPORT}/index.html"

  # Extract key metrics from JTL with awk
  if [[ -f "$JTL" ]]; then
    echo ""
    echo "[SUMMARY] Key metrics from ${LABEL}.jtl:"
    awk -F',' 'NR>1 && $3!="label" {
      total++; dur+=$2; if($8=="false") err++;
      if($2>max) max=$2
    }
    END {
      printf "  Total requests : %d\n", total
      printf "  Error count    : %d (%.2f%%)\n", err, (total>0 ? err/total*100 : 0)
      printf "  Avg latency    : %.1f ms\n", (total>0 ? dur/total : 0)
      printf "  Max latency    : %.1f ms\n", max
    }' "$JTL" 2>/dev/null || echo "  (awk parse failed, check HTML report)"
    echo "  p95/p99        : open HTML report → Statistics table"
  fi

  # Cool-down between scenarios
  if [[ "$LABEL" != "scenario-2000vu" ]]; then
    echo ""
    echo "[INFO] Cool-down 30s before next scenario..."
    sleep 30
  fi
}

# ── Execute 3 scenarios ────────────────────────────────────────────────────
#                Label           VU    Ramp  Duration
run_scenario "scenario-500vu"   500    30     60
run_scenario "scenario-1000vu" 1000    30     60
run_scenario "scenario-2000vu" 2000    30     60

echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║  ALL SCENARIOS COMPLETE                                   ║"
echo "╠══════════════════════════════════════════════════════════╣"
echo "║  HTML Reports:                                            ║"
echo "║    reports/scenario-500vu/index.html                     ║"
echo "║    reports/scenario-1000vu/index.html                    ║"
echo "║    reports/scenario-2000vu/index.html                    ║"
echo "╠══════════════════════════════════════════════════════════╣"
echo "║  Server Metrics (CPU/RAM during test):                   ║"
echo "║    results/server-metrics-scenario-500vu.csv             ║"
echo "║    results/server-metrics-scenario-1000vu.csv            ║"
echo "║    results/server-metrics-scenario-2000vu.csv            ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""
echo "To build the table (like Bảng 4.2 in your report):"
echo "  1. Open each HTML report → Statistics tab"
echo "     → copy Throughput, Avg, p95, p99, Error%"
echo "  2. Open server-metrics-*.csv"
echo "     → take MAX(CPU_%) and MAX(JVM_Heap_MB) for each scenario"
echo "  3. Fill into table: 500 VU / 1000 VU / 2000 VU columns"
echo ""
echo "Graphs to screenshot for report appendix:"
echo "  - Response Times Over Time (requires jpgc plugin)"
echo "  - Transactions Per Second"
echo "  - HTML Report: Charts tab has all graphs exportable as PNG"
