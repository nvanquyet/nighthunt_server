/* ── DS Sessions Page ───────────────────────────────────────────────────── */
'use strict';

async function renderDsSessions() {
  clearInterval(refreshTimer);
  $('content').innerHTML = `
  <div class="flex justify-between items-center mb-md">
    <span class="text-muted text-xs">Auto-refresh every 10s</span>
    <div class="flex gap-sm">
      <button class="btn btn-primary btn-sm" onclick="dsAllocate()">&#43; Allocate DS</button>
      <button class="btn btn-ghost btn-sm" onclick="renderDsSessions()">&#8635; Refresh</button>
    </div>
  </div>

  <div id="ds-allocate-form" class="card mb-md" style="display:none">
    <div class="card-header"><span class="card-title">&#128640; Allocate New Dedicated Server</span></div>
    <div class="card-body">
      <div class="form-row">
        <div class="form-group">
          <label class="text-xs text-muted">Region</label>
          <input id="ds_region" class="form-control sm" value="vn" placeholder="vn" />
        </div>
        <div class="form-group">
          <label class="text-xs text-muted">Map ID</label>
          <input id="ds_mapId" class="form-control sm" value="map_01" placeholder="map_01" />
        </div>
        <div class="form-group" style="align-self:flex-end;display:flex;gap:8px">
          <button class="btn btn-primary btn-sm" onclick="dsAllocateSubmit()">&#128640; Launch</button>
          <button class="btn btn-ghost btn-sm" onclick="$('ds-allocate-form').style.display='none'">Cancel</button>
        </div>
      </div>
      <div id="ds-allocate-msg" class="text-sm mt-sm"></div>
    </div>
  </div>

  <div class="stats-grid" id="ds-stats">
    <div class="stat-card"><div class="stat-label">Loading...</div><div class="stat-value"><span class="spinner"></span></div></div>
  </div>
  <div class="card mt-md">
    <div class="card-header">
      <span class="card-title">&#128225; Active Containers</span>
      <span id="ds-ts" class="text-muted text-xs"></span>
    </div>
    <div class="table-wrap">
      <table>
        <thead><tr>
          <th>Server ID</th><th>Container</th><th>Map</th><th>Status</th>
          <th class="col-center">Players</th><th>Port</th><th>Region</th>
          <th>Image</th><th>Started</th><th>Last HB</th><th>Actions</th>
        </tr></thead>
        <tbody id="ds-tbody">
          <tr><td colspan="11" style="text-align:center;padding:2rem"><span class="spinner"></span></td></tr>
        </tbody>
      </table>
    </div>
  </div>

  <div class="card mt-md">
    <div class="card-header">
      <span class="card-title">&#128225; Relay Sessions</span>
      <button class="btn btn-ghost btn-xs" onclick="loadRelaySessions()">&#8635; Refresh</button>
    </div>
    <div class="card-body">
      <div id="ds-relay-status" class="text-sm text-muted mb-sm"></div>
      <div id="relay-list"></div>
    </div>
  </div>`;

  await _loadDsSessions();
  await loadRelaySessions();
  refreshTimer = setInterval(async () => {
    await _loadDsSessions();
    await loadRelaySessions();
  }, 10000);
}

async function _loadDsSessions() {
  try {
    const r = await api('GET', '/api/admin/ds/servers');
    const list = Array.isArray(r.data || r) ? (r.data || r) : [];

    const inGame   = list.filter(s => s.status === 'in_game').length;
    const ready    = list.filter(s => s.status === 'ready').length;
    const starting = list.filter(s => s.status === 'starting').length;
    const totalPl  = list.reduce((acc, s) => acc + (s.currentPlayers || 0), 0);

    if (!$('ds-stats')) return;
    $('ds-stats').innerHTML = `
    <div class="stat-card"><div class="stat-label">Active Containers</div><div class="stat-value" style="color:var(--cyan)">${list.length}</div><div class="stat-sub">${starting} starting</div></div>
    <div class="stat-card"><div class="stat-label">In-Game</div><div class="stat-value" style="color:var(--green)">${inGame}</div><div class="stat-sub">Active matches</div></div>
    <div class="stat-card"><div class="stat-label">Ready</div><div class="stat-value" style="color:var(--yellow)">${ready}</div><div class="stat-sub">Waiting for players</div></div>
    <div class="stat-card"><div class="stat-label">Players in DS</div><div class="stat-value" style="color:var(--purple)">${totalPl}</div><div class="stat-sub">Across all sessions</div></div>`;

    const dsStatus = s => {
      if (s === 'in_game')  return '<span class="badge badge-cyan">IN GAME</span>';
      if (s === 'ready')    return '<span class="badge badge-green">READY</span>';
      if (s === 'starting') return '<span class="badge badge-yellow">STARTING</span>';
      if (s === 'stopped')  return '<span class="badge badge-muted">STOPPED</span>';
      return `<span class="badge badge-muted">${s}</span>`;
    };

    if (!$('ds-tbody')) return;
    $('ds-tbody').innerHTML = list.length ? list.map(s => `<tr>
      <td class="font-mono text-xs text-cyan">${(s.serverId || '').substring(0, 8)}&hellip;</td>
      <td class="font-mono text-xs text-muted">${(s.dockerContainerId || 'local').substring(0, 12)}</td>
      <td><span class="badge badge-muted text-xs">${s.mapId || '—'}</span></td>
      <td>${dsStatus(s.status)}</td>
      <td class="col-center"><strong>${s.currentPlayers || 0}</strong><span class="text-muted">/${s.maxPlayers || 16}</span></td>
      <td class="font-mono">${s.port || '—'}</td>
      <td>${s.region || '—'}</td>
      <td class="text-xs text-muted truncate" style="max-width:140px" title="${s.imageTag || ''}">${(s.imageTag || '—').split(':').pop()}</td>
      <td class="text-xs text-muted">${fmtDate(s.startedAt)}</td>
      <td class="text-xs" style="color:${s.lastHeartbeatAt ? 'var(--green)' : 'var(--red)'}">${s.lastHeartbeatAt ? fmtDate(s.lastHeartbeatAt) : 'No HB'}</td>
      <td style="display:flex;gap:4px;flex-wrap:wrap">
        ${s.status !== 'stopped' ? `<button class="btn btn-xs" style="background:var(--red);color:#fff;border:none" onclick="dsTerminate('${s.serverId}')">&#9209; Stop</button>` : ''}
        <button class="btn btn-ghost btn-xs" onclick="dsViewLogs('${s.dockerContainerId || ''}','${s.serverId}')">&#128196; Logs</button>
      </td>
    </tr>`).join('') : '<tr><td colspan="11" style="text-align:center;color:var(--muted);padding:2.5rem">No active DS containers</td></tr>';

    const ts = $('ds-ts');
    if (ts) ts.textContent = 'Updated ' + new Date().toLocaleTimeString();
  } catch (e) {
    if ($('ds-tbody')) $('ds-tbody').innerHTML = `<tr><td colspan="11" class="text-red" style="padding:1rem">${e.message}</td></tr>`;
  }
}

function dsAllocate() {
  const form = $('ds-allocate-form');
  if (form) form.style.display = form.style.display === 'none' ? 'block' : 'none';
}

async function dsAllocateSubmit() {
  const region = ($('ds_region') || {}).value || 'vn';
  const mapId  = ($('ds_mapId')  || {}).value || 'map_01';
  const msg    = $('ds-allocate-msg');
  if (msg) { msg.textContent = 'Launching…'; msg.className = 'text-sm mt-sm text-muted'; }
  try {
    const json = await api('POST', '/api/admin/ds/allocate', { region, mapId });
    const d = json.data || json;
    if (msg) {
      msg.textContent = `\u2705 Allocated: ${d.serverId} (port ${d.port})`;
      msg.className = 'text-sm mt-sm text-green';
    }
    setTimeout(() => {
      if ($('ds-allocate-form')) $('ds-allocate-form').style.display = 'none';
      _loadDsSessions();
    }, 1500);
  } catch (e) {
    if (msg) { msg.textContent = '\u274C ' + e.message; msg.className = 'text-sm mt-sm text-red'; }
  }
}

async function dsTerminate(serverId) {
  if (!confirm(`Terminate DS ${serverId}?\n\nPlayers in the match will receive a notification and be returned to home.`)) return;
  const reason = prompt('Reason for termination (shown to players):', 'Scheduled maintenance') || '';
  try {
    await api('POST', `/api/admin/ds/terminate/${encodeURIComponent(serverId)}`, { reason });
    showAlert(`\u2705 Terminated: ${serverId}`, 'success');
    _loadDsSessions();
  } catch (e) {
    showAlert('\u274C ' + e.message, 'error');
  }
}

async function dsViewLogs(containerId, serverId) {
  if (!containerId || containerId === 'null' || containerId === 'local') {
    showAlert('No container ID available for this server.', 'warn'); return;
  }
  try {
    const json = await api('GET', `/api/admin/ds/logs/${encodeURIComponent(containerId)}`, null, { tail: 200 });
    const d = json.data || json;
    const w = window.open('', '_blank', 'width=900,height=600');
    w.document.write(`<html><head><title>Logs \u2014 ${serverId}</title></head><body style="background:#1a1a2e;color:#e0e0e0;font-family:monospace;padding:16px;white-space:pre-wrap;font-size:12px;">${(d.logs || '(no logs)').replace(/</g, '&lt;')}</body></html>`);
  } catch (e) {
    showAlert('Error: ' + e.message, 'error');
  }
}

async function loadRelaySessions() {
  const statusEl = $('ds-relay-status');
  const listEl   = $('relay-list');
  if (!listEl) return;
  try {
    const hJson = await api('GET', '/relay/health');
    const h = hJson.data || hJson;
    if (statusEl) {
      statusEl.innerHTML = `Mode: <strong>${h.mode || '—'}</strong> &nbsp;|&nbsp; `
        + `Status: <strong style="color:${h.status === 'ok' ? 'var(--green)' : 'var(--red)'}">${h.status || '—'}</strong> &nbsp;|&nbsp; `
        + `Active sessions: <strong>${h.sessions != null ? h.sessions : '—'}</strong>`;
    }
    listEl.innerHTML = `<p class="text-sm text-muted">Session details require ROLE_ADMIN JWT (auto-attached). `
      + `Health shown above. <a href="/relay/health" target="_blank" style="color:var(--cyan)">Open /relay/health \u2197</a></p>`;
  } catch (e) {
    if (listEl) listEl.innerHTML = `<p class="text-red text-sm">Error: ${e.message}</p>`;
  }
}
