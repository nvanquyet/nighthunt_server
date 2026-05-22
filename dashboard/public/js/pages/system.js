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
  </div>`;
  await loadSystem();
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
