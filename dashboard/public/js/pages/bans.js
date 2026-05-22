/* ── Ban Management Page ─────────────────────────────────────────────────── */
'use strict';

let bPage = 0, bType = '', bActive = '';

async function renderBans() {
  clearInterval(refreshTimer);
  $('content').innerHTML = `
  <div class="form-row mb-md">
    <div class="form-group">
      <select class="form-control sm" onchange="bType=this.value;bPage=0;loadBans()">
        <option value="">All Types</option>
        <option value="USER"   ${bType==='USER'  ?'selected':''}>USER</option>
        <option value="IP"     ${bType==='IP'    ?'selected':''}>IP</option>
        <option value="DEVICE" ${bType==='DEVICE'?'selected':''}>DEVICE</option>
      </select>
    </div>
    <div class="form-group">
      <select class="form-control sm" onchange="bActive=this.value;bPage=0;loadBans()">
        <option value="">All Status</option>
        <option value="true"  ${bActive==='true' ?'selected':''}>Active Only</option>
        <option value="false" ${bActive==='false'?'selected':''}>Expired</option>
      </select>
    </div>
  </div>
  <div class="card">
    <div class="table-wrap">
      <table>
        <thead><tr>
          <th>ID</th><th>User</th><th>Type</th><th>Reason</th>
          <th>Duration</th><th>Banned At</th><th>Expires</th><th>Status</th><th>Action</th>
        </tr></thead>
        <tbody id="b-tbody">
          <tr><td colspan="9" style="text-align:center;padding:2rem"><span class="spinner"></span></td></tr>
        </tbody>
      </table>
    </div>
    <div id="b-pager"></div>
  </div>`;
  loadBans();
}

async function loadBans() {
  try {
    const d = await api('GET', '/api/admin/bans', null,
      { page: bPage, size: 20, type: bType || null, active: bActive || null });
    $('b-tbody').innerHTML = (d.content || []).map(b => `
    <tr>
      <td data-label="ID" class="text-muted">#${b.id}</td>
      <td data-label="User">
        <strong>${b.username || '—'}</strong>
        <div class="text-xs text-muted">#${b.userId || '—'}</div>
      </td>
      <td data-label="Type"><span class="badge badge-red">${b.banType}</span></td>
      <td data-label="Reason" class="truncate" style="max-width:180px">${b.reason || '—'}</td>
      <td data-label="Duration">${b.isPermanent ? '<span class="badge badge-red">Permanent</span>' : (b.banDurationMinutes || 0) + 'm'}</td>
      <td data-label="Banned At" class="text-xs text-muted">${fmtDate(b.bannedAt)}</td>
      <td data-label="Expires" class="text-xs text-muted">${b.isPermanent ? 'Never' : fmtDate(b.expiresAt)}</td>
      <td data-label="Status">${b.isActive ? '<span class="badge badge-red">Active</span>' : '<span class="badge badge-muted">Expired</span>'}</td>
      <td data-label="Action">${b.isActive ? `<button class="btn btn-success btn-xs" onclick="removeBan(${b.id})">Unban</button>` : ''}</td>
    </tr>`).join('') || '<tr><td colspan="9" style="text-align:center;color:var(--muted);padding:2rem">No bans found</td></tr>';
    renderPager('b-pager', d, p => { bPage = p; loadBans(); });
  } catch (e) {
    $('b-tbody').innerHTML = `<tr><td colspan="9" class="text-red" style="padding:1rem">${e.message}</td></tr>`;
  }
}

async function removeBan(id) {
  if (!confirm('Remove this ban?')) return;
  try {
    await api('DELETE', `/api/admin/bans/${id}`);
    showAlert('Ban removed', 'success');
    loadBans();
    $('modal-bg')?.classList.remove('open');
  } catch (e) { showAlert(e.message, 'error'); }
}
