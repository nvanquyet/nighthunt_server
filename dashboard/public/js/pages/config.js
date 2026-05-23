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
            <td style="white-space:nowrap">
              <button class="btn btn-success btn-xs" onclick="saveMapConfig('${m.mapId}')">Save</button>
              <button class="btn btn-xs" style="margin-left:4px;background:var(--accent-blue,#2563eb);color:#fff" onclick="openZoneEditor('${m.mapId}')">&#9881; Zone</button>
            </td>
          </tr>`).join('')}
          </tbody>
        </table>
      </div>
    </div>
    <div id="zone-editor-panel" style="display:none" class="card mb-md">
      <div class="card-header" style="display:flex;align-items:center;justify-content:space-between">
        <span class="card-title">&#9881; Zone Config — <span id="zone-editor-mapid" style="font-family:monospace;color:var(--accent-cyan,#06b6d4)"></span></span>
        <button class="btn btn-xs" onclick="$('zone-editor-panel').style.display='none'" style="background:transparent;color:var(--text-muted,#888)">&#10005; Close</button>
      </div>
      <div class="card-body">
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:12px">
          <div style="background:var(--bg-darker,#0f172a);border:1px solid var(--border,#334);border-radius:6px;padding:12px">
            <div class="text-xs" style="color:var(--text-muted,#888);margin-bottom:8px;font-weight:600;text-transform:uppercase">&#127760; Zone Shape</div>
            <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px">
              <div class="form-group"><label class="form-label text-xs">Initial Radius</label><input type="number" id="zc-initialRadius" step="1" min="0" class="form-control sm" style="width:100%"></div>
              <div class="form-group"><label class="form-label text-xs">Final Min Radius</label><input type="number" id="zc-finalZoneMinRadius" step="1" min="0" class="form-control sm" style="width:100%"></div>
            </div>
          </div>
          <div style="background:var(--bg-darker,#0f172a);border:1px solid var(--border,#334);border-radius:6px;padding:12px">
            <div class="text-xs" style="color:var(--text-muted,#888);margin-bottom:8px;font-weight:600;text-transform:uppercase">&#127919; Center Mode</div>
            <div class="form-group" style="margin-bottom:8px"><label class="form-label text-xs">Mode</label>
              <select id="zc-centerMode" class="form-control sm" style="width:100%">
                <option value="PureRandom">PureRandom — PUBG-style (random within prev zone)</option>
                <option value="CenterBiased">CenterBiased — biased toward map center</option>
                <option value="Fixed">Fixed — no movement (1v1 arenas)</option>
              </select>
            </div>
            <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px">
              <div class="form-group"><label class="form-label text-xs">Max Shift <span class="text-muted">(0–1)</span></label><input type="number" id="zc-maxCenterShiftPercent" step="0.05" min="0" max="1" class="form-control sm" style="width:100%"></div>
              <div class="form-group"><label class="form-label text-xs">Min Shift <span class="text-muted">(0–1)</span></label><input type="number" id="zc-minCenterShiftPercent" step="0.05" min="0" max="1" class="form-control sm" style="width:100%"></div>
            </div>
          </div>
          <div style="background:var(--bg-darker,#0f172a);border:1px solid var(--border,#334);border-radius:6px;padding:12px">
            <div class="text-xs" style="color:var(--text-muted,#888);margin-bottom:8px;font-weight:600;text-transform:uppercase">&#128055; Respawn</div>
            <label style="display:flex;align-items:center;gap:8px;cursor:pointer">
              <input type="checkbox" id="zc-beaconAllowedInFinalZone">
              <span class="text-sm">Beacon Allowed in Final Zone</span>
            </label>
          </div>
          <div style="background:var(--bg-darker,#0f172a);border:1px solid var(--border,#334);border-radius:6px;padding:12px">
            <div class="text-xs" style="color:var(--text-muted,#888);margin-bottom:8px;font-weight:600;text-transform:uppercase">&#127942; Scoring</div>
            <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px">
              <div class="form-group"><label class="form-label text-xs">Survival pts/s</label><input type="number" id="zc-baseSurvivalPtsPerSecond" step="0.1" min="0" class="form-control sm" style="width:100%"></div>
              <div class="form-group"><label class="form-label text-xs">Capture zone pts/s</label><input type="number" id="zc-captureZoneScorePerSecond" step="0.5" min="0" class="form-control sm" style="width:100%"></div>
              <div class="form-group"><label class="form-label text-xs">Kill score</label><input type="number" id="zc-killScore" step="1" min="0" class="form-control sm" style="width:100%"></div>
              <div class="form-group"><label class="form-label text-xs">Boss kill score</label><input type="number" id="zc-bossKillScore" step="1" min="0" class="form-control sm" style="width:100%"></div>
              <div class="form-group" style="grid-column:span 2"><label class="form-label text-xs">Kill steal % — final zone <span class="text-muted">(0–1)</span></label><input type="number" id="zc-killScoreStealPercent" step="0.01" min="0" max="1" class="form-control sm" style="width:100%"></div>
            </div>
          </div>
        </div>
        <div style="background:var(--bg-darker,#0f172a);border:1px solid var(--border,#334);border-radius:6px;padding:12px;margin-bottom:12px">
          <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
            <span class="text-xs" style="color:var(--text-muted,#888);font-weight:600;text-transform:uppercase">&#127384; Phases</span>
            <button class="btn btn-xs btn-success" onclick="addZonePhase()">&#10133; Add Phase</button>
          </div>
          <div class="table-wrap">
            <table>
              <thead><tr>
                <th>#</th><th>Start R</th><th>End R</th><th>Wait (s)</th><th>Shrink (s)</th>
                <th>DMG/s</th><th>Tick (s)</th><th class="col-center">Score Bonus</th><th>Bonus Mult</th><th>Min R Override</th><th></th>
              </tr></thead>
              <tbody id="zc-phases-body"></tbody>
            </table>
          </div>
        </div>
        <div>
          <button class="btn btn-success" onclick="saveZoneConfig()">&#128190; Save Zone Config</button>
        </div>
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

/* ── Zone Config Editor ─────────────────────────────────────────────────── */
let _zoneEditorMapId = null;
let _zonePhases      = [];

function _zcEl(id)          { return document.getElementById(id); }
function _zcNum(id, def=0)  { const v = parseFloat(_zcEl(id)?.value); return isNaN(v) ? def : v; }
function _zcBool(id)        { return !!_zcEl(id)?.checked; }
function _zcStr(id, def='') { return _zcEl(id)?.value ?? def; }

async function openZoneEditor(mapId) {
  _zoneEditorMapId = mapId;
  _zcEl('zone-editor-mapid').textContent = mapId;
  $('zone-editor-panel').style.display = '';
  $('zone-editor-panel').scrollIntoView({ behavior: 'smooth', block: 'nearest' });

  let cfg = {};
  try {
    const resp = await fetch(`/api/maps/${encodeURIComponent(mapId)}/zone-config`, {
      headers: { 'Authorization': 'Bearer ' + (localStorage.getItem('nh_token') || '') }
    });
    if (resp.ok && resp.status !== 204) cfg = await resp.json();
  } catch (_) {}

  _zcEl('zc-initialRadius').value           = cfg.initialRadius           ?? 400;
  _zcEl('zc-finalZoneMinRadius').value       = cfg.finalZoneMinRadius       ?? 25;
  _zcEl('zc-centerMode').value              = cfg.centerMode              || 'PureRandom';
  _zcEl('zc-maxCenterShiftPercent').value   = cfg.maxCenterShiftPercent   ?? 0.6;
  _zcEl('zc-minCenterShiftPercent').value   = cfg.minCenterShiftPercent   ?? 0.1;
  _zcEl('zc-beaconAllowedInFinalZone').checked = cfg.beaconAllowedInFinalZone ?? false;
  _zcEl('zc-baseSurvivalPtsPerSecond').value  = cfg.baseSurvivalPtsPerSecond  ?? 1;
  _zcEl('zc-captureZoneScorePerSecond').value = cfg.captureZoneScorePerSecond ?? 20;
  _zcEl('zc-killScore').value               = cfg.killScore               ?? 100;
  _zcEl('zc-bossKillScore').value           = cfg.bossKillScore           ?? 300;
  _zcEl('zc-killScoreStealPercent').value   = cfg.killScoreStealPercent   ?? 0.15;

  _zonePhases = (cfg.phases || []).map(p => ({ ...p }));
  _renderZonePhases();
}

function _renderZonePhases() {
  const tbody = _zcEl('zc-phases-body');
  if (!tbody) return;
  tbody.innerHTML = _zonePhases.map((p, i) => `
    <tr>
      <td><span class="badge badge-muted">${i}</span></td>
      <td><input type="number" id="zcp-sr-${i}"  value="${p.startRadius  ?? 0}"  step="1"    min="0" class="form-control sm" style="width:68px"></td>
      <td><input type="number" id="zcp-er-${i}"  value="${p.endRadius    ?? 0}"  step="1"    min="0" class="form-control sm" style="width:68px"></td>
      <td><input type="number" id="zcp-wbs-${i}" value="${p.waitBeforeShrink ?? 60}" step="5" min="0" class="form-control sm" style="width:62px"></td>
      <td><input type="number" id="zcp-sd-${i}"  value="${p.shrinkDuration ?? 90}" step="5"  min="0" class="form-control sm" style="width:62px"></td>
      <td><input type="number" id="zcp-dps-${i}" value="${p.damagePerSecond ?? 5}" step="0.5" min="0" class="form-control sm" style="width:58px"></td>
      <td><input type="number" id="zcp-dt-${i}"  value="${p.damageTick   ?? 1}"  step="0.5" min="0" class="form-control sm" style="width:52px"></td>
      <td class="col-center"><input type="checkbox" id="zcp-sbz-${i}" ${p.isScoreBonusZone ? 'checked' : ''}></td>
      <td><input type="number" id="zcp-bm-${i}"  value="${p.zoneBonusMultiplier ?? 1.5}" step="0.1" min="1" class="form-control sm" style="width:58px"></td>
      <td><input type="number" id="zcp-mro-${i}" value="${p.minRadiusOverride ?? 0}" step="1" min="0" class="form-control sm" style="width:68px" title="0 = use global finalZoneMinRadius"></td>
      <td><button class="btn btn-xs" style="background:#dc2626;color:#fff" onclick="removeZonePhase(${i})">&#10005;</button></td>
    </tr>`).join('');
}

function addZonePhase() {
  const last = _zonePhases[_zonePhases.length - 1];
  _zonePhases.push({
    zoneIndex: _zonePhases.length,
    startRadius: last ? (last.endRadius ?? 50) : 100,
    endRadius:   last ? Math.max(5, (last.endRadius ?? 50) - 25) : 50,
    waitBeforeShrink: 60, shrinkDuration: 90,
    damagePerSecond: 5, damageTick: 1,
    isScoreBonusZone: false, zoneBonusMultiplier: 1.5, minRadiusOverride: 0
  });
  _renderZonePhases();
}

function removeZonePhase(idx) {
  _zonePhases.splice(idx, 1);
  _zonePhases.forEach((p, i) => { p.zoneIndex = i; });
  _renderZonePhases();
}

async function saveZoneConfig() {
  if (!_zoneEditorMapId) return;
  const phases = _zonePhases.map((_, i) => ({
    zoneIndex:         i,
    startRadius:       _zcNum(`zcp-sr-${i}`),
    endRadius:         _zcNum(`zcp-er-${i}`),
    waitBeforeShrink:  _zcNum(`zcp-wbs-${i}`, 60),
    shrinkDuration:    _zcNum(`zcp-sd-${i}`, 90),
    damagePerSecond:   _zcNum(`zcp-dps-${i}`, 5),
    damageTick:        _zcNum(`zcp-dt-${i}`, 1),
    isScoreBonusZone:  _zcBool(`zcp-sbz-${i}`),
    zoneBonusMultiplier: _zcNum(`zcp-bm-${i}`, 1.5),
    minRadiusOverride: _zcNum(`zcp-mro-${i}`)
  }));
  const payload = {
    initialRadius:            _zcNum('zc-initialRadius', 400),
    finalZoneMinRadius:       _zcNum('zc-finalZoneMinRadius', 25),
    centerMode:               _zcStr('zc-centerMode', 'PureRandom'),
    maxCenterShiftPercent:    _zcNum('zc-maxCenterShiftPercent', 0.6),
    minCenterShiftPercent:    _zcNum('zc-minCenterShiftPercent', 0.1),
    beaconAllowedInFinalZone: _zcBool('zc-beaconAllowedInFinalZone'),
    baseSurvivalPtsPerSecond: _zcNum('zc-baseSurvivalPtsPerSecond', 1),
    captureZoneScorePerSecond:_zcNum('zc-captureZoneScorePerSecond', 20),
    killScore:                _zcNum('zc-killScore', 100),
    bossKillScore:            _zcNum('zc-bossKillScore', 300),
    killScoreStealPercent:    _zcNum('zc-killScoreStealPercent', 0.15),
    phases
  };
  try {
    await api('PATCH', `/api/admin/config/maps/${_zoneEditorMapId}/zone`, payload);
    showAlert(`Zone config for "${_zoneEditorMapId}" saved`, 'success');
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
