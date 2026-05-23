/* ── Game Config Page ────────────────────────────────────────────────────── */
'use strict';

let cfgActiveTab = 'modes';

async function renderConfig() {
  clearInterval(refreshTimer);
  $('content').innerHTML = `
  <div class="tab-bar mb-md" id="cfg-tabs">
    <button class="tab-btn" data-tab="modes" onclick="loadConfigTab('modes')">&#127918; Game Modes</button>
    <button class="tab-btn" data-tab="maps"  onclick="loadConfigTab('maps')">&#128506; Maps</button>
    <button class="tab-btn" data-tab="runtime" onclick="loadConfigTab('runtime')">&#128295; Runtime Config</button>
  </div>
  <div id="config-body"><div style="text-align:center;padding:3rem"><span class="spinner"></span></div></div>`;
  loadConfigTab(cfgActiveTab);
}

function _setActiveTab(name) {
  cfgActiveTab = name;
  document.querySelectorAll('#cfg-tabs .tab-btn').forEach(b => {
    b.classList.toggle('active', b.dataset.tab === name);
  });
}

async function loadConfigTab(name) {
  _setActiveTab(name);
  if (name === 'modes')   await loadModesConfig();
  else if (name === 'maps') await loadMapsConfig();
  else await loadRuntimeConfig();
}

/* ── Modes ──────────────────────────────────────────────────────────────── */
async function loadModesConfig() {
  $('config-body').innerHTML = '<div style="text-align:center;padding:3rem"><span class="spinner"></span></div>';
  try {
    const r = await api('GET', '/api/admin/config/modes');
    const modes = r.data || r;
    $('config-body').innerHTML = `
    <div class="card">
      <div class="card-header">
        <span class="card-title">&#127918; Game Modes</span>
        <span class="text-muted text-xs">${modes.length} total</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr>
            <th>Key</th><th>Display Name</th><th>Description</th><th>Players/Team</th>
            <th>ELO Min</th><th>ELO Max</th><th>Fill</th>
            <th>Status</th><th class="col-center">Matchmaking</th><th class="col-center">Dev</th><th class="col-center">Active</th><th>Actions</th>
          </tr></thead>
          <tbody>${modes.map(m => `<tr id="mode-row-${m.modeKey}">
            <td><code class="text-cyan">${m.modeKey}</code></td>
            <td><input type="text" id="mname-${m.modeKey}" value="${m.displayName || ''}" class="form-control sm" style="width:110px"></td>
            <td><input type="text" id="mdesc-${m.modeKey}" value="${m.description || ''}" class="form-control sm" style="width:140px"></td>
            <td class="text-sm text-center">${m.playersPerTeam}</td>
            <td><input type="number" id="minelo-${m.modeKey}" value="${m.minElo ?? 0}" class="form-control sm" style="width:70px"></td>
            <td><input type="number" id="maxelo-${m.modeKey}" value="${m.maxElo ?? 9999}" class="form-control sm" style="width:70px"></td>
            <td class="col-center"><input type="checkbox" ${m.allowFill ? 'checked' : ''} id="mfill-${m.modeKey}"></td>
            <td>
              <select class="form-control sm" id="mstatus-${m.modeKey}">
                ${['AVAILABLE', 'COMING_SOON', 'DISABLED'].map(s => `<option value="${s}" ${m.modeStatus === s ? 'selected' : ''}>${s}</option>`).join('')}
              </select>
            </td>
            <td class="col-center"><input type="checkbox" ${m.matchmakingEnabled ? 'checked' : ''} id="mmatch-${m.modeKey}"></td>
            <td class="col-center"><input type="checkbox" ${m.isDevMode ? 'checked' : ''} id="mdev-${m.modeKey}"></td>
            <td class="col-center"><input type="checkbox" ${m.isActive ? 'checked' : ''} id="mact-${m.modeKey}"></td>
            <td><button class="btn btn-success btn-xs" onclick="saveModeConfig('${m.modeKey}')">Save</button></td>
          </tr>`).join('')}
          </tbody>
        </table>
      </div>
    </div>`;
  } catch (e) { $('config-body').innerHTML = `<div class="card card-body text-red">${e.message}</div>`; }
}

async function saveModeConfig(key) {
  const minElo = parseInt($('minelo-' + key).value);
  const maxElo = parseInt($('maxelo-' + key).value);
  if (isNaN(minElo) || isNaN(maxElo) || minElo < 0 || maxElo < minElo) {
    showAlert('ELO values invalid: min must be ≥ 0 and max must be ≥ min', 'error'); return;
  }
  const patch = {
    displayName:       $('mname-'  + key).value.trim() || undefined,
    description:       $('mdesc-'  + key).value.trim() || undefined,
    modeStatus:        $('mstatus-'+ key).value,
    matchmakingEnabled:$('mmatch-' + key).checked,
    allowFill:         $('mfill-'  + key).checked,
    minElo,
    maxElo,
    isDevMode:         $('mdev-'   + key).checked,
    isActive:          $('mact-'   + key).checked
  };
  if (!patch.displayName) delete patch.displayName;
  if (!patch.description) delete patch.description;
  try {
    await api('PATCH', `/api/admin/config/modes/${key}`, patch);
    showAlert(`Mode "${key}" saved`, 'success');
  } catch (e) { showAlert(e.message, 'error'); }
}

/* ── Maps ───────────────────────────────────────────────────────────────── */
async function loadMapsConfig() {
  $('config-body').innerHTML = '<div style="text-align:center;padding:3rem"><span class="spinner"></span></div>';
  try {
    const r = await api('GET', '/api/admin/config/maps');
    const maps = r.data || r;
    $('config-body').innerHTML = `
    <div class="card mb-md">
      <div class="card-header">
        <span class="card-title">&#128506; Maps</span>
        <span class="text-muted text-xs">${maps.length} total</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr>
            <th>Map ID</th><th>Scene</th><th>Display Name</th><th>Supported Modes</th>
            <th>Order</th><th class="col-center">Active</th><th class="col-center">Locked</th><th>Actions</th>
          </tr></thead>
          <tbody>${maps.map(m => `<tr>
            <td><code class="text-cyan text-xs">${m.mapId}</code></td>
            <td><input type="text" id="mscene-${m.mapId}" value="${m.sceneName || ''}" class="form-control sm" style="width:130px"></td>
            <td><input type="text" id="mname-${m.mapId}" value="${m.displayName || ''}" class="form-control sm" style="width:140px"></td>
            <td><input type="text" id="mmodes-${m.mapId}" value="${(m.supportedModes || []).join(', ')}" class="form-control sm" placeholder="2v2,3v3" style="width:130px"></td>
            <td><input type="number" id="mord-${m.mapId}" value="${m.displayOrder || 0}" class="form-control sm" style="width:60px"></td>
            <td class="col-center"><input type="checkbox" ${m.isActive ? 'checked' : ''} id="mactive-${m.mapId}"></td>
            <td class="col-center"><input type="checkbox" ${m.isLocked ? 'checked' : ''} id="mlocked-${m.mapId}"></td>
            <td><button class="btn btn-success btn-xs" onclick="saveMapConfig('${m.mapId}')">Save</button></td>
          </tr>`).join('')}
          </tbody>
        </table>
      </div>
    </div>
    <div class="card">
      <div class="card-header"><span class="card-title">&#10133; Add New Map</span></div>
      <div class="card-body">
        <div class="form-row">
          <div class="form-group">
            <label class="form-label">Map ID</label>
            <input id="new-map-id" class="form-control" placeholder="map_03">
          </div>
          <div class="form-group">
            <label class="form-label">Scene Name</label>
            <input id="new-map-scene" class="form-control" placeholder="GameMap_03">
          </div>
          <div class="form-group">
            <label class="form-label">Display Name</label>
            <input id="new-map-name" class="form-control" placeholder="New Map">
          </div>
          <div class="form-group">
            <label class="form-label">Modes (comma-sep)</label>
            <input id="new-map-modes" class="form-control" placeholder="2v2,3v3">
          </div>
          <div class="form-group" style="align-self:flex-end">
            <button class="btn btn-success" onclick="addMap()">&#10133; Add Map</button>
          </div>
        </div>
      </div>
    </div>`;
  } catch (e) { $('config-body').innerHTML = `<div class="card card-body text-red">${e.message}</div>`; }
}

async function saveMapConfig(mapId) {
  const modesRaw = $('mmodes-' + mapId).value.trim();
  const patch = {
    displayName: $('mname-' + mapId).value,
    sceneName: $('mscene-' + mapId).value.trim() || undefined,
    supportedModes: modesRaw ? modesRaw.split(',').map(s => s.trim()).filter(Boolean) : [],
    displayOrder: parseInt($('mord-' + mapId).value) || 0,
    isActive: $('mactive-' + mapId).checked,
    isLocked: $('mlocked-' + mapId).checked
  };
  if (!patch.sceneName) delete patch.sceneName;
  try {
    await api('PATCH', `/api/admin/config/maps/${mapId}`, patch);
    showAlert(`Map "${mapId}" saved`, 'success');
  } catch (e) { showAlert(e.message, 'error'); }
}

async function addMap() {
  const mapId       = $('new-map-id').value.trim();
  const sceneName   = $('new-map-scene').value.trim();
  const displayName = $('new-map-name').value.trim();
  const modesRaw    = $('new-map-modes').value.trim();
  if (!mapId || !sceneName || !displayName) {
    showAlert('Map ID, Scene Name and Display Name are required', 'error'); return;
  }
  try {
    await api('POST', '/api/admin/config/maps', {
      mapId, sceneName, displayName,
      supportedModes: modesRaw ? modesRaw.split(',').map(s => s.trim()).filter(Boolean) : []
    });
    showAlert(`Map "${mapId}" added`, 'success');
    loadMapsConfig();
  } catch (e) { showAlert(e.message, 'error'); }
}

/* ── Runtime Config ─────────────────────────────────────────────────────── */
async function loadRuntimeConfig() {
  $('config-body').innerHTML = '<div style="text-align:center;padding:3rem"><span class="spinner"></span></div>';
  try {
    const r = await api('GET', '/api/admin/config/runtime');
    const entries = r.data || r;
    const typeColor = t => t === 'INT' ? 'var(--cyan)' : t === 'FLOAT' ? 'var(--yellow)' : t === 'BOOL' ? 'var(--green)' : t === 'JSON' ? 'var(--purple)' : 'var(--muted)';
    $('config-body').innerHTML = `
    <div class="card">
      <div class="card-header">
        <span class="card-title">&#128295; Runtime Config</span>
        <span class="text-muted text-xs">${entries.length} keys — changes take effect immediately</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>Key</th><th>Type</th><th>Value</th><th>Description</th><th>Updated</th><th>Actions</th></tr></thead>
          <tbody>${entries.map(e => `<tr>
            <td><code class="text-cyan text-sm">${e.configKey}</code></td>
            <td><span class="badge badge-muted" style="color:${typeColor(e.valueType)}">${e.valueType}</span></td>
            <td><input type="text" id="rval-${e.configKey.replace(/\./g, '_')}" value="${e.configValue}" class="form-control sm" style="width:120px"></td>
            <td class="text-xs text-muted" style="max-width:200px">${e.description || '—'}</td>
            <td class="text-xs text-muted">${fmtDate(e.updatedAt)}</td>
            <td><button class="btn btn-success btn-xs" onclick="saveRuntimeConfig('${e.configKey}')">Save</button></td>
          </tr>`).join('')}
          </tbody>
        </table>
      </div>
    </div>`;
  } catch (e) { $('config-body').innerHTML = `<div class="card card-body text-red">${e.message}</div>`; }
}

async function saveRuntimeConfig(key) {
  const inputId = 'rval-' + key.replace(/\./g, '_');
  const val = $(inputId);
  if (!val) return;
  try {
    await api('PATCH', `/api/admin/config/runtime/${key}`, { value: val.value });
    showAlert(`Config "${key}" updated`, 'success');
  } catch (e) { showAlert(e.message, 'error'); }
}
