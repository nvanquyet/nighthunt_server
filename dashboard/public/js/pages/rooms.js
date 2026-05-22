/* ── Live Rooms Page ─────────────────────────────────────────────────────── */
'use strict';

async function renderRooms() {
  clearInterval(refreshTimer);
  $('content').innerHTML = `
  <div class="flex justify-between items-center mb-md">
    <div class="flex items-center gap-sm">
      <span class="text-muted text-xs" id="room-ts"></span>
      <span class="badge badge-green">Auto-refresh 5s</span>
    </div>
    <button class="btn btn-ghost btn-sm" onclick="loadRooms()">&#8635; Refresh</button>
  </div>
  <div class="stats-grid mb-md" id="room-stats" style="grid-template-columns:repeat(3,1fr)"></div>
  <div class="room-grid" id="room-grid"></div>`;
  loadRooms();
  refreshTimer = setInterval(loadRooms, 5000);
}

async function loadRooms() {
  try {
    const d = await api('GET', '/api/admin/rooms');
    if (!$('room-stats')) return;

    $('room-stats').innerHTML = `
    <div class="stat-card"><div class="stat-label">Waiting</div><div class="stat-value" style="color:var(--yellow)">${d.waitingCount || 0}</div><div class="stat-sub">Gathering players</div></div>
    <div class="stat-card"><div class="stat-label">In-Game</div><div class="stat-value" style="color:var(--green)">${d.inGameCount || 0}</div><div class="stat-sub">Active matches</div></div>
    <div class="stat-card"><div class="stat-label">Total Active</div><div class="stat-value" style="color:var(--cyan)">${(d.waitingCount || 0) + (d.inGameCount || 0)}</div></div>`;

    $('room-grid').innerHTML = (d.rooms || []).map(r => `
    <div class="room-card">
      <div class="room-card-header">
        <div>
          <div class="room-code">${r.roomCode}</div>
          <div class="text-xs text-muted">by ${r.ownerName}</div>
        </div>
        ${statusBadge(r.status)}
      </div>
      <div class="flex justify-between text-xs text-muted mb-sm">
        <span>&#127918; ${r.mode || '?'}</span>
        <span>${r.isPublic ? 'Public' : 'Private'}</span>
        <span>&#128101; ${r.playerCount || 0}</span>
      </div>
      <div style="border-top:1px solid var(--border);padding-top:.5rem">
        ${(r.players || []).map(p => `
        <div class="flex justify-between text-xs" style="padding:.2rem 0">
          <span>${p.username}</span>
          <div class="flex gap-sm">
            <span class="badge badge-muted" style="font-size:.65rem">${p.team || '?'}</span>
            ${p.isReady ? '<span class="badge badge-green" style="font-size:.65rem">READY</span>' : ''}
          </div>
        </div>`).join('') || '<div class="text-xs text-muted" style="text-align:center;padding:.35rem">Empty</div>'}
      </div>
    </div>`).join('') || '<div class="empty-state" style="grid-column:1/-1"><div class="empty-icon">&#128682;</div><p>No active rooms</p></div>';

    const ts = $('room-ts');
    if (ts) ts.textContent = 'Updated ' + new Date().toLocaleTimeString();
  } catch (e) {
    if ($('room-grid')) $('room-grid').innerHTML = `<div class="text-red" style="padding:1rem">${e.message}</div>`;
  }
}
