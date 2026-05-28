/* ── System Health Page ──────────────────────────────────────────────────── */
'use strict';

async function renderSystem() {
  clearInterval(refreshTimer);
  $('content').innerHTML = `
  <div class="flex justify-between items-center mb-md">
    <span class="badge badge-green">&#9679; Auto-refresh 10s</span>
    <button class="btn btn-ghost btn-sm" onclick="loadSystem()">&#8635; Refresh</button>
  </div>
  <div id="sys-content" class="grid-2">
    <div class="card"><div class="card-body" style="text-align:center;padding:3rem"><span class="spinner"></span></div></div>
  </div>

  <div class="card mt-md">
    <div class="card-header">
      <span class="card-title">&#128200; Server Performance Metrics</span>
      <button class="btn btn-ghost btn-xs" onclick="loadMetrics()">&#8635; Refresh</button>
    </div>
    <div class="card-body">
      <p class="text-xs text-muted" style="margin-bottom:.75rem">
        &#9432; Data from <strong>Micrometer</strong> (Spring Boot) — all HTTP counters &amp; latencies are cumulative since last backend restart.
        Use the <em>Load Test</em> panel below for real-time throughput numbers.
      </p>
      <div class="stats-grid" style="margin-bottom:1rem">
        <div class="stat-card"><div class="stat-label">Avg Throughput</div><div class="stat-value text-cyan" id="m_throughput">—</div><div class="stat-sub">req/min &bull; since start</div></div>
        <div class="stat-card"><div class="stat-label">Success Rate</div><div class="stat-value text-green" id="m_successRate">—</div><div class="stat-sub">% 2xx responses</div></div>
        <div class="stat-card"><div class="stat-label">Avg Latency</div><div class="stat-value text-green" id="m_avgLatency">—</div><div class="stat-sub">ms &bull; all endpoints</div></div>
        <div class="stat-card"><div class="stat-label">p95 Latency</div><div class="stat-value text-yellow" id="m_p95">—</div><div class="stat-sub">ms &bull; 95% faster than this</div></div>
        <div class="stat-card"><div class="stat-label">p99 Latency</div><div class="stat-value" id="m_p99">—</div><div class="stat-sub">ms &bull; worst 1%</div></div>
        <div class="stat-card"><div class="stat-label">Max Latency</div><div class="stat-value" id="m_maxLatency">—</div><div class="stat-sub">ms &bull; slowest ever</div></div>
        <div class="stat-card"><div class="stat-label">Error Rate</div><div class="stat-value" id="m_errorRate">—</div><div class="stat-sub">4xx + 5xx %</div></div>
        <div class="stat-card"><div class="stat-label">Total Requests</div><div class="stat-value text-muted" id="m_totalReq">—</div><div class="stat-sub">since last restart</div></div>
        <div class="stat-card"><div class="stat-label">JVM Heap</div><div class="stat-value text-purple" id="m_heap">—</div><div class="stat-sub"><span id="m_heapPct">—</span> &bull; &gt;85% = GC pressure</div></div>
        <div class="stat-card"><div class="stat-label">DB Pool</div><div class="stat-value" id="m_dbActive">—</div><div class="stat-sub">active / <span id="m_dbMax">?</span> max &bull; <span id="m_dbPending">0</span> waiting</div></div>
        <div class="stat-card"><div class="stat-label">CPU</div><div class="stat-value" id="m_cpu">—</div><div class="stat-sub">system-level</div></div>
        <div class="stat-card"><div class="stat-label">Active DS</div><div class="stat-value text-cyan" id="m_activeDs">—</div><div class="stat-sub">game containers</div></div>
        <div class="stat-card"><div class="stat-label">Uptime</div><div class="stat-value text-green" id="m_uptime">—</div><div class="stat-sub">backend process</div></div>
      </div>
      <div id="metricsEndpointsTable"><span class="spinner"></span></div>
    </div>
  </div>

  <div class="card mt-md">
    <div class="card-header">
      <span class="card-title">&#9889; Load Test</span>
      <span class="text-muted text-xs">Browser-based HTTP benchmark &mdash; measures real throughput &amp; latency</span>
    </div>
    <div class="card-body">
      <div class="form-row">
        <div class="form-group">
          <label class="text-xs text-muted">Endpoint</label>
          <select id="bench-endpoint" class="form-control sm" style="min-width:260px">
            <option value="/api/backend/stats">GET /api/backend/stats (stats)</option>
            <option value="/api/admin/ds/servers">GET /api/admin/ds/servers</option>
            <option value="/api/system/stats">GET /api/system/stats (host info)</option>
            <option value="/api/admin/ds/system-metrics">GET /api/admin/ds/system-metrics</option>
          </select>
        </div>
        <div class="form-group">
          <label class="text-xs text-muted">Concurrency</label>
          <select id="bench-concurrency" class="form-control sm">
            <option value="1">1 (sequential)</option>
            <option value="5">5 users</option>
            <option value="10" selected>10 users</option>
            <option value="25">25 users</option>
            <option value="50">50 users</option>
          </select>
        </div>
        <div class="form-group">
          <label class="text-xs text-muted">Duration</label>
          <select id="bench-duration" class="form-control sm">
            <option value="10000">10 seconds</option>
            <option value="30000" selected>30 seconds</option>
            <option value="60000">60 seconds</option>
          </select>
        </div>
        <div class="form-group" style="align-self:flex-end">
          <button class="btn btn-primary btn-sm" id="bench-btn" onclick="runBenchmark()">&#9654; Run Load Test</button>
        </div>
      </div>
      <div id="bench-progress" class="text-sm text-muted mt-sm" style="display:none">
        <span class="spinner"></span> Running&hellip; &nbsp; <span id="bench-progress-count">0</span> requests sent
      </div>
      <div id="bench-results" style="display:none"></div>
      <p class="text-xs text-muted mt-sm">
        &#9432; Sends concurrent requests directly from this browser tab to the dashboard proxy &rarr; backend.
        Use <strong>JMeter</strong> externally for multi-origin load testing (see guide in README).
      </p>
    </div>
  </div>`;

  await loadSystem();
  await loadMetrics();
  refreshTimer = setInterval(loadSystem, 10000);
}

async function loadSystem() {
  if (!$('sys-content')) return;
  const [sysRes, backRes] = await Promise.allSettled([
    api('GET', '/api/system/stats'),
    api('GET', '/api/backend/stats')
  ]);
  const s = sysRes.status === 'fulfilled' ? sysRes.value : null;
  const b = backRes.status === 'fulfilled' ? backRes.value : null;

  const cpuU = s ? parseFloat(s.cpu.usage) : 0;
  const memU = s ? parseFloat(s.memory.usagePct) : 0;

  const barColor = pct => pct > 80 ? 'red' : pct > 60 ? 'yellow' : 'green';

  $('sys-content').innerHTML = `
  <div class="card">
    <div class="card-header"><span class="card-title">&#128187; Host Machine</span></div>
    <div class="card-body">
      ${s ? `<p class="text-muted text-sm mb-md">${s.cpu.manufacturer} ${s.cpu.brand} &bull; ${s.cpu.physicalCores} cores @ ${s.cpu.speed}GHz</p>` : ''}
      <div class="mb-md">
        <div class="flex justify-between text-sm mb-sm"><span>CPU Usage</span><strong style="color:var(--${barColor(cpuU)})">${cpuU}%</strong></div>
        <div class="health-bar"><div class="health-bar-fill ${barColor(cpuU)}" style="width:${cpuU}%"></div></div>
      </div>
      <div class="mb-md">
        <div class="flex justify-between text-sm mb-sm">
          <span>Memory</span>
          <strong style="color:var(--${barColor(memU)})">${s ? fmtBytes(s.memory.used) : '0'} / ${s ? fmtBytes(s.memory.total) : '0'} — ${memU}%</strong>
        </div>
        <div class="health-bar"><div class="health-bar-fill ${barColor(memU)}" style="width:${memU}%"></div></div>
      </div>
      <div class="text-sm"><span class="text-muted">Processes:</span> ${s ? s.processes.all : 0} total, ${s ? s.processes.running : 0} running</div>
    </div>
  </div>

  <div class="card">
    <div class="card-header"><span class="card-title">&#128190; Disk Usage</span></div>
    <div class="card-body">
      ${s ? s.disk.slice(0, 4).map(d => {
    const u = parseFloat(d.usagePct);
    return `
        <div class="mb-md">
          <div class="flex justify-between text-sm mb-sm">
            <span class="text-sm">${d.fs}</span>
            <strong style="color:var(--${barColor(u)})">${fmtBytes(d.used)} / ${fmtBytes(d.size)} — ${u}%</strong>
          </div>
          <div class="health-bar"><div class="health-bar-fill ${barColor(u)}" style="width:${u}%"></div></div>
        </div>`;
  }).join('') : '<p class="text-muted">Unavailable</p>'}
    </div>
  </div>

  <div class="card" style="grid-column:1/-1">
    <div class="card-header"><span class="card-title">&#9881; Backend Status</span></div>
    <div class="card-body">
      <div class="stats-grid">
        <div class="stat-card">
          <div class="stat-label">Backend</div>
          <div class="stat-value" style="font-size:1.5rem">${b ? '<span style="color:var(--green)">&#10003;</span>' : '<span style="color:var(--red)">&#10007;</span>'}</div>
          <div class="stat-sub">${b ? 'Online' : 'Offline'}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">Active Rooms</div>
          <div class="stat-value" style="color:var(--cyan)">${b && b.data ? b.data.totalActiveRooms || 0 : 0}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">Online Users</div>
          <div class="stat-value" style="color:var(--green)">${b && b.data ? b.data.totalOnlineUsers || 0 : 0}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">In-Game</div>
          <div class="stat-value" style="color:var(--yellow)">${b && b.data ? b.data.totalUsersInRooms || 0 : 0}</div>
        </div>
      </div>
    </div>
  </div>`;
}

// ── Metrics ───────────────────────────────────────────────────────────────────

async function loadMetrics() {
  if (!$('metricsEndpointsTable')) return;
  try {
    const json = await api('GET', '/api/admin/ds/system-metrics');
    renderMetrics(json.data || json);
  } catch (e) {
    if ($('metricsEndpointsTable'))
      $('metricsEndpointsTable').innerHTML = `<p class="text-red text-sm">Error: ${e.message}</p>`;
  }
}

function renderMetrics(d) {
  const http = d.http   || {};
  const jvm  = d.jvm    || {};
  const db   = d.db     || {};
  const sys  = d.system || {};

  const set = (id, val, unit) => {
    const el = $(id);
    if (el) el.textContent = (val !== undefined && val !== -1) ? (unit ? `${val}${unit}` : val) : '—';
  };

  set('m_throughput',  http.throughputPerMin);
  set('m_avgLatency',  http.avgLatencyMs);
  set('m_p95',         http.p95LatencyMs);
  set('m_p99',         http.p99LatencyMs);
  set('m_maxLatency',  http.maxLatencyMs);

  // Success rate
  const srEl = $('m_successRate');
  if (srEl) {
    srEl.textContent = http.successRate >= 0 ? http.successRate + '%' : '—';
    srEl.style.color = http.successRate < 95 ? 'var(--red)' : http.successRate < 99 ? 'var(--yellow)' : 'var(--green)';
  }

  const errRate = ((http.errorRate4xx || 0) + (http.errorRate5xx || 0)).toFixed(1);
  const errEl = $('m_errorRate');
  if (errEl) {
    errEl.textContent = errRate + '%';
    errEl.style.color = errRate > 5 ? 'var(--red)' : errRate > 1 ? 'var(--yellow)' : 'var(--green)';
  }

  set('m_totalReq', (http.totalRequests || 0).toLocaleString());
  set('m_heap', jvm.heapUsedMb ? jvm.heapUsedMb + ' MB' : undefined);

  const heapEl = $('m_heapPct');
  if (heapEl) {
    heapEl.textContent = jvm.heapPercent >= 0 ? jvm.heapPercent + '%' : '—';
    heapEl.style.color = jvm.heapPercent > 85 ? 'var(--red)' : jvm.heapPercent > 70 ? 'var(--yellow)' : 'var(--cyan)';
  }

  // DB pool — show active / max + pending waiters
  const dbActEl = $('m_dbActive');
  if (dbActEl) {
    dbActEl.textContent = db.activeConnections >= 0 ? db.activeConnections : '—';
    const utilPct = db.maxConnections > 0 ? db.activeConnections / db.maxConnections : 0;
    dbActEl.style.color = utilPct > 0.8 ? 'var(--red)' : utilPct > 0.6 ? 'var(--yellow)' : 'var(--green)';
  }
  set('m_dbMax',     db.maxConnections     >= 0 ? db.maxConnections     : undefined);
  set('m_dbPending', db.pendingConnections >= 0 ? db.pendingConnections : 0);

  const cpuEl = $('m_cpu');
  if (cpuEl) {
    cpuEl.textContent = sys.systemCpuPercent >= 0 ? sys.systemCpuPercent + '%' : '—';
    cpuEl.style.color = sys.systemCpuPercent > 80 ? 'var(--red)' : sys.systemCpuPercent > 60 ? 'var(--yellow)' : 'var(--green)';
  }

  set('m_activeDs', d.activeDedicatedServers);

  const uptimeSec = sys.uptimeSeconds || 0;
  const uptimeStr = uptimeSec >= 3600
    ? `${Math.floor(uptimeSec / 3600)}h ${Math.floor((uptimeSec % 3600) / 60)}m`
    : `${Math.floor(uptimeSec / 60)}m ${uptimeSec % 60}s`;
  set('m_uptime', uptimeStr);

  const eps = http.topEndpoints || [];
  const tbl = $('metricsEndpointsTable');
  if (!tbl) return;
  if (!eps.length) { tbl.innerHTML = '<p class="text-muted text-sm">No request data yet.</p>'; return; }

  let html = `<div class="table-wrap"><table>
    <thead><tr>
      <th>Method</th><th>URI</th><th>Count</th><th>Avg (ms)</th><th>Max (ms)</th><th>Errors</th><th>Health</th>
    </tr></thead><tbody>`;
  eps.forEach(ep => {
    const errPct = ep.count > 0 ? (ep.errors / ep.count * 100).toFixed(1) : '0.0';
    const health = ep.avgMs < 100 ? '<span class="badge badge-green">FAST</span>'
                 : ep.avgMs < 500 ? '<span class="badge badge-yellow">OK</span>'
                 : '<span class="badge badge-red">SLOW</span>';
    html += `<tr>
      <td><span class="badge badge-purple">${ep.method || '?'}</span></td>
      <td class="font-mono text-xs">${ep.uri || '?'}</td>
      <td>${(ep.count || 0).toLocaleString()}</td>
      <td>${ep.avgMs}</td>
      <td>${ep.maxMs}</td>
      <td style="color:${ep.errors > 0 ? 'var(--red)' : 'var(--green)'}">${ep.errors || 0} (${errPct}%)</td>
      <td>${health}</td>
    </tr>`;
  });
  html += '</tbody></table></div>';
  tbl.innerHTML = html;
}

// ── Load Test ─────────────────────────────────────────────────────────────────
let _benchRunning = false;

async function runBenchmark() {
  if (_benchRunning) { showAlert('Load test already running', 'warn'); return; }

  const endpoint    = ($('bench-endpoint')    || {}).value || '/api/backend/stats';
  const concurrency = parseInt(($('bench-concurrency') || {}).value || '10');
  const durationMs  = parseInt(($('bench-duration')    || {}).value || '30000');

  _benchRunning = true;
  const btn = $('bench-btn');
  if (btn) { btn.disabled = true; btn.textContent = '\u23F3 Running\u2026'; }

  const progress = $('bench-progress');
  const progressCount = $('bench-progress-count');
  const resultsEl = $('bench-results');
  if (progress) progress.style.display = '';
  if (resultsEl) resultsEl.style.display = 'none';

  const results = [];
  let shouldRun = true;
  let reqCount  = 0;

  const stopTimer = setTimeout(() => { shouldRun = false; }, durationMs);
  const progressInterval = setInterval(() => {
    if (progressCount) progressCount.textContent = reqCount;
  }, 400);

  const startTs = performance.now();

  const worker = async () => {
    while (shouldRun) {
      const t0 = performance.now();
      try {
        const r = await fetch(endpoint, {
          method: 'GET',
          headers: { 'Authorization': `Bearer ${TOKEN}`, 'Content-Type': 'application/json' }
        });
        results.push({ latency: performance.now() - t0, ok: r.ok, status: r.status });
      } catch {
        results.push({ latency: performance.now() - t0, ok: false, status: 0 });
      }
      reqCount++;
    }
  };

  await Promise.all(Array.from({ length: concurrency }, worker));

  clearTimeout(stopTimer);
  clearInterval(progressInterval);
  if (progress) progress.style.display = 'none';

  const elapsed = (performance.now() - startTs) / 1000;
  const total   = results.length;
  const errors  = results.filter(r => !r.ok).length;
  const errRate = total > 0 ? (errors / total * 100).toFixed(1) : '0.0';
  const rps     = (total / elapsed).toFixed(1);

  const lats = results.map(r => r.latency).sort((a, b) => a - b);
  const avg  = lats.length ? (lats.reduce((a, b) => a + b, 0) / lats.length).toFixed(1) : 0;
  const p50  = (lats[Math.floor(lats.length * 0.50)] || 0).toFixed(1);
  const p95  = (lats[Math.floor(lats.length * 0.95)] || 0).toFixed(1);
  const p99  = (lats[Math.floor(lats.length * 0.99)] || 0).toFixed(1);
  const maxL = (lats[lats.length - 1] || 0).toFixed(1);
  const minL = (lats[0] || 0).toFixed(1);

  // Status code breakdown
  const statusMap = {};
  results.forEach(r => { statusMap[r.status] = (statusMap[r.status] || 0) + 1; });
  const statusBreakdown = Object.entries(statusMap)
    .map(([s, c]) => `<span class="badge ${s >= 200 && s < 300 ? 'badge-green' : 'badge-red'}">${s}: ${c}</span>`)
    .join(' ');

  if (resultsEl) {
    resultsEl.style.display = '';
    resultsEl.innerHTML = `
      <div class="stats-grid" style="margin-top:1rem">
        <div class="stat-card"><div class="stat-label">Throughput</div><div class="stat-value text-cyan">${rps}</div><div class="stat-sub">req/sec</div></div>
        <div class="stat-card"><div class="stat-label">Total Requests</div><div class="stat-value">${total.toLocaleString()}</div><div class="stat-sub">${elapsed.toFixed(1)}s &bull; ${concurrency} workers</div></div>
        <div class="stat-card"><div class="stat-label">Avg / Min</div><div class="stat-value text-green">${avg} / ${minL}</div><div class="stat-sub">ms latency</div></div>
        <div class="stat-card"><div class="stat-label">p50 &nbsp;/&nbsp; p95</div><div class="stat-value text-yellow">${p50} / ${p95}</div><div class="stat-sub">ms</div></div>
        <div class="stat-card"><div class="stat-label">p99 &nbsp;/&nbsp; Max</div><div class="stat-value">${p99} / ${maxL}</div><div class="stat-sub">ms</div></div>
        <div class="stat-card"><div class="stat-label">Error Rate</div><div class="stat-value" style="color:${errRate > 5 ? 'var(--red)' : errRate > 0 ? 'var(--yellow)' : 'var(--green)'};">${errRate}%</div><div class="stat-sub">${errors} errors</div></div>
      </div>
      <div class="text-xs text-muted mt-sm" style="line-height:1.8">
        <strong>Endpoint:</strong> ${endpoint} &nbsp;&bull;&nbsp;
        <strong>Status codes:</strong> ${statusBreakdown}
      </div>`;
  }

  if (btn) { btn.disabled = false; btn.textContent = '\u25BA Run Again'; }
  _benchRunning = false;
}
