/* ── Users Page ─────────────────────────────────────────────────────────── */
'use strict';

let uPage = 0, uSearch = '', uSort = 'id', uSortDir = 'desc';

async function renderUsers() {
  clearInterval(refreshTimer);
  $('content').innerHTML = `
  <div class="form-row mb-md">
    <div class="form-group" style="flex:1;min-width:200px">
      <input class="form-control" id="u-search" placeholder="&#128269; Search username / email..."
        value="${uSearch}" oninput="uSearch=this.value;uPage=0;loadUsers()">
    </div>
    <div class="form-group">
      <select class="form-control sm" onchange="uSort=this.value;loadUsers()">
        <option value="id"        ${uSort==='id'        ?'selected':''}>Sort: ID</option>
        <option value="elo"       ${uSort==='elo'       ?'selected':''}>Sort: ELO</option>
        <option value="createdAt" ${uSort==='createdAt' ?'selected':''}>Sort: Created</option>
        <option value="username"  ${uSort==='username'  ?'selected':''}>Sort: Name</option>
      </select>
    </div>
    <div class="form-group">
      <select class="form-control sm" onchange="uSortDir=this.value;loadUsers()">
        <option value="desc" ${uSortDir==='desc'?'selected':''}>Desc</option>
        <option value="asc"  ${uSortDir==='asc' ?'selected':''}>Asc</option>
      </select>
    </div>
  </div>
  <div class="card">
    <div class="table-wrap">
      <table>
        <thead><tr>
          <th>ID</th><th class="col-center">Status</th><th>Username</th><th>Email</th>
          <th>ELO</th><th>Tier</th><th>W/L/D</th><th>Joined</th><th>Actions</th>
        </tr></thead>
        <tbody id="u-tbody">
          <tr><td colspan="9" style="text-align:center;padding:2rem"><span class="spinner"></span></td></tr>
        </tbody>
      </table>
    </div>
    <div id="u-pager"></div>
  </div>`;
  loadUsers();
}

async function loadUsers() {
  try {
    const d = await api('GET', '/api/admin/users', null,
      { page: uPage, size: 20, search: uSearch, sortBy: uSort, sortDir: uSortDir });
    $('u-tbody').innerHTML = (d.content || []).map(u => `
    <tr>
      <td data-label="ID" class="text-muted">#${u.id}</td>
      <td data-label="Status" class="col-center">
        <span title="${u.isOnline ? 'Online' : 'Offline'}" style="color:${u.isOnline ? 'var(--green)' : 'var(--border)'}">&#9679;</span>
      </td>
      <td data-label="Username"><strong>${u.username}</strong></td>
      <td data-label="Email" class="text-muted text-sm">${u.email || '—'}</td>
      <td data-label="ELO" class="font-bold" style="color:${elo2color(u.elo)}">${u.elo}</td>
      <td data-label="Tier">${tierBadge(u.tier)}</td>
      <td data-label="W/L/D" class="text-sm">
        <span class="text-green">${u.totalWins}W</span> /
        <span class="text-red">${u.totalLosses}L</span> /
        ${u.totalDraws || 0}D
      </td>
      <td data-label="Joined" class="text-xs text-muted">${fmtDate(u.createdAt)}</td>
      <td data-label="Actions" style="white-space:nowrap">
        <button class="btn btn-ghost btn-xs" onclick="viewUser(${u.id})">View</button>
        <button class="btn btn-warning btn-xs" onclick="editUserModal(${u.id},'${u.username}',${u.elo})">Edit</button>
        <button class="btn btn-danger btn-xs" onclick="banUserModal(${u.id},'${u.username}')">Ban</button>
      </td>
    </tr>`).join('') || '<tr><td colspan="9" style="text-align:center;color:var(--muted);padding:2rem">No users found</td></tr>';
    renderPager('u-pager', d, p => { uPage = p; loadUsers(); });
  } catch (e) {
    $('u-tbody').innerHTML = `<tr><td colspan="9" class="text-red" style="padding:1rem">${e.message}</td></tr>`;
  }
}

async function viewUser(id) {
  showModal(`<div style="text-align:center;padding:2rem"><span class="spinner"></span></div>`);
  try {
    const isRoot = adminRole === 'root';
    const u = await api('GET', isRoot ? `/api/admin/users/${id}/full` : `/api/admin/users/${id}`);

    const bansHtml = (u.activeBans || []).map(b => `
    <div style="background:var(--red-dim);border:1px solid rgba(255,68,68,.2);border-radius:8px;padding:.75rem;margin-bottom:.5rem;font-size:.82rem">
      <strong class="text-red">${b.banType}</strong> — ${b.reason || 'No reason'} &bull;
      ${b.isPermanent ? 'Permanent' : fmtDate(b.expiresAt)}
      <button class="btn btn-success btn-xs" style="float:right" onclick="removeBan(${b.id})">Unban</button>
    </div>`).join('');

    const actList = u.allActivity || u.recentActivity || [];
    const actHtml = actList.map(l => `
    <div style="display:flex;gap:.5rem;font-size:.78rem;padding:.35rem 0;border-bottom:1px solid var(--border)">
      <span class="text-muted" style="min-width:130px;flex-shrink:0">${fmtDate(l.createdAt)}</span>
      ${eventBadge(l.eventType)}
      <span class="text-muted" style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${l.eventData || ''}</span>
      <span class="text-xs text-muted">${l.ipAddress || ''}</span>
    </div>`).join('');

    let rootHtml = '';
    if (isRoot) {
      rootHtml = `
      <div class="root-section">
        <div class="root-section-header">&#128274; Root Admin Details</div>
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:.5rem;font-size:.82rem;margin-bottom:1rem">
          <div><span class="text-muted">Total Played:</span> ${u.totalMatchesPlayed || 0}</div>
          <div><span class="text-muted">Win Rate:</span> ${u.winRate || 'N/A'}</div>
          <div><span class="text-muted">Updated:</span> ${fmtDate(u.updatedAt)}</div>
        </div>
        <div class="text-xs text-red font-bold mb-sm" style="text-transform:uppercase;letter-spacing:.06em">Password Hash (BCrypt)</div>
        <div style="background:var(--surface);border:1px solid var(--border);border-radius:6px;padding:.5rem .75rem;font-family:monospace;font-size:.75rem;display:flex;align-items:center;justify-content:space-between;gap:.5rem;margin-bottom:.75rem">
          <span id="hash-disp-${id}" style="flex:1;overflow:hidden;text-overflow:ellipsis">&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;</span>
          <button class="btn btn-ghost btn-xs" id="hash-btn-${id}" data-hash="${u.passwordHash || ''}"
            onclick="toggleHash('hash-disp-${id}','hash-btn-${id}',this.dataset.hash)">Reveal</button>
        </div>
      </div>`;
    }

    showModal(`
    <div class="modal-header">
      <span class="modal-title">&#128101; ${u.username}
        ${u.isOnline ? '<span class="badge badge-green" style="margin-left:.5rem">Online</span>' : '<span class="badge badge-muted" style="margin-left:.5rem">Offline</span>'}
        ${isRoot ? '<span class="badge badge-red" style="margin-left:.5rem">ROOT VIEW</span>' : ''}
      </span>
      <button class="modal-close" onclick="closeModal(event)">&times;</button>
    </div>
    <div class="modal-body">
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:.6rem;margin-bottom:1.25rem;font-size:.875rem">
        <div><span class="text-muted">ID:</span> <code>${u.id}</code></div>
        <div><span class="text-muted">Email:</span> ${u.email || '—'}</div>
        <div><span class="text-muted">ELO:</span> <strong style="color:${elo2color(u.elo)}">${u.elo}</strong></div>
        <div><span class="text-muted">Tier:</span> ${tierBadge(u.tier)}</div>
        <div><span class="text-muted">W/L/D:</span> ${u.totalWins}/${u.totalLosses}/${u.totalDraws || 0}</div>
        <div><span class="text-muted">Joined:</span> ${fmtDate(u.createdAt)}</div>
      </div>
      ${bansHtml ? `<div class="mb-md"><strong class="text-red text-sm">Active Bans</strong><div class="mt-sm">${bansHtml}</div></div>` : ''}
      <div class="mb-md">
        <strong class="text-sm text-muted">Activity (${actList.length} entries)</strong>
        <div style="margin-top:.5rem;max-height:200px;overflow-y:auto">${actHtml || '<p class="text-muted text-sm" style="padding:.75rem 0">No activity</p>'}</div>
      </div>
      ${rootHtml}
    </div>
    <div class="modal-footer">
      <button class="btn btn-warning btn-sm" onclick="editUserModal(${u.id},'${u.username}',${u.elo})">&#9998; Edit</button>
      <button class="btn btn-danger btn-sm" onclick="banUserModal(${u.id},'${u.username}')">&#128683; Ban</button>
    </div>`);
  } catch (e) {
    showModal(`<div class="modal-body text-red">${e.message}</div>`);
  }
}

function toggleHash(dispId, btnId, hash) {
  const el = $(dispId), btn = $(btnId);
  if (!el) return;
  if (btn.textContent === 'Reveal') {
    el.textContent = hash || '(empty)'; el.style.color = 'var(--yellow)'; btn.textContent = 'Hide';
  } else {
    el.innerHTML = '&#8226;'.repeat(20); el.style.color = ''; btn.textContent = 'Reveal';
  }
}

function editUserModal(id, name, elo) {
  showModal(`
  <div class="modal-header">
    <span class="modal-title">&#9998; Edit: ${name}</span>
    <button class="modal-close" onclick="closeModal(event)">&times;</button>
  </div>
  <div class="modal-body">
    <div class="form-group mb-md">
      <label class="form-label">ELO (0 – 3000)</label>
      <input type="number" id="edit-elo" value="${elo}" class="form-control" min="0" max="3000">
    </div>
    <div class="mb-md">
      <div class="flex justify-between text-sm mb-sm">
        <span>ELO Slider</span>
        <span id="elo-disp" class="text-cyan font-bold">${elo}</span>
      </div>
      <input type="range" min="0" max="3000" value="${elo}" class="w-full"
        oninput="$('edit-elo').value=this.value;$('elo-disp').textContent=this.value">
    </div>
  </div>
  <div class="modal-footer">
    <button class="btn btn-ghost btn-sm" onclick="closeModal(event)">Cancel</button>
    <button class="btn btn-success btn-sm" onclick="saveUser(${id})">Save Changes</button>
  </div>`);
  $('edit-elo')?.addEventListener('input', function () {
    document.querySelector('input[type=range]').value = this.value;
    $('elo-disp').textContent = this.value;
  });
}

async function saveUser(id) {
  const elo = parseInt($('edit-elo').value);
  if (isNaN(elo)) return;
  try {
    await api('PUT', `/api/admin/users/${id}`, { elo });
    $('modal-bg').classList.remove('open');
    showAlert('User updated', 'success');
    loadUsers();
  } catch (e) { showAlert(e.message, 'error'); }
}

function banUserModal(id, name) {
  showModal(`
  <div class="modal-header">
    <span class="modal-title">&#128683; Ban: ${name}</span>
    <button class="modal-close" onclick="closeModal(event)">&times;</button>
  </div>
  <div class="modal-body">
    <div class="form-group mb-md">
      <label class="form-label">Reason</label>
      <input type="text" id="ban-reason" class="form-control" placeholder="Enter reason...">
    </div>
    <div class="form-group mb-md">
      <label class="form-label">Duration in minutes (0 = permanent)</label>
      <input type="number" id="ban-dur" class="form-control" value="0" min="0">
    </div>
    <div class="form-group mb-md">
      <label class="form-label">Ban Type</label>
      <select id="ban-type" class="form-control">
        <option value="USER">USER (account)</option>
        <option value="IP">IP Address</option>
        <option value="DEVICE">Device Fingerprint</option>
      </select>
    </div>
  </div>
  <div class="modal-footer">
    <button class="btn btn-ghost btn-sm" onclick="closeModal(event)">Cancel</button>
    <button class="btn btn-danger btn-sm" onclick="confirmBan(${id})">Confirm Ban</button>
  </div>`);
}

async function confirmBan(id) {
  try {
    await api('POST', `/api/admin/users/${id}/ban`, {
      reason: $('ban-reason').value,
      durationMinutes: parseInt($('ban-dur').value) || 0,
      banType: $('ban-type').value
    });
    $('modal-bg').classList.remove('open');
    showAlert('User banned', 'warning');
    loadUsers();
  } catch (e) { showAlert(e.message, 'error'); }
}
