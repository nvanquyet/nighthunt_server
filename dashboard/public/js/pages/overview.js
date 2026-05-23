/* ── Overview Page ─────────────────────────────────────────────────────── */
'use strict';

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
      <div class="card-body" style="height:260px;display:flex;align-items:center;justify-content:center"><canvas id="tier-chart"></canvas></div>
    </div>`;

    if (tierChart) { tierChart.destroy(); tierChart = null; }
    const ctx = document.getElementById('tier-chart');
    if (ctx && tk.length) {
      if (typeof Chart === 'undefined') {
        ctx.parentElement.innerHTML = '<div class="text-muted text-xs text-center" style="padding:1rem">Chart.js failed to load</div>';
      } else {
        try {
          tierChart = new Chart(ctx, {
            type: 'doughnut',
            data: { labels: tk, datasets: [{ data: tv, backgroundColor: tk.map(k => tc[k] || '#888'), borderColor: 'var(--card)', borderWidth: 3 }] },
            options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { labels: { color: 'var(--text)', font: { size: 11 } } } }, cutout: '65%' }
          });
        } catch (chartErr) {
          ctx.parentElement.innerHTML = `<div class="text-muted text-xs text-center" style="padding:1rem">${chartErr.message}</div>`;
        }
      }
    }

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
