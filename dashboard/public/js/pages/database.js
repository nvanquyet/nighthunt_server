/* ═══════════════════════════════════════════════════════════════════════
   NightHunt Admin Dashboard — Database Monitor Page
   Tabs: Stats Overview | Browse Rooms | Browse Matches | Cleanup
   ═══════════════════════════════════════════════════════════════════════ */
'use strict';

let dbTab = 'stats';
let dbRoomsPage = 0, dbRoomsStatus = '';
let dbMatchPage = 0, dbMatchStatus = '', dbMatchMode = '';

/* ── Entry point (mapped as nav('dbclean')) ─────────────────────────────── */
async function renderDbCleanup() {
  clearInterval(refreshTimer);
  $('content').innerHTML = `
  <div class="tab-bar" id="db-tabs">
    <button class="tab-btn" data-tab="stats"   onclick="switchDbTab('stats')">&#128202; Stats Overview</button>
    <button class="tab-btn" data-tab="rooms"   onclick="switchDbTab('rooms')">&#128682; Browse Rooms</button>
    <button class="tab-btn" data-tab="matches" onclick="switchDbTab('matches')">&#128296; Browse Matches</button>
    <button class="tab-btn" data-tab="cleanup" onclick="switchDbTab('cleanup')">&#128465; Cleanup</button>
  </div>

  <!-- Stats tab -->
  <div class="tab-panel" id="dbpanel-stats">
    <div class="flex justify-between items-center mb-md">
      <span class="text-muted text-xs">Database health at a glance</span>
      <button class="btn btn-ghost btn-sm" onclick="loadDbStats()">&#8635; Refresh</button>
    </div>
    <div class="stats-grid mb-md" id="db-stat-cards">
      <div class="stat-card"><div class="stat-label">Loading...</div><div class="stat-value"><span class="spinner"></span></div></div>
    </div>
    <div class="grid-2 mt-md" id="db-stat-charts"></div>
    <div id="db-stat-warnings" class="mt-md"></div>
  </div>

  <!-- Rooms tab -->
  <div class="tab-panel" id="dbpanel-rooms">
    <div class="form-row mb-md">
      <div class="form-group">
        <label class="form-label">Filter by Status</label>
        <select class="form-control sm" onchange="dbRoomsStatus=this.value;dbRoomsPage=0;loadDbRooms()">
          <option value="">All Statuses</option>
          <option value="WAITING">WAITING</option>
          <option value="IN_GAME">IN_GAME</option>
          <option value="CLOSED">CLOSED</option>
          <option value="FINISHED">FINISHED</option>
        </select>
      </div>
      <div class="form-group" style="align-self:flex-end">
        <button class="btn btn-ghost btn-sm" onclick="loadDbRooms()">&#8635; Refresh</button>
      </div>
    </div>
    <div class="card">
      <div class="table-wrap">
        <table>
          <thead><tr>
            <th>ID</th><th>Code</th><th>Status</th><th>Mode</th><th>Owner</th>
            <th class="col-center">Players</th><th class="col-center">Any Online</th>
            <th>Visibility</th><th>Created</th>
          </tr></thead>
          <tbody id="db-rooms-tbody">
            <tr><td colspan="9" style="text-align:center;padding:2rem"><span class="spinner"></span></td></tr>
          </tbody>
        </table>
      </div>
      <div id="db-rooms-pager"></div>
    </div>
  </div>

  <!-- Matches tab -->
  <div class="tab-panel" id="dbpanel-matches">
    <div class="form-row mb-md">
      <div class="form-group">
        <label class="form-label">Status</label>
        <select class="form-control sm" onchange="dbMatchStatus=this.value;dbMatchPage=0;loadDbMatches()">
          <option value="">All Statuses</option>
          <option value="LOBBY">LOBBY</option>
          <option value="IN_GAME">IN_GAME</option>
          <option value="FINISHED">FINISHED</option>
          <option value="ABANDONED">ABANDONED</option>
        </select>
      </div>
      <div class="form-group">
        <label class="form-label">Mode</label>
        <select class="form-control sm" onchange="dbMatchMode=this.value;dbMatchPage=0;loadDbMatches()">
          <option value="">All Modes</option>
          <option value="TEAM_DEATHMATCH">TEAM_DEATHMATCH</option>
          <option value="FREE_FOR_ALL">FREE_FOR_ALL</option>
        </select>
      </div>
      <div class="form-group" style="align-self:flex-end">
        <button class="btn btn-ghost btn-sm" onclick="loadDbMatches()">&#8635; Refresh</button>
      </div>
    </div>
    <div class="card">
      <div class="table-wrap">
        <table>
          <thead><tr>
            <th>Match ID</th><th>Mode</th><th>Status</th><th>End Reason</th>
            <th>Winner</th><th>Duration</th><th>Created</th>
          </tr></thead>
          <tbody id="db-matches-tbody">
            <tr><td colspan="7" style="text-align:center;padding:2rem"><span class="spinner"></span></td></tr>
          </tbody>
        </table>
      </div>
      <div id="db-matches-pager"></div>
    </div>
  </div>

  <!-- Cleanup tab -->
  <div class="tab-panel" id="dbpanel-cleanup">
    <div id="cleanup-preview">
      <div style="text-align:center;padding:3rem"><span class="spinner"></span></div>
    </div>
  </div>`;

  switchDbTab(dbTab);
}

/* ── Tab switching ──────────────────────────────────────────────────────── */
function switchDbTab(tab) {
  dbTab = tab;
  document.querySelectorAll('#db-tabs .tab-btn').forEach(b => {
    b.classList.toggle('active', b.dataset.tab === tab);
  });
  document.querySelectorAll('[id^="dbpanel-"]').forEach(p => p.classList.remove('active'));
  const panel = $('dbpanel-' + tab);
  if (panel) panel.classList.add('active');

  if (tab === 'stats')   loadDbStats();
  if (tab === 'rooms')   loadDbRooms();
  if (tab === 'matches') loadDbMatches();
  if (tab === 'cleanup') loadCleanupPreview();
}

/* ── Stats Tab ──────────────────────────────────────────────────────────── */
async function loadDbStats() {
  try {
    const d = await api('GET', '/api/admin/db-stats');

    const roomsBy = d.roomsByStatus || {};
    const matchBy = d.matchesByStatus || {};
    const totalRooms   = Object.values(roomsBy).reduce((a, v) => a + v, 0);
    const totalMatches = Object.values(matchBy).reduce((a, v) => a + v, 0);

    $('db-stat-cards').innerHTML = `
    <div class="stat-card"><div class="stat-label">Total Rooms (DB)</div><div class="stat-value" style="color:var(--cyan)">${fmt(totalRooms)}</div><div class="stat-sub">All statuses</div></div>
    <div class="stat-card"><div class="stat-label">Total Matches (DB)</div><div class="stat-value" style="color:var(--purple)">${fmt(totalMatches)}</div><div class="stat-sub">All statuses</div></div>
    <div class="stat-card"><div class="stat-label">Active Room Players</div><div class="stat-value" style="color:var(--green)">${fmt(d.totalRoomPlayers)}</div><div class="stat-sub">room_players table</div></div>
    <div class="stat-card"><div class="stat-label">Online Sessions</div><div class="stat-value" style="color:var(--green)">${fmt(d.onlineSessions)}</div><div class="stat-sub">Redis sessions</div></div>
    <div class="stat-card ${d.staleRooms > 0 ? 'border-yellow' : ''}" style="${d.staleRooms > 0 ? 'border-color:var(--yellow)' : ''}">
      <div class="stat-label">Stale Rooms (empty)</div>
      <div class="stat-value" style="color:${d.staleRooms > 0 ? 'var(--yellow)' : 'var(--green)'}">${fmt(d.staleRooms)}</div>
      <div class="stat-sub">Active with 0 DB players</div>
    </div>
    <div class="stat-card ${d.ghostRooms > 0 ? 'border-red' : ''}" style="${d.ghostRooms > 0 ? 'border-color:var(--red)' : ''}">
      <div class="stat-label">Ghost Rooms</div>
      <div class="stat-value" style="color:${d.ghostRooms > 0 ? 'var(--red)' : 'var(--green)'}">${fmt(d.ghostRooms)}</div>
      <div class="stat-sub">Active, players all offline</div>
    </div>
    <div class="stat-card ${d.stuckMatches > 0 ? 'border-red' : ''}" style="${d.stuckMatches > 0 ? 'border-color:var(--red)' : ''}">
      <div class="stat-label">Stuck Matches (&gt;2h)</div>
      <div class="stat-value" style="color:${d.stuckMatches > 0 ? 'var(--red)' : 'var(--green)'}">${fmt(d.stuckMatches)}</div>
      <div class="stat-sub">LOBBY/IN_GAME &gt; 2h old</div>
    </div>`;

    // Status breakdown tables
    $('db-stat-charts').innerHTML = `
    <div class="card">
      <div class="card-header"><span class="card-title">&#128682; Rooms by Status</span></div>
      <div class="card-body">
        ${_statusTable(roomsBy, totalRooms, {
      WAITING: 'badge-yellow', IN_GAME: 'badge-cyan', CLOSED: 'badge-muted', FINISHED: 'badge-muted'
    })}
      </div>
    </div>
    <div class="card">
      <div class="card-header"><span class="card-title">&#128296; Matches by Status</span></div>
      <div class="card-body">
        ${_statusTable(matchBy, totalMatches, {
      LOBBY: 'badge-yellow', IN_GAME: 'badge-cyan', FINISHED: 'badge-muted', ABANDONED: 'badge-red'
    })}
      </div>
    </div>`;

    // Warnings
    const warnings = [];
    if (d.ghostRooms > 0) warnings.push({ cls: 'danger-banner', icon: '&#128680;', msg: `<strong>${d.ghostRooms} ghost room(s)</strong> detected — rooms with players but <em>no one online</em>. Run cleanup to close them.` });
    if (d.staleRooms > 0) warnings.push({ cls: 'warning-banner', icon: '&#9888;', msg: `<strong>${d.staleRooms} stale room(s)</strong> found — active rooms with zero DB players. These can be safely closed.` });
    if (d.stuckMatches > 0) warnings.push({ cls: 'danger-banner', icon: '&#9888;', msg: `<strong>${d.stuckMatches} stuck match(es)</strong> — have been in LOBBY/IN_GAME for over 2 hours.` });

    $('db-stat-warnings').innerHTML = warnings.map(w =>
      `<div class="${w.cls}"><span class="icon">${w.icon}</span><div>${w.msg}${(w.cls.includes('danger') && adminRole === 'root')
        ? `  <button class="btn btn-danger btn-xs" style="margin-left:1rem" onclick="switchDbTab('cleanup')">Go to Cleanup &#8594;</button>`
        : ''}</div></div>`
    ).join('') + (warnings.length === 0
      ? `<div class="warning-banner" style="background:var(--green-dim);border-color:rgba(0,255,136,.3)"><span class="icon">&#10003;</span><div><strong style="color:var(--green)">Database looks clean!</strong> No stale or ghost data detected.</div></div>`
      : '');

  } catch (e) {
    $('db-stat-cards').innerHTML = `<div class="card card-body text-red">Error: ${e.message}</div>`;
  }
}

function _statusTable(obj, total, classMap) {
  const rows = Object.entries(obj);
  if (!rows.length) return '<p class="text-muted">No data</p>';
  return rows.map(([k, v]) => {
    const pct = total > 0 ? Math.round(v / total * 100) : 0;
    return `
    <div class="flex items-center gap-md" style="margin-bottom:.6rem">
      <span class="badge ${classMap[k] || 'badge-muted'}" style="min-width:80px;text-align:center">${k}</span>
      <div class="health-bar" style="flex:1">
        <div class="health-bar-fill ${k.includes('GAME') ? 'green' : k === 'WAITING' || k === 'LOBBY' ? 'yellow' : 'red'}" style="width:${pct}%"></div>
      </div>
      <span class="font-bold" style="min-width:2.5rem;text-align:right">${fmt(v)}</span>
      <span class="text-muted text-xs" style="min-width:2.5rem;text-align:right">${pct}%</span>
    </div>`;
  }).join('');
}

/* ── Rooms Tab ──────────────────────────────────────────────────────────── */
async function loadDbRooms(page) {
  if (page !== undefined) dbRoomsPage = page;
  const tbody = $('db-rooms-tbody');
  if (tbody) tbody.innerHTML = '<tr><td colspan="9" style="text-align:center;padding:2rem"><span class="spinner"></span></td></tr>';
  try {
    const d = await api('GET', '/api/admin/rooms/all', null,
      { page: dbRoomsPage, size: 20, status: dbRoomsStatus || null });
    $('db-rooms-tbody').innerHTML = (d.content || []).map(r => `
    <tr>
      <td data-label="ID" class="text-muted">#${r.id}</td>
      <td data-label="Code"><code class="text-cyan">${r.roomCode || '—'}</code></td>
      <td data-label="Status">${statusBadge(r.status)}</td>
      <td data-label="Mode" class="text-sm">${r.mode || '—'}</td>
      <td data-label="Owner" class="font-bold">${r.ownerName || '?'} <span class="text-muted text-xs">#${r.ownerId}</span></td>
      <td data-label="Players" class="col-center font-bold">${r.playerCount}</td>
      <td data-label="Any Online" class="col-center">
        ${r.anyOnline
        ? '<span class="badge badge-green">YES</span>'
        : r.playerCount > 0 ? '<span class="badge badge-red">NO</span>' : '<span class="badge badge-muted">—</span>'}
      </td>
      <td data-label="Visibility">${r.isPublic ? '<span class="badge badge-cyan">Public</span>' : '<span class="badge badge-muted">Private</span>'}</td>
      <td data-label="Created" class="text-xs text-muted">${fmtDate(r.createdAt)}</td>
    </tr>`).join('') || '<tr><td colspan="9" style="text-align:center;color:var(--muted);padding:2rem">No rooms found</td></tr>';
    renderPager('db-rooms-pager', d, p => loadDbRooms(p));
  } catch (e) {
    if ($('db-rooms-tbody')) $('db-rooms-tbody').innerHTML = `<tr><td colspan="9" class="text-red" style="padding:1rem">${e.message}</td></tr>`;
  }
}

/* ── Matches Tab ────────────────────────────────────────────────────────── */
async function loadDbMatches(page) {
  if (page !== undefined) dbMatchPage = page;
  const tbody = $('db-matches-tbody');
  if (tbody) tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;padding:2rem"><span class="spinner"></span></td></tr>';
  try {
    const d = await api('GET', '/api/admin/matches', null,
      { page: dbMatchPage, size: 20, status: dbMatchStatus || null, mode: dbMatchMode || null });
    $('db-matches-tbody').innerHTML = (d.content || []).map(m => {
      const dur = m.durationSecs
        ? Math.floor(m.durationSecs / 60) + 'm ' + (m.durationSecs % 60) + 's'
        : '—';
      return `<tr>
        <td data-label="Match ID" class="font-mono text-xs text-muted" title="${m.matchId}">${(m.matchId || '').substring(0, 12)}&hellip;</td>
        <td data-label="Mode"><span class="badge badge-purple">${m.gameMode || '?'}</span></td>
        <td data-label="Status">${statusBadge(m.status)}</td>
        <td data-label="End Reason" class="text-sm text-muted">${m.endReason || '—'}</td>
        <td data-label="Winner" class="text-green">Team ${m.winnerTeamId != null ? m.winnerTeamId : '?'}</td>
        <td data-label="Duration" class="text-sm text-muted">${dur}</td>
        <td data-label="Created" class="text-xs text-muted">${fmtDate(m.createdAt || m.startedAt)}</td>
      </tr>`;
    }).join('') || '<tr><td colspan="7" style="text-align:center;color:var(--muted);padding:2rem">No matches found</td></tr>';
    renderPager('db-matches-pager', d, p => loadDbMatches(p));
  } catch (e) {
    if ($('db-matches-tbody')) $('db-matches-tbody').innerHTML = `<tr><td colspan="7" class="text-red" style="padding:1rem">${e.message}</td></tr>`;
  }
}

/* ── Cleanup Tab ────────────────────────────────────────────────────────── */
async function loadCleanupPreview() {
  const preview = $('cleanup-preview');
  if (!preview) return;
  preview.innerHTML = `<div style="text-align:center;padding:2rem"><span class="spinner"></span></div>`;

  try {
    const d = await api('GET', '/api/admin/db-stats');

    const staleTotal = (d.staleRooms || 0) + (d.ghostRooms || 0) + (d.stuckMatches || 0);

    preview.innerHTML = `
    <div class="card mb-md">
      <div class="card-header"><span class="card-title">&#128269; Pre-Cleanup Snapshot</span></div>
      <div class="card-body">
        <div class="stats-grid" style="grid-template-columns:repeat(3,1fr)">
          <div class="stat-card" style="${d.staleRooms > 0 ? 'border-color:var(--yellow)' : ''}">
            <div class="stat-label">Empty Active Rooms</div>
            <div class="stat-value" style="color:${d.staleRooms > 0 ? 'var(--yellow)' : 'var(--green)'}">${d.staleRooms}</div>
            <div class="stat-sub">Active, 0 DB players</div>
          </div>
          <div class="stat-card" style="${d.ghostRooms > 0 ? 'border-color:var(--red)' : ''}">
            <div class="stat-label">Ghost Rooms</div>
            <div class="stat-value" style="color:${d.ghostRooms > 0 ? 'var(--red)' : 'var(--green)'}">${d.ghostRooms}</div>
            <div class="stat-sub">No players online</div>
          </div>
          <div class="stat-card" style="${d.stuckMatches > 0 ? 'border-color:var(--red)' : ''}">
            <div class="stat-label">Stuck Matches</div>
            <div class="stat-value" style="color:${d.stuckMatches > 0 ? 'var(--red)' : 'var(--green)'}">${d.stuckMatches}</div>
            <div class="stat-sub">LOBBY/IN_GAME &gt; 2h</div>
          </div>
        </div>
      </div>
    </div>

    ${staleTotal === 0
        ? `<div class="warning-banner" style="background:var(--green-dim);border-color:rgba(0,255,136,.3)">
          <span class="icon">&#10003;</span>
          <div><strong style="color:var(--green)">Database is clean!</strong> No stale data detected. Cleanup is not necessary.</div>
        </div>`
        : `
      ${d.staleRooms > 0 || d.ghostRooms > 0 ? `
      <div class="warning-banner"><span class="icon">&#9888;</span>
        <div><strong>${d.staleRooms + d.ghostRooms} active room(s)</strong> will be closed (status → CLOSED).
        Their <code>room_players</code> entries will be deleted.
        Redis <code>room_state:{id}</code> keys will be cleared.</div>
      </div>` : ''}
      ${d.stuckMatches > 0 ? `
      <div class="danger-banner"><span class="icon">&#128680;</span>
        <div><strong>${d.stuckMatches} stuck match(es)</strong> will be marked FINISHED with
        <code>endReason=ABANDONED_CLEANUP</code>.
        Redis <code>match_session</code> and <code>match_presence</code> keys will be cleared.</div>
      </div>` : ''}
      `}

    <div class="card mt-md">
      <div class="card-header"><span class="card-title">&#128465; Force Cleanup</span></div>
      <div class="card-body">
        <p class="text-muted text-sm mb-md">
          Cleanup closes all WAITING / IN_GAME rooms where <strong>no player has an active Redis session</strong>,
          then marks stuck matches as FINISHED. This operation is safe to run at any time — active players are
          detected via Redis and will NOT be affected.
        </p>
        ${adminRole === 'root'
        ? `<div class="root-section">
              <div class="root-section-header">&#9888; Root Admin Operation</div>
              <p class="text-sm text-muted mb-md">This action will be logged to the activity audit trail.</p>
              <button class="btn btn-danger" id="cleanup-btn" onclick="runForceCleanup()">
                &#128465; Run Force Cleanup (${staleTotal} item${staleTotal !== 1 ? 's' : ''})
              </button>
            </div>`
        : `<div class="warning-banner"><span class="icon">&#128274;</span>
              <div><strong>Root admin required</strong> to perform cleanup. Contact a root admin or use the SQL script.</div>
            </div>`}
        <div id="cleanup-result" class="mt-md"></div>
      </div>
    </div>

    <div class="card mt-md">
      <div class="card-header"><span class="card-title">&#128736; Manual SQL Script</span></div>
      <div class="card-body">
        <p class="text-sm text-muted mb-md">Run this on the database directly for a manual cleanup:</p>
        <pre style="background:var(--surface);border:1px solid var(--border);border-radius:6px;padding:.75rem 1rem;font-size:.75rem;overflow-x:auto;color:var(--cyan)">docker exec -i nighthunt_server-db-1 \\
  mysql -u$MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE \\
  &lt; /opt/nighthunt/scripts/cleanup-stale-data.sql</pre>
      </div>
    </div>`;
  } catch (e) {
    if (preview) preview.innerHTML = `<div class="card card-body text-red">Error loading preview: ${e.message}</div>`;
  }
}

/* ── Force Cleanup ──────────────────────────────────────────────────────── */
async function runForceCleanup() {
  if (!confirm('Run force cleanup? This will close stale rooms, evict their players, and fix stuck matches. Confirm?')) return;

  const btn = $('cleanup-btn');
  if (btn) { btn.disabled = true; btn.textContent = '⏳ Running...'; }

  const resultEl = $('cleanup-result');
  if (resultEl) resultEl.innerHTML = `<div class="flex items-center gap-sm"><span class="spinner"></span> Executing cleanup…</div>`;

  try {
    const r = await api('POST', '/api/admin/cleanup-stale-data');

    if (resultEl) resultEl.innerHTML = `
    <div class="warning-banner" style="background:var(--green-dim);border-color:rgba(0,255,136,.3)">
      <span class="icon">&#10003;</span>
      <div>
        <strong style="color:var(--green)">Cleanup completed successfully</strong>
        <div class="stats-grid mt-sm" style="grid-template-columns:repeat(4,1fr)">
          <div class="stat-card"><div class="stat-label">Rooms Closed</div><div class="stat-value" style="font-size:1.5rem;color:var(--cyan)">${r.roomsClosed}</div></div>
          <div class="stat-card"><div class="stat-label">Players Evicted</div><div class="stat-value" style="font-size:1.5rem;color:var(--yellow)">${r.playersEvicted}</div></div>
          <div class="stat-card"><div class="stat-label">Matches Fixed</div><div class="stat-value" style="font-size:1.5rem;color:var(--purple)">${r.matchesFixed}</div></div>
          <div class="stat-card"><div class="stat-label">Redis Keys Cleared</div><div class="stat-value" style="font-size:1.5rem;color:var(--green)">${r.redisKeysRemoved}</div></div>
        </div>
        <div class="text-xs text-muted mt-sm">Executed at: ${r.executedAt || new Date().toLocaleString()}</div>
      </div>
    </div>`;

    showAlert('Cleanup completed!', 'success');
    setTimeout(loadCleanupPreview, 2000);
  } catch (e) {
    if (resultEl) resultEl.innerHTML = `<div class="danger-banner"><span class="icon">&#10007;</span><div><strong>Cleanup failed:</strong> ${e.message}</div></div>`;
    showAlert(`Cleanup failed: ${e.message}`, 'error');
    if (btn) { btn.disabled = false; btn.textContent = '&#128465; Retry Force Cleanup'; }
  }
}
