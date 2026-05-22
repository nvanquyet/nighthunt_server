/* ── Online Users Page ──────────────────────────────────────────────────── */
'use strict';

async function renderOnlineUsers() {
  clearInterval(refreshTimer);
  $('content').innerHTML = `
  <div class="flex justify-between items-center mb-md">
    <div id="ol-count" class="text-muted text-sm">Loading...</div>
    <button class="btn btn-ghost btn-sm" onclick="renderOnlineUsers()">&#8635; Refresh</button>
  </div>
  <div class="card">
    <div class="table-wrap">
      <table>
        <thead><tr>
          <th>ID</th><th>Username</th><th>ELO</th><th>Tier</th>
          <th>Room</th><th>Status</th><th>Mode</th><th>Session TTL</th><th>Actions</th>
        </tr></thead>
        <tbody id="ol-tbody">
          <tr><td colspan="9" style="text-align:center;padding:2rem"><span class="spinner"></span></td></tr>
        </tbody>
      </table>
    </div>
  </div>`;

  try {
    const d = await api('GET', '/api/admin/online-users');
    const count = $('ol-count');
    if (count) count.innerHTML = `<span class="badge badge-green">&#9679; ${fmt(d.onlineCount || 0)} online</span>`;

    const users = d.users || [];
    if (!users.length) {
      $('ol-tbody').innerHTML = '<tr><td colspan="9" style="text-align:center;color:var(--muted);padding:3rem">No users currently online</td></tr>';
      return;
    }

    $('ol-tbody').innerHTML = users.map(u => {
      const ttl = u.sessionTtlSeconds;
      const ttlStr = ttl != null
        ? (ttl > 3600 ? `${Math.floor(ttl / 3600)}h ${Math.floor((ttl % 3600) / 60)}m` : `${Math.floor(ttl / 60)}m ${ttl % 60}s`)
        : '—';
      const ttlColor = ttl != null && ttl < 300 ? 'var(--red)' : ttl != null && ttl < 900 ? 'var(--yellow)' : 'var(--green)';
      return `<tr>
        <td data-label="ID" class="text-muted">#${u.id}</td>
        <td data-label="Username"><strong>${u.username}</strong></td>
        <td data-label="ELO" class="font-bold" style="color:${elo2color(u.elo)}">${u.elo}</td>
        <td data-label="Tier">${tierBadge(u.tier)}</td>
        <td data-label="Room">${u.roomCode ? `<code class="text-cyan">${u.roomCode}</code>` : '<span class="text-muted">—</span>'}</td>
        <td data-label="Status">${u.roomStatus ? statusBadge(u.roomStatus) : '—'}</td>
        <td data-label="Mode" class="text-sm text-muted">${u.gameMode || '—'}</td>
        <td data-label="TTL" class="font-mono text-sm" style="color:${ttlColor}">${ttlStr}</td>
        <td data-label="Actions"><button class="btn btn-ghost btn-xs" onclick="viewUser(${u.id})">View</button></td>
      </tr>`;
    }).join('');
  } catch (e) {
    $('ol-tbody').innerHTML = `<tr><td colspan="9" class="text-red" style="padding:1rem">${e.message}</td></tr>`;
  }
}
