/* ── Overview Page ─────────────────────────────────────────────────────── */
'use strict';

/* Pure-SVG donut chart — no Chart.js dependency */
function _drawTierDonut(elId, labels, values, colors) {
  const el = document.getElementById(elId);
  if (!el) return;
  const total = values.reduce((s, v) => s + v, 0);
  if (!total) {
    el.innerHTML = '<div class="empty-state" style="width:100%"><p>No tier data</p></div>';
    return;
  }
  const R = 58, CX = 72, CY = 72, SW = 22;
  const circ = 2 * Math.PI * R;
  let cum = 0;
  const slices = labels.map((lbl, i) => {
    const len = (values[i] / total) * circ;
    const dashOffset = circ / 4 - cum;
    cum += len;
    const col = colors[lbl] || '#888';
    return `<circle r="${R}" cx="${CX}" cy="${CY}" fill="none" stroke="${col}"
      stroke-width="${SW}" stroke-linecap="butt"
      stroke-dasharray="${len.toFixed(3)} ${(circ - len).toFixed(3)}"
      stroke-dashoffset="${dashOffset.toFixed(3)}" style="transition:all .4s ease">
      <title>${lbl}: ${values[i]} (${(values[i]/total*100).toFixed(1)}%)</title>
    </circle>`;
  }).join('');

  const legend = labels.map((lbl, i) => {
    const col = colors[lbl] || '#888';
    const pct = (values[i] / total * 100).toFixed(0);
    return `<div style="display:flex;align-items:center;gap:8px;padding:3px 0">
      <span style="width:9px;height:9px;border-radius:50%;background:${col};flex-shrink:0"></span>
      <span style="font-size:11px;flex:1;color:var(--muted)">${lbl}</span>
      <span style="font-size:12px;font-weight:600;color:var(--text)">${values[i]}</span>
      <span style="font-size:10px;color:var(--muted);width:34px;text-align:right">${pct}%</span>
    </div>`;
  }).join('');

  el.innerHTML = `
  <div style="display:flex;align-items:center;gap:1.5rem;flex-wrap:wrap;justify-content:center;padding:.5rem 0">
    <svg viewBox="0 0 144 144" width="140" height="140" style="flex-shrink:0;overflow:visible">
      <circle r="${R}" cx="${CX}" cy="${CY}" fill="none" stroke="var(--border)" stroke-width="${SW}"/>
      ${slices}
      <text x="${CX}" y="${CY - 7}" text-anchor="middle" fill="var(--text)" font-size="17" font-weight="700">${total}</text>
      <text x="${CX}" y="${CY + 11}" text-anchor="middle" fill="var(--muted)" font-size="9" letter-spacing="1">PLAYERS</text>
    </svg>
    <div style="flex:1;min-width:130px">${legend}</div>
  </div>`;
}

async function renderOverview() {
  clearInterval(refreshTimer);
  $('content').innerHTML = `
  <div class="flex justify-between items-center mb-md">
    <span class="text-muted text-xs">Live dashboard summary</span>
    <button class="btn btn-ghost btn-sm" onclick="renderOverview()">&#8635; Refresh</button>
  </div>
  <div class="stats-grid" id="ov-stats">
    <div class="stat-card"><div class="stat-label">Loading...</div><div class="stat-value"><span class="spinner"></span></div></div>
  </div>
  <div class="grid-2 mt-md" id="ov-mid"></div>
  <div id="ov-bot" class="mt-md"></div>`;

  try {
    const d = await api('GET', '/api/admin/overview');

    $('ov-stats').innerHTML = `
    <div class="stat-card"><div class="stat-label">Total Users</div><div class="stat-value">${fmt(d.totalUsers)}</div><div class="stat-sub">+${fmt(d.newTodayUsers)} today</div></div>
    <div class="stat-card" style="cursor:pointer;border-color:var(--green)" onclick="nav('online')"><div class="stat-label">Online Now</div><div class="stat-value" style="color:var(--green)">${fmt(d.onlineNow || 0)}</div><div class="stat-sub">Active sessions</div></div>
    <div class="stat-card"><div class="stat-label">Rooms Active</div><div class="stat-value" style="color:var(--cyan)">${(d.waitingRooms || 0) + (d.inGameRooms || 0)}</div><div class="stat-sub">${fmt(d.waitingRooms)} waiting / ${fmt(d.inGameRooms)} in-game</div></div>
    <div class="stat-card"><div class="stat-label">Matches Today</div><div class="stat-value" style="color:var(--yellow)">${fmt(d.todayMatches)}</div><div class="stat-sub">Since midnight</div></div>
    <div class="stat-card"><div class="stat-label">Active Bans</div><div class="stat-value" style="color:var(--red)">${fmt(d.activeBans)}</div><div class="stat-sub">User / IP / Device</div></div>
    <div class="stat-card"><div class="stat-label">Logins Today</div><div class="stat-value" style="color:var(--purple)">${fmt(d.todayLogins)}</div></div>`;

    const topHtml = (d.topPlayers || []).map((p, i) => `
    <div class="leader-row">
      <div class="leader-rank" style="color:${i === 0 ? 'var(--yellow)' : i === 1 ? 'var(--muted)' : i === 2 ? 'var(--orange)' : 'var(--muted)'}">${i + 1}</div>
      <div style="flex:1"><div class="leader-name">${p.username}</div><div class="text-xs text-muted">${p.tier} &bull; ${p.totalWins}W / ${p.totalLosses}L</div></div>
      <div class="leader-elo font-bold" style="color:${elo2color(p.elo)}">${p.elo}</div>
    </div>`).join('');

    const tk = Object.keys(d.tierDistribution || {});
    const tv = tk.map(k => d.tierDistribution[k]);
    const tc = { BRONZE: '#cd7f32', SILVER: '#c0c0c0', GOLD: '#ffd700', PLATINUM: '#e5e4e2', DIAMOND: '#b9f2ff', MASTER: '#a855f7', CHALLENGER: '#00d4ff' };

    $('ov-mid').innerHTML = `
    <div class="card">
      <div class="card-header"><span class="card-title">&#127942; Top 10 Leaderboard</span></div>
      <div class="card-body">${topHtml || '<div class="empty-state"><p>No data yet</p></div>'}</div>
    </div>
    <div class="card">
      <div class="card-header"><span class="card-title">&#127759; Tier Distribution</span></div>
      <div class="card-body" id="tier-chart" style="display:flex;align-items:center;justify-content:center;min-height:220px"></div>
    </div>`;

    _drawTierDonut('tier-chart', tk, tv, tc);

    const actHtml = (d.recentActivity || []).slice(0, 15).map(l => `
    <tr>
      <td data-label="Time">${fmtDate(l.createdAt)}</td>
      <td data-label="User" class="font-bold">${l.username || '—'}</td>
      <td data-label="Event">${eventBadge(l.eventType)}</td>
      <td data-label="Data" class="text-muted truncate" style="max-width:200px">${l.eventData || '—'}</td>
      <td data-label="IP" class="text-xs text-muted">${l.ipAddress || '—'}</td>
    </tr>`).join('');

    $('ov-bot').innerHTML = `
    <div class="card">
      <div class="card-header">
        <span class="card-title">&#128196; Recent Activity</span>
        <button class="btn btn-ghost btn-sm" onclick="nav('activity')">View All</button>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>Time</th><th>User</th><th>Event</th><th>Data</th><th>IP</th></tr></thead>
          <tbody>${actHtml || '<tr><td colspan="5" style="text-align:center;color:var(--muted);padding:2rem">No activity</td></tr>'}</tbody>
        </table>
      </div>
    </div>`;
  } catch (e) {
    $('ov-stats').innerHTML = `<div class="card" style="grid-column:1/-1"><div class="card-body text-red">&#9888; Failed to load overview: ${e.message}</div></div>`;
  }
}
