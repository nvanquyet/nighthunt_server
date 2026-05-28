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
      <span class="text-muted text-xs">Auto-refresh 15s</span>
    </div>
    <div class="card-body">
      <div class="stats-grid" style="margin-bottom:1rem">
        <div class="stat-card"><div class="stat-label">Throughput</div><div class="stat-value text-cyan" id="m_throughput">—</div><div class="stat-sub">req/min</div></div>
        <div class="stat-card"><div class="stat-label">Avg Latency</div><div class="stat-value text-green" id="m_avgLatency">—</div><div class="stat-sub">ms</div></div>
        <div class="stat-card"><div class="stat-label">p95 Latency</div><div class="stat-value text-yellow" id="m_p95">—</div><div class="stat-sub">ms</div></div>
        <div class="stat-card"><div class="stat-label">p99 Latency</div><div class="stat-value" id="m_p99">—</div><div class="stat-sub">ms</div></div>
        <div class="stat-card"><div class="stat-label">Error Rate</div><div class="stat-value" id="m_errorRate">—</div><div class="stat-sub">4xx+5xx</div></div>
        <div class="stat-card"><div class="stat-label">Total Requests</div><div class="stat-value text-muted" id="m_totalReq">—</div><div class="stat-sub">all time</div></div>
        <div class="stat-card"><div class="stat-label">JVM Heap</div><div class="stat-value text-purple" id="m_heap">—</div><div class="stat-sub"><span id="m_heapPct">—</span> used</div></div>
        <div class="stat-card"><div class="stat-label">DB Connections</div><div class="stat-value" id="m_dbActive">—</div><div class="stat-sub">active</div></div>
        <div class="stat-card"><div class="stat-label">CPU</div><div class="stat-value" id="m_cpu">—</div><div class="stat-sub">system</div></div>
        <div class="stat-card"><div class="stat-label">Active DS</div><div class="stat-value text-cyan" id="m_activeDs">—</div><div class="stat-sub">containers</div></div>
        <div class="stat-card"><div class="stat-label">Uptime</div><div class="stat-value text-green" id="m_uptime">—</div><div class="stat-sub">backend</div></div>
      </div>
      <div id="metricsEndpointsTable"><span class="spinner"></span></div>
    </div>
  </div>`;

  await loadSystem();
  await loadMetrics();
  refreshTimer = setInterval(loadSystem, 10000);
  _metricsTimer = setInterval(loadMetrics, 15000);
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
let _metricsTimer = null;

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

  set('m_throughput', http.throughputPerMin);
  set('m_avgLatency', http.avgLatencyMs);
  set('m_p95',        http.p95LatencyMs);
  set('m_p99',        http.p99LatencyMs);

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

  set('m_dbActive', db.activeConnections);

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
