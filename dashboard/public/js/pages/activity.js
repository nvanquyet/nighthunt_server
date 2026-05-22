/* ── Activity Logs Page ─────────────────────────────────────────────────── */
'use strict';

let aPage = 0, aUserId = '', aEvent = '', aFrom = '', aTo = '';

async function renderActivity() {
  clearInterval(refreshTimer);
  $('content').innerHTML = `
  <div class="form-row mb-md">
    <div class="form-group">
      <input class="form-control sm" id="a-uid" placeholder="User ID"
        value="${aUserId}" oninput="aUserId=this.value;aPage=0;loadActivity()">
    </div>
    <div class="form-group">
      <select class="form-control sm" id="a-event" onchange="aEvent=this.value;aPage=0;loadActivity()">
        <option value="">All Events</option>
        ${['LOGIN','LOGOUT','REGISTER','PASSWORD_CHANGE','ROOM_CREATE','ROOM_JOIN','ROOM_LEAVE','MATCH_END','BAN','UNBAN']
          .map(e => `<option value="${e}" ${aEvent===e?'selected':''}>${e}</option>`).join('')}
      </select>
    </div>
    <div class="form-group">
      <input class="form-control sm" type="date" value="${aFrom}" onchange="aFrom=this.value;aPage=0;loadActivity()">
    </div>
    <div class="form-group">
      <input class="form-control sm" type="date" value="${aTo}" onchange="aTo=this.value;aPage=0;loadActivity()">
    </div>
    <button class="btn btn-ghost btn-sm" onclick="aUserId='';aEvent='';aFrom='';aTo='';aPage=0;renderActivity()">&#10005; Clear</button>
  </div>
  <div class="card">
    <div class="table-wrap">
      <table>
        <thead><tr><th>Time</th><th>User ID</th><th>Username</th><th>Event</th><th>Data</th><th>IP</th></tr></thead>
        <tbody id="a-tbody">
          <tr><td colspan="6" style="text-align:center;padding:2rem"><span class="spinner"></span></td></tr>
        </tbody>
      </table>
    </div>
    <div id="a-pager"></div>
  </div>`;
  loadActivity();
}

async function loadActivity() {
  try {
    const d = await api('GET', '/api/admin/activity', null,
      { page: aPage, size: 50, userId: aUserId || null, eventType: aEvent || null, from: aFrom || null, to: aTo || null });
    $('a-tbody').innerHTML = (d.content || []).map(l => `
    <tr>
      <td data-label="Time" class="text-xs" style="white-space:nowrap">${fmtDate(l.createdAt)}</td>
      <td data-label="User ID" class="text-muted text-xs">${l.userId || '—'}</td>
      <td data-label="Username"><strong>${l.username || '—'}</strong></td>
      <td data-label="Event">${eventBadge(l.eventType)}</td>
      <td data-label="Data" class="text-muted truncate" style="max-width:240px">${l.eventData || '—'}</td>
      <td data-label="IP" class="text-xs text-muted">${l.ipAddress || '—'}</td>
    </tr>`).join('') || '<tr><td colspan="6" style="text-align:center;color:var(--muted);padding:2rem">No logs found</td></tr>';
    renderPager('a-pager', d, p => { aPage = p; loadActivity(); });
  } catch (e) {
    $('a-tbody').innerHTML = `<tr><td colspan="6" class="text-red" style="padding:1rem">${e.message}</td></tr>`;
  }
}
