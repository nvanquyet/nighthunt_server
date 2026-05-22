/* ── Match History Page ──────────────────────────────────────────────────── */
'use strict';

let mPage = 0, mStatus = '', mMode = '';

async function renderMatches() {
  clearInterval(refreshTimer);
  $('content').innerHTML = `
  <div class="form-row mb-md">
    <div class="form-group">
      <select class="form-control sm" onchange="mStatus=this.value;mPage=0;loadMatches()">
        <option value="">All Status</option>
        <option value="WAITING"     ${mStatus==='WAITING'     ?'selected':''}>WAITING</option>
        <option value="IN_PROGRESS" ${mStatus==='IN_PROGRESS' ?'selected':''}>IN_PROGRESS</option>
        <option value="FINISHED"    ${mStatus==='FINISHED'    ?'selected':''}>FINISHED</option>
        <option value="CANCELLED"   ${mStatus==='CANCELLED'   ?'selected':''}>CANCELLED</option>
      </select>
    </div>
    <div class="form-group">
      <select class="form-control sm" onchange="mMode=this.value;mPage=0;loadMatches()">
        <option value="">All Modes</option>
        <option value="TEAM_DEATHMATCH" ${mMode==='TEAM_DEATHMATCH'?'selected':''}>TEAM_DEATHMATCH</option>
        <option value="FREE_FOR_ALL"    ${mMode==='FREE_FOR_ALL'   ?'selected':''}>FREE_FOR_ALL</option>
      </select>
    </div>
  </div>
  <div class="card">
    <div class="table-wrap">
      <table>
        <thead><tr>
          <th>Match ID</th><th>Mode</th><th>Status</th><th>Winner</th>
          <th>Duration</th><th>End Reason</th><th class="col-center">Players</th><th>Started</th>
        </tr></thead>
        <tbody id="m-tbody">
          <tr><td colspan="8" style="text-align:center;padding:2rem"><span class="spinner"></span></td></tr>
        </tbody>
      </table>
    </div>
    <div id="m-pager"></div>
  </div>`;
  loadMatches();
}

async function loadMatches() {
  try {
    const d = await api('GET', '/api/admin/matches', null,
      { page: mPage, size: 20, status: mStatus || null, mode: mMode || null });
    $('m-tbody').innerHTML = (d.content || []).map(m => {
      const dur = m.durationSecs ? Math.floor(m.durationSecs / 60) + 'm ' + (m.durationSecs % 60) + 's' : '—';
      return `<tr>
        <td data-label="Match ID" class="font-mono text-xs text-muted" title="${m.matchId}">${(m.matchId || '').substring(0, 10)}&hellip;</td>
        <td data-label="Mode"><span class="badge badge-purple">${m.gameMode || '?'}</span></td>
        <td data-label="Status">${statusBadge(m.status)}</td>
        <td data-label="Winner" class="text-green">Team ${m.winnerTeamId != null ? m.winnerTeamId : '?'}</td>
        <td data-label="Duration" class="text-muted text-sm">${dur}</td>
        <td data-label="End Reason" class="text-muted text-sm">${m.endReason || '—'}</td>
        <td data-label="Players" class="col-center">
          <button class="btn btn-ghost btn-xs" onclick='showMatchPlayers(${JSON.stringify(m.players || [])})'>
            &#128101; ${(m.players || []).length}
          </button>
        </td>
        <td data-label="Started" class="text-xs text-muted">${fmtDate(m.startedAt)}</td>
      </tr>`;
    }).join('') || '<tr><td colspan="8" style="text-align:center;color:var(--muted);padding:2rem">No matches found</td></tr>';
    renderPager('m-pager', d, p => { mPage = p; loadMatches(); });
  } catch (e) {
    $('m-tbody').innerHTML = `<tr><td colspan="8" class="text-red" style="padding:1rem">${e.message}</td></tr>`;
  }
}

function showMatchPlayers(players) {
  showModal(`
  <div class="modal-header">
    <span class="modal-title">&#128296; Match Players</span>
    <button class="modal-close" onclick="closeModal(event)">&times;</button>
  </div>
  <div class="modal-body">
    <div class="table-wrap">
      <table>
        <thead><tr><th>Name</th><th>Team</th><th>K</th><th>D</th><th>Score</th><th>ELO &Delta;</th></tr></thead>
        <tbody>${players.map(p => `<tr>
          <td><strong>${p.displayName || p.userId}</strong></td>
          <td><span class="badge badge-muted">T${p.teamId || '?'}</span></td>
          <td class="text-green">${p.kills || 0}</td>
          <td class="text-red">${p.deaths || 0}</td>
          <td class="text-cyan font-bold">${p.score || 0}</td>
          <td class="font-bold" style="color:${(p.eloChange || 0) >= 0 ? 'var(--green)' : 'var(--red)'}">
            ${(p.eloChange || 0) >= 0 ? '+' : ''}${p.eloChange || 0}
          </td>
        </tr>`).join('') || '<tr><td colspan="6" style="text-align:center;color:var(--muted);padding:1.5rem">No players</td></tr>'}
        </tbody>
      </table>
    </div>
  </div>`);
}
