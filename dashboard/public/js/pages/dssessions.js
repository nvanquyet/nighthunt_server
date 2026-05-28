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
  </div>

  <div class="card mt-md">
    <div class="card-header">
      <span class="card-title">&#128202; DS Server History</span>
      <button class="btn btn-ghost btn-xs" onclick="loadDsHistory()">&#128196; Load Stopped Servers</button>
    </div>
    <div id="ds-history-body" class="table-wrap">
      <p class="text-muted text-sm" style="padding:1rem">Click \"Load Stopped Servers\" to view terminated DS records.</p>
    </div>
  </div>

  <div class="card mt-md">
    <div class="card-header">
      <span class="card-title">&#128203; Match History</span>
      <div class="flex gap-sm items-center">
        <select id="mh-status-filter" class="form-control sm" style="width:auto;padding:2px 8px;font-size:11px">
          <option value="">All statuses</option>
          <option value="finished">Finished</option>
          <option value="aborted">Aborted</option>
          <option value="in_progress">In Progress</option>
        </select>
        <button class="btn btn-ghost btn-xs" onclick="loadMatchHistory(0)">&#128196; Load</button>
      </div>
    </div>
    <div id="ds-match-history-body">
      <p class="text-muted text-sm" style="padding:1rem">Click \"Load\" to fetch recent match history.</p>
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
async function loadDsHistory() {
  const body = $('ds-history-body');
  if (!body) return;
  body.innerHTML = '<p class="text-muted text-sm" style="padding:1rem"><span class="spinner"></span> Loading\u2026</p>';
  try {
    const r = await api('GET', '/api/admin/ds/servers', null, { status: 'stopped' });
    const list = Array.isArray(r.data || r) ? (r.data || r) : [];
    if (!list.length) {
      body.innerHTML = '<p class="text-muted text-sm" style="padding:1rem">No stopped DS servers found.</p>';
      return;
    }
    const dsStatus = s => {
      if (s === 'stopped') return '<span class="badge badge-muted">STOPPED</span>';
      return `<span class="badge badge-muted">${s}</span>`;
    };
    body.innerHTML = `<table><thead><tr>
      <th>Server ID</th><th>Container</th><th>Map</th><th>Status</th>
      <th>Region</th><th>Port</th><th>Image</th><th>Started</th><th>Stopped</th>
    </tr></thead><tbody>${list.map(s => `<tr>
      <td class="font-mono text-xs text-cyan">${(s.serverId||'').substring(0,8)}&hellip;</td>
      <td class="font-mono text-xs text-muted">${(s.dockerContainerId||'local').substring(0,12)}</td>
      <td><span class="badge badge-muted text-xs">${s.mapId||'\u2014'}</span></td>
      <td>${dsStatus(s.status)}</td>
      <td>${s.region||'\u2014'}</td>
      <td class="font-mono">${s.port||'\u2014'}</td>
      <td class="text-xs text-muted">${(s.imageTag||'\u2014').split(':').pop()}</td>
      <td class="text-xs text-muted">${fmtDate(s.startedAt)}</td>
      <td class="text-xs text-muted">${fmtDate(s.stoppedAt)}</td>
    </tr>`).join('')}</tbody></table>`;
  } catch (e) {
    body.innerHTML = `<p class="text-red text-sm" style="padding:1rem">Error: ${e.message}</p>`;
  }
}

let _mhPage = 0;
async function loadMatchHistory(page = 0) {
  _mhPage = page;
  const body = $('ds-match-history-body');
  if (!body) return;
  body.innerHTML = '<p class="text-muted text-sm" style="padding:1rem"><span class="spinner"></span> Loading\u2026</p>';
  const statusFilter = ($('mh-status-filter') || {}).value || '';
  try {
    const params = { page, size: 20 };
    if (statusFilter) params.status = statusFilter;
    const r = await api('GET', '/api/admin/ds/match-history', null, params);
    const d = r.data || r;
    const matches = d.matches || [];
    const total = d.total || 0;
    const totalPages = Math.ceil(total / 20);

    const modeColor = m => ({
      'battle_royale': 'badge-cyan', 'team_deathmatch': 'badge-green',
      'capture_flag': 'badge-yellow', 'survival': 'badge-purple'
    })[m] || 'badge-muted';
    const statusBadge = s => ({
      'finished':    '<span class="badge badge-green">FINISHED</span>',
      'aborted':     '<span class="badge badge-red">ABORTED</span>',
      'in_progress': '<span class="badge badge-yellow">LIVE</span>'
    })[s] || `<span class="badge badge-muted">${s||'?'}</span>`;

    const rows = matches.map(m => {
      const dur = m.durationSeconds
        ? (m.durationSeconds >= 60
          ? `${Math.floor(m.durationSeconds/60)}m ${m.durationSeconds%60}s`
          : `${m.durationSeconds}s`)
        : '\u2014';
      return `<tr>
        <td class="font-mono text-xs text-cyan">${(m.matchId||'').substring(0,8)}&hellip;</td>
        <td class="font-mono text-xs text-muted">${(m.roomId||'\u2014').substring(0,8)}</td>
        <td>${statusBadge(m.status)}</td>
        <td><span class="badge ${modeColor(m.gameMode)} text-xs">${m.gameMode||'\u2014'}</span></td>
        <td>${m.winnerTeamId!=null ? '<span class="text-green">Team '+m.winnerTeamId+'</span>' : '\u2014'}</td>
        <td class="text-xs">${m.endReason||'\u2014'}</td>
        <td class="text-xs text-muted">${dur}</td>
        <td class="text-xs text-muted">${fmtDate(m.startedAt)}</td>
        <td><button class="btn btn-ghost btn-xs" onclick="dsViewMatchDetail('${m.matchId}')">&#128269; Detail</button></td>
      </tr>`;
    }).join('');

    body.innerHTML = `
      <div class="flex justify-between items-center" style="padding:0.5rem 1rem;font-size:11px;color:var(--muted)">
        <span>${total} total matches &mdash; page ${page+1} of ${totalPages||1}</span>
        <div class="flex gap-sm">
          ${page > 0 ? `<button class="btn btn-ghost btn-xs" onclick="loadMatchHistory(${page-1})">&laquo; Prev</button>` : ''}
          ${page < totalPages-1 ? `<button class="btn btn-ghost btn-xs" onclick="loadMatchHistory(${page+1})">Next &raquo;</button>` : ''}
        </div>
      </div>
      <div class="table-wrap">
        <table><thead><tr>
          <th>Match ID</th><th>Room ID</th><th>Status</th><th>Mode</th>
          <th>Winner</th><th>End Reason</th><th>Duration</th><th>Started</th><th></th>
        </tr></thead><tbody>${rows||'<tr><td colspan="9" style="text-align:center;color:var(--muted);padding:2rem">No matches found</td></tr>'}</tbody></table>
      </div>`;
  } catch (e) {
    body.innerHTML = `<p class="text-red text-sm" style="padding:1rem">Error: ${e.message}</p>`;
  }
}

async function dsViewMatchDetail(matchId) {
  try {
    const r = await api('GET', `/api/admin/ds/match-detail/${encodeURIComponent(matchId)}`);
    const d = r.data || r;
    const w = window.open('', '_blank', 'width=920,height=680');
    const players = (d.players || []).map(p => `
      <tr>
        <td>${p.userId||'\u2014'}</td><td>${p.username||'\u2014'}</td>
        <td>${p.teamId!=null?'Team '+p.teamId:'\u2014'}</td>
        <td>${p.kills??'\u2014'}</td><td>${p.deaths??'\u2014'}</td>
        <td>${p.assists??'\u2014'}</td><td>${p.score??'\u2014'}</td>
        <td>${p.placement??'\u2014'}</td>
      </tr>`).join('');
    w.document.write(`<html><head><title>Match \u2014 ${matchId}</title>
      <style>body{background:#12122a;color:#e0e0e0;font-family:monospace;padding:20px;font-size:13px}
      table{border-collapse:collapse;width:100%}th,td{border:1px solid #333;padding:6px 10px;text-align:left}
      th{background:#1e1e3a;color:#aaa}tr:nth-child(even){background:#1a1a2e}</style></head>
      <body><h3>Match Detail \u2014 ${matchId}</h3>
      <p>Status: <strong>${d.status||'?'}</strong> | Mode: <strong>${d.gameMode||'?'}</strong> | End: ${d.endReason||'?'}</p>
      <table><thead><tr><th>User ID</th><th>Username</th><th>Team</th><th>Kills</th><th>Deaths</th><th>Assists</th><th>Score</th><th>Placement</th></tr></thead>
      <tbody>${players||'<tr><td colspan="8">No player data</td></tr>'}</tbody></table></body></html>`);
  } catch (e) {
    showAlert('Error loading match detail: ' + e.message, 'error');
  }
}