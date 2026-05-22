/* ── DS Sessions Page ───────────────────────────────────────────────────── */
'use strict';

async function renderDsSessions() {
  clearInterval(refreshTimer);
  $('content').innerHTML = `
  <div class="flex justify-between items-center mb-md">
    <div>
      <span class="text-muted text-xs">Auto-refresh every 10s</span>
    </div>
    <button class="btn btn-ghost btn-sm" onclick="renderDsSessions()">&#8635; Refresh</button>
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
          <th>Image</th><th>Started</th><th>Last HB</th>
        </tr></thead>
        <tbody id="ds-tbody">
          <tr><td colspan="10" style="text-align:center;padding:2rem"><span class="spinner"></span></td></tr>
        </tbody>
      </table>
    </div>
  </div>`;

  await _loadDsSessions();
  refreshTimer = setInterval(_loadDsSessions, 10000);
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
      return `<span class="badge badge-muted">${s}</span>`;
    };

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
    </tr>`).join('') : '<tr><td colspan="10" style="text-align:center;color:var(--muted);padding:2.5rem">No active DS containers</td></tr>';

    const ts = $('ds-ts');
    if (ts) ts.textContent = 'Updated ' + new Date().toLocaleTimeString();
  } catch (e) {
    if ($('ds-tbody')) $('ds-tbody').innerHTML = `<tr><td colspan="10" class="text-red" style="padding:1rem">${e.message}</td></tr>`;
  }
}
