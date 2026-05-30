// loadtest.js — Load Test Reports page
// Charts: DS Capacity (concurrent DS vs batch) + JMeter (TPS + response time dual-Y)
// Requires Chart.js 4 loaded before this file.

(function () {
    'use strict';

    // ── Helpers ──────────────────────────────────────────────────────────────
    function fmtMs(ms) {
        if (ms == null || isNaN(ms)) return '–';
        return ms >= 1000 ? (ms / 1000).toFixed(2) + 's' : Math.round(ms) + 'ms';
    }
    function fmtPct(p) { return (p == null) ? '–' : (+p).toFixed(1) + '%'; }
    function badge(val, warn, danger) {
        const cls = val >= danger ? 'badge-danger' : val >= warn ? 'badge-warn' : 'badge-ok';
        return `<span class="lt-badge ${cls}">${val}</span>`;
    }

    let jmeterChart = null;
    let capacityChart = null;
    let selectedScenarioIdx = -1;

    // ── Main render ──────────────────────────────────────────────────────────
    window.renderLoadtest = async function () {
        const el = document.getElementById('content');
        if (!el) return;

        el.innerHTML = `
<div id="page-loadtest">
<div class="page-header" style="display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:10px">
  <div>
    <h1>&#128200; Load Test Reports</h1>
    <p class="page-subtitle">DS Capacity &amp; JMeter stress test results</p>
  </div>
  <div style="display:flex;gap:8px;flex-wrap:wrap">
    <button class="lt-btn lt-btn-ghost" onclick="ltRefresh()" id="lt-refresh-btn">&#8635; Refresh</button>
    <button class="lt-btn lt-btn-run"  onclick="ltRunCapacity()" id="lt-run-cap-btn">&#9654; Run DS Capacity Test</button>
  </div>
</div>

<!-- Run output console -->
<div id="lt-run-console" class="lt-console" style="display:none">
  <div class="lt-console-header">
    <span id="lt-run-title">Running test…</span>
    <span id="lt-run-status" class="lt-badge badge-loading">Running</span>
  </div>
  <pre id="lt-run-log" class="lt-log"></pre>
</div>

<!-- Rate limit toggle card -->
<div class="lt-section">
  <div class="lt-card" id="lt-rl-card">
    <div class="lt-card-header">
      <span class="lt-card-icon">&#128274;</span>
      <h3>Backend Rate Limiting</h3>
      <span id="lt-rl-status" class="lt-badge badge-loading">Loading...</span>
    </div>
    <p class="lt-desc">Controls IP-based rate limiting on the backend. Disable during load tests, keep enabled in production.</p>
    <div class="lt-actions">
      <button id="lt-rl-toggle" class="lt-btn" onclick="ltToggleRateLimit()">Loading...</button>
      <span id="lt-rl-msg" class="lt-msg"></span>
    </div>
  </div>
</div>

<!-- Stat cards -->
<div class="lt-section">
  <h2 class="lt-section-title">Summary</h2>
  <div class="lt-stat-grid" id="lt-stats">
    <div class="lt-stat-card"><div class="lt-stat-val" id="st-peak-ds">–</div><div class="lt-stat-label">Peak Concurrent DS</div></div>
    <div class="lt-stat-card"><div class="lt-stat-val" id="st-peak-tps">–</div><div class="lt-stat-label">Peak TPS</div></div>
    <div class="lt-stat-card"><div class="lt-stat-val" id="st-avg-resp">–</div><div class="lt-stat-label">Avg Response Time</div></div>
    <div class="lt-stat-card"><div class="lt-stat-val" id="st-error-rate">–</div><div class="lt-stat-label">Error Rate</div></div>
    <div class="lt-stat-card"><div class="lt-stat-val" id="st-total-req">–</div><div class="lt-stat-label">Total Requests</div></div>
  </div>
</div>

<!-- JMeter section -->
<div class="lt-section">
  <div class="lt-card-header" style="margin-bottom:12px">
    <h2 class="lt-section-title" style="margin:0">&#9889; JMeter Stress Test</h2>
    <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
      <select id="lt-jtl-sel" class="lt-select" onchange="ltSelectJtl()" title="Select JTL for chart"></select>
    </div>
  </div>
    <div class="lt-card lt-help-card" style="margin-bottom:12px">
        <div class="lt-help-grid">
            <div>
                <strong>Chart meaning</strong>
                <div class="lt-help-text">Blue line = average response time, yellow line = TPS, red dashed line = error rate, grey bars = active users. The chart shows only the currently selected scenario/JTL, not an aggregate of all scenarios.</div>
            </div>
            <div>
                <strong>Raw vs steady-state</strong>
                <div class="lt-help-text"><code>-raw.jtl</code> includes warm-up login/register traffic. Normal <code>.jtl</code> is filtered steady-state traffic and is what the HTML report/statistics use. If a scenario is stuck or fails before report generation, you may only see <code>raw</code>.</div>
            </div>
        </div>
    </div>
    <div style="margin-bottom:10px;padding:8px 12px;background:rgba(99,179,237,0.08);border:1px solid rgba(99,179,237,0.3);border-radius:6px;font-size:0.82rem;color:#90cdf4">
      &#128295; Run JMeter from an external machine to avoid overloading the VPS.
      See <strong>load-tests/jmeter/EXTERNAL_MACHINE_GUIDE.md</strong> for setup, commands, and filtering steps.
    </div>
    <div id="lt-jmeter-selected" class="lt-selected-summary" style="display:none"></div>
  <div class="lt-chart-wrap">
    <canvas id="lt-jmeter-chart" height="100"></canvas>
  </div>
  <div id="lt-jmeter-table" class="lt-table-wrap" style="margin-top:16px"></div>
</div>

<!-- Upload Load Test Graphs -->
<div class="lt-section">
  <div class="lt-card-header" style="margin-bottom:12px">
    <h2 class="lt-section-title" style="margin:0">&#128247; Upload Load Test Graphs</h2>
  </div>
  <div style="display:flex;gap:10px;align-items:flex-end;flex-wrap:wrap;margin-bottom:14px">
    <div style="flex:1;min-width:160px">
      <label style="display:block;font-size:0.78rem;color:#a0aec0;margin-bottom:4px">Label (optional)</label>
      <input id="lt-graph-label" type="text" placeholder="e.g. 1000vu-2026-05-30"
        style="width:100%;padding:6px 10px;background:#1a2035;border:1px solid #2d3748;border-radius:5px;color:#e2e8f0;font-size:0.85rem">
    </div>
    <div style="flex:2;min-width:200px">
      <label style="display:block;font-size:0.78rem;color:#a0aec0;margin-bottom:4px">PNG / JPG / WEBP (max 10 MB)</label>
      <input id="lt-graph-file" type="file" accept="image/png,image/jpeg,image/webp,image/gif"
        style="width:100%;padding:5px 10px;background:#1a2035;border:1px solid #2d3748;border-radius:5px;color:#e2e8f0;font-size:0.85rem">
    </div>
    <button class="lt-btn lt-btn-run" style="font-size:0.85rem;padding:6px 16px;white-space:nowrap" onclick="ltUploadGraph()">&#8593; Upload</button>
  </div>
  <div id="lt-graph-upload-msg" style="min-height:18px;font-size:0.82rem;margin-bottom:10px"></div>
  <div id="lt-graph-gallery" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:12px"></div>
</div>

<!-- DS Capacity chart -->
<div class="lt-section">
  <div class="lt-card-header" style="margin-bottom:8px">
    <h2 class="lt-section-title" style="margin:0">DS Capacity Test</h2>
    <button class="lt-btn lt-btn-run" style="font-size:0.8rem;padding:4px 12px" onclick="ltRunCapacity()">&#9654; Run New Test</button>
  </div>
  <div class="lt-chart-wrap">
    <canvas id="lt-capacity-chart" height="100"></canvas>
  </div>
  <div id="lt-capacity-table" class="lt-table-wrap" style="margin-top:16px"></div>
</div>
</div>`;

        await ltLoadData();
    };

    async function ltLoadData() {
        ltRenderRateLimitStatus();
        const [jmeterData, capacityData] = await Promise.all([
            ltFetch('/api/loadtest/jmeter').catch(e => ({ error: e.message })),
            ltFetch('/api/loadtest/capacity').catch(e => ({ error: e.message }))
        ]);
        ltRenderJmeter(jmeterData);
        ltRenderCapacity(capacityData);
        await ltLoadGraphs();
    }

    window.ltRefresh = async function () {
        const btn = document.getElementById('lt-refresh-btn');
        if (btn) { btn.disabled = true; btn.textContent = '⏳ Loading…'; }
        await ltLoadData();
        if (btn) { btn.disabled = false; btn.textContent = '↻ Refresh'; }
    };

    window.ltRunCapacity = async function () {
        const consoleEl = document.getElementById('lt-run-console');
        const logEl     = document.getElementById('lt-run-log');
        const titleEl   = document.getElementById('lt-run-title');
        const statusEl  = document.getElementById('lt-run-status');
        const runBtns   = document.querySelectorAll('[onclick*="ltRunCapacity"]');

        if (!consoleEl || !logEl) return;
        consoleEl.style.display = 'block';
        logEl.textContent = '';
        if (titleEl)  titleEl.textContent = 'Running DS Capacity Test…';
        if (statusEl) { statusEl.textContent = 'Running'; statusEl.className = 'lt-badge badge-loading'; }
        runBtns.forEach(b => b.disabled = true);
        consoleEl.scrollIntoView({ behavior: 'smooth', block: 'start' });

        const token = ltGetToken();
        try {
            const resp = await fetch('/api/loadtest/run/capacity', {
                method: 'POST',
                headers: Object.assign({ 'Content-Type': 'application/json' }, token ? { 'Authorization': 'Bearer ' + token } : {})
            });
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            const { jobId } = await resp.json();
            ltStreamJob(jobId, token, logEl, statusEl, async () => {
                logEl.textContent += '\n✅ Test completed — refreshing reports…\n';
                await ltLoadData();
                runBtns.forEach(b => b.disabled = false);
            });
        } catch (e) {
            if (statusEl) { statusEl.textContent = 'Error'; statusEl.className = 'lt-badge badge-danger'; }
            logEl.textContent += 'Error: ' + e.message + '\n';
            runBtns.forEach(b => b.disabled = false);
        }
    };

    // ── API fetch helper ─────────────────────────────────────────────────────
    function ltGetToken() {
        return (typeof TOKEN !== 'undefined' && TOKEN) ? TOKEN : sessionStorage.getItem('nh_token');
    }
    async function ltFetch(url) {
        const token = ltGetToken();
        const res = await fetch(url, { headers: token ? { 'Authorization': 'Bearer ' + token } : {} });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
    }
    async function ltPost(url, body) {
        const token = ltGetToken();
        const res = await fetch(url, {
            method: 'POST',
            headers: Object.assign({ 'Content-Type': 'application/json' }, token ? { 'Authorization': 'Bearer ' + token } : {}),
            body: body ? JSON.stringify(body) : undefined
        });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
    }

    // ── Rate limit toggle ────────────────────────────────────────────────────
    async function ltRenderRateLimitStatus() {
        const statusEl = document.getElementById('lt-rl-status');
        const btn      = document.getElementById('lt-rl-toggle');
        if (!statusEl) return;
        try {
            const data = await ltFetch('/api/admin/rate-limit/status');
            const enabled = data.data ? data.data.enabled : data.enabled;
            statusEl.textContent = enabled ? 'ENABLED' : 'DISABLED';
            statusEl.className   = 'lt-badge ' + (enabled ? 'badge-ok' : 'badge-warn');
            if (btn) {
                btn.textContent  = enabled ? '🔴 Disable Rate Limit' : '🟢 Enable Rate Limit';
                btn.className    = 'lt-btn ' + (enabled ? 'lt-btn-danger' : 'lt-btn-ok');
            }
        } catch {
            if (statusEl) { statusEl.textContent = 'Error'; statusEl.className = 'lt-badge badge-danger'; }
            if (btn) { btn.textContent = 'Unavailable'; btn.disabled = true; }
        }
    }

    window.ltToggleRateLimit = async function () {
        const btn    = document.getElementById('lt-rl-toggle');
        const msgEl  = document.getElementById('lt-rl-msg');
        if (btn) btn.disabled = true;
        try {
            const data = await ltPost('/api/admin/rate-limit/toggle');
            if (msgEl) {
                const enabled = data.data ? data.data.enabled : data.enabled;
                msgEl.textContent = enabled ? 'Rate limiting enabled.' : 'Rate limiting disabled.';
                msgEl.className = 'lt-msg ' + (enabled ? 'lt-msg-ok' : 'lt-msg-warn');
            }
            await ltRenderRateLimitStatus();
        } catch (e) {
            if (msgEl) { msgEl.textContent = 'Failed: ' + e.message; msgEl.className = 'lt-msg lt-msg-err'; }
        }
        if (btn) btn.disabled = false;
    };

    // ── JMeter render ────────────────────────────────────────────────────────
    let allScenarios  = [];
    let allJtlFiles   = [];
    let currentJtlIdx = 0;

    function ltRenderJmeter(data) {
        const tableEl = document.getElementById('lt-jmeter-table');
        if (!data || data.error) {
            if (tableEl) tableEl.innerHTML = '<p class="lt-empty">No JMeter data available.</p>';
            return;
        }

        allScenarios = data.scenarios || [];

        // Build full JTL list: scenario chart files first, then standalone JTLs
        allJtlFiles = [];
        allScenarios.forEach((s, idx) => {
            if (s.chartFile) {
                const mode = s.reportReady ? 'steady-state' : (s.rawJtlFile ? 'raw only' : 'no report');
                allJtlFiles.push({ label: `${s.name} (${mode})`, file: s.chartFile, scenarioIdx: idx });
            }
        });
        (data.standaloneJtls || []).forEach(f => {
            if (!allJtlFiles.find(x => x.file === f)) allJtlFiles.push({ label: f.replace('.jtl',''), file: f });
        });

        // JTL selector
        const sel = document.getElementById('lt-jtl-sel');
        if (sel) {
            sel.innerHTML = allJtlFiles.length
                ? allJtlFiles.map((x, i) => `<option value="${i}">${x.label}</option>`).join('')
                : '<option>No JTL files</option>';
        }

        const preferredIdx = allJtlFiles.findIndex(x => x.scenarioIdx != null && allScenarios[x.scenarioIdx]?.reportReady);
        currentJtlIdx = preferredIdx >= 0 ? preferredIdx : 0;
        if (sel && currentJtlIdx >= 0) sel.value = String(currentJtlIdx);

        const preferredScenarioIdx = allJtlFiles[currentJtlIdx]?.scenarioIdx;
        if (preferredScenarioIdx != null) selectedScenarioIdx = preferredScenarioIdx;

        ltDrawJmeterSummaryTable();
        ltRenderSelectedScenarioSummary();
        ltDrawJmeterChart(data.timeSeries, allJtlFiles[currentJtlIdx]);
    }

    window.ltSelectJtl = async function () {
        const sel = document.getElementById('lt-jtl-sel');
        if (!sel) return;
        currentJtlIdx = +sel.value;
        const entry = allJtlFiles[currentJtlIdx];
        if (!entry) return;
        if (entry.scenarioIdx != null) selectedScenarioIdx = entry.scenarioIdx;
        ltDrawJmeterSummaryTable();
        ltRenderSelectedScenarioSummary();
        try {
            const ts = await ltFetch('/api/loadtest/jmeter/series/' + encodeURIComponent(entry.file));
            ltDrawJmeterChart(ts, entry);
        } catch (e) {
            const canvas = document.getElementById('lt-jmeter-chart');
            if (canvas) canvas.parentElement.innerHTML = '<p class="lt-empty">Could not load JTL: ' + e.message + '</p>';
        }
    };

    window.ltFocusScenario = async function (idx) {
        const scenario = allScenarios[idx];
        if (!scenario || !scenario.chartFile) return;
        const entryIdx = allJtlFiles.findIndex(x => x.scenarioIdx === idx);
        if (entryIdx >= 0) {
            currentJtlIdx = entryIdx;
            const sel = document.getElementById('lt-jtl-sel');
            if (sel) sel.value = String(entryIdx);
        }
        selectedScenarioIdx = idx;
        ltDrawJmeterSummaryTable();
        ltRenderSelectedScenarioSummary();
        try {
            const ts = await ltFetch('/api/loadtest/jmeter/series/' + encodeURIComponent(scenario.chartFile));
            ltDrawJmeterChart(ts, allJtlFiles[currentJtlIdx]);
        } catch (e) {
            const canvas = document.getElementById('lt-jmeter-chart');
            if (canvas) canvas.parentElement.innerHTML = '<p class="lt-empty">Could not load scenario chart: ' + e.message + '</p>';
        }
    };

    function ltDrawJmeterChart(ts, entry) {
        const wrap = document.querySelector('#lt-jmeter-chart')?.parentElement;
        const existingCanvas = document.getElementById('lt-jmeter-chart');
        if (!existingCanvas) return;
        if (jmeterChart) { jmeterChart.destroy(); jmeterChart = null; }
        if (typeof Chart === 'undefined') {
            if (wrap) wrap.innerHTML = '<p class="lt-empty">Chart.js đang bị chặn hoặc chưa tải xong. Bảng report và nút tải vẫn dùng được bên dưới.</p>';
            return;
        }

        // Restore canvas if it was replaced by an error message
        if (!document.getElementById('lt-jmeter-chart')) {
            wrap.innerHTML = '<canvas id="lt-jmeter-chart" height="100"></canvas>';
        }
        const canvas = document.getElementById('lt-jmeter-chart');
        if (!canvas) return;

        if (!ts || ts.error || !ts.buckets || ts.buckets.length === 0) {
            canvas.parentElement.innerHTML = '<p class="lt-empty">No time-series data for this JTL.</p>';
            return;
        }

        const labels  = ts.buckets.map(b => b.t + 's');
        const tpsData = ts.buckets.map(b => b.tps);
        const msData  = ts.buckets.map(b => b.avgMs);
        const vuData  = ts.buckets.map(b => b.threads);
        const errData = ts.buckets.map(b => b.errorPct);

        jmeterChart = new Chart(canvas.getContext('2d'), {
            type: 'bar',
            data: {
                labels,
                datasets: [
                    {
                        type: 'line', yAxisID: 'y2', label: 'Avg Response (ms)',
                        data: msData, borderColor: '#4e9af1', backgroundColor: 'rgba(78,154,241,0.12)',
                        tension: 0.3, fill: true, pointRadius: 3, borderWidth: 2,
                    },
                    {
                        type: 'line', yAxisID: 'y1', label: 'TPS',
                        data: tpsData, borderColor: '#f0c040', backgroundColor: 'rgba(240,192,64,0.10)',
                        tension: 0.3, fill: false, pointRadius: 3, borderWidth: 2,
                    },
                    {
                        type: 'line', yAxisID: 'y1', label: 'Error %',
                        data: errData, borderColor: '#e05555', backgroundColor: 'rgba(224,85,85,0.10)',
                        tension: 0.3, fill: false, pointRadius: 3, borderWidth: 1.5, borderDash: [4,3],
                    },
                    {
                        type: 'bar', yAxisID: 'y1', label: 'Active Users',
                        data: vuData, backgroundColor: 'rgba(150,150,180,0.18)', borderWidth: 0,
                    },
                ]
            },
            options: {
                responsive: true,
                interaction: { mode: 'index', intersect: false },
                plugins: {
                    legend: { position: 'top', labels: { color: '#ccc', boxWidth: 12 } },
                    title:  { display: true, text: (entry?.label || ts.fileName || '') + ' — Throughput & Response Times', color: '#ccc', font: { size: 13 } },
                    tooltip: {
                        callbacks: {
                            label: ctx => {
                                const v = ctx.parsed.y;
                                if (ctx.dataset.label.includes('ms')) return ctx.dataset.label + ': ' + fmtMs(v);
                                if (ctx.dataset.label.includes('TPS')) return 'TPS: ' + v.toFixed(1) + '/s';
                                if (ctx.dataset.label.includes('Error')) return 'Error: ' + v.toFixed(1) + '%';
                                return ctx.dataset.label + ': ' + v;
                            }
                        }
                    }
                },
                scales: {
                    x:  { ticks: { color: '#999', maxRotation: 0, autoSkip: true, maxTicksLimit: 20 }, grid: { color: '#1e2230' } },
                    y1: { type: 'linear', position: 'left',  title: { display: true, text: 'TPS / Users / Err%', color: '#aaa' }, ticks: { color: '#aaa' }, grid: { color: '#1e2230' }, min: 0 },
                    y2: { type: 'linear', position: 'right', title: { display: true, text: 'Response Time (ms)', color: '#4e9af1' }, ticks: { color: '#4e9af1', callback: v => fmtMs(v) }, grid: { drawOnChartArea: false }, min: 0 },
                }
            }
        });
    }

    function ltDrawJmeterSummaryTable() {
        const el = document.getElementById('lt-jmeter-table');
        if (!el) return;

        if (!allScenarios.length) {
            el.innerHTML = '<p class="lt-empty">No scenario reports found.</p>';
            return;
        }

        const rows = allScenarios.map((s, idx) => {
            const stats  = s.stats || {};
                        const statValues = Object.values(stats);
                        const total  = stats['Total'] || statValues[0] || null;
                        const peakTps = statValues.length ? Math.max(...statValues.map(v => v.throughput || 0)) : 0;
                        const errValue = total?.errorPct;
                        const errCls  = total
                                ? ((errValue || 0) > 20 ? 'style="color:#e07070"' : (errValue || 0) > 5 ? 'style="color:#f0c040"' : 'style="color:#7be495"')
                                : '';
                        const steadyJtlBtn   = s.jtlFile
                ? `<button class="lt-btn lt-btn-dl" onclick="ltDownload('jtl','${s.jtlFile}')" title="Download JTL">&#11123; JTL</button>`
                : '<span style="color:var(--muted);font-size:0.75rem">no JTL</span>';
                        const rawJtlBtn = s.rawJtlFile
                                ? `<button class="lt-btn lt-btn-dl" onclick="ltDownload('jtl','${s.rawJtlFile}')" title="Download raw JTL">&#11123; RAW</button>`
                                : '';
                        const logBtn = s.logFile
                                ? `<button class="lt-btn lt-btn-ghost" onclick="ltDownload('result','${s.logFile}')" title="Download jmeter log">&#128221; Log</button>`
                                : '';
                        const htmlBtn = s.reportReady
                                ? `<button class="lt-btn lt-btn-dl" onclick="ltOpenScenarioReport('${s.name}')" title="Open HTML report">&#128196; HTML</button>`
                                : '<span class="lt-status-pill lt-status-warn">raw only</span>';
                        const endpts  = Object.keys(stats).filter(k => k !== 'Total').length;
                        const chartMode = s.reportReady ? 'steady-state chart' : (s.rawJtlFile ? 'raw chart only' : 'no chart');
                        return `<tr class="lt-scenario-row ${idx === selectedScenarioIdx ? 'lt-scenario-row-active' : ''}">
    <td>
        <button class="lt-link-btn" onclick="ltFocusScenario(${idx})"><strong>${s.name}</strong></button><br>
        <span style="color:var(--muted);font-size:0.75rem">${s.reportReady ? `${endpts} endpoint${endpts!==1?'s':''}` : 'incomplete scenario'} · ${chartMode}</span>
    </td>
    <td>${total ? (total.sampleCount||0).toLocaleString() : '–'}</td>
    <td ${errCls}>${total ? fmtPct(total.errorPct) : 'raw only'}</td>
    <td>${total ? peakTps.toFixed(1) + ' /s' : '–'}</td>
    <td>${total ? fmtMs(total.meanResTime) : '–'}</td>
    <td>${total ? fmtMs(total.pct2ResTime) : '–'}</td>
    <td>${total ? fmtMs(total.pct3ResTime) : '–'}</td>
  <td>
    <div style="display:flex;gap:6px;flex-wrap:wrap">
            ${s.reportReady ? `<button class="lt-btn lt-btn-dl" onclick="ltDownload('scenario','${s.name}.json')">&#11123; stats.json</button>` : ''}
            ${steadyJtlBtn}
            ${rawJtlBtn}
            ${htmlBtn}
            ${logBtn}
            ${s.reportReady ? `<button class="lt-btn lt-btn-ghost" style="font-size:0.75rem;padding:3px 9px" onclick="ltShowEndpoints(${idx})">&#9776; Endpoints</button>` : ''}
    </div>
  </td>
</tr>
<tr id="lt-ep-row-${idx}" style="display:none">
  <td colspan="8" style="padding:0">
    <div id="lt-ep-detail-${idx}" style="padding:8px 12px;background:rgba(0,0,0,0.2)"></div>
  </td>
</tr>`;
        });

        el.innerHTML = `<table class="lt-table">
<thead><tr>
  <th>Scenario</th><th>Samples</th><th>Err%</th><th>Peak TPS</th>
  <th>Avg</th><th>p95</th><th>p99</th><th>Download / Detail</th>
</tr></thead>
<tbody>${rows.join('')}</tbody></table>`;
    }

    window.ltShowEndpoints = function (idx) {
        const row    = document.getElementById('lt-ep-row-' + idx);
        const detail = document.getElementById('lt-ep-detail-' + idx);
        if (!row || !detail) return;
        const visible = row.style.display !== 'none';
        if (visible) { row.style.display = 'none'; return; }
        row.style.display = '';

        const s = allScenarios[idx];
        if (!s || !s.stats) { detail.innerHTML = '<p class="lt-empty">No steady-state endpoint data yet. This scenario currently only has raw artifacts/logs.</p>'; return; }

        const rows = Object.entries(s.stats)
            .sort(([a], [b]) => a === 'Total' ? 1 : b === 'Total' ? -1 : a.localeCompare(b));

        detail.innerHTML = `<table class="lt-table" style="font-size:0.78rem">
<thead><tr><th>Endpoint</th><th>Samples</th><th>Err%</th><th>Avg</th><th>p50</th><th>p95</th><th>p99</th><th>TPS</th></tr></thead>
<tbody>
${rows.map(([label, v]) => `<tr class="${label==='Total'?'lt-row-total':''}">
  <td>${label}</td><td>${(v.sampleCount||0).toLocaleString()}</td>
  <td>${fmtPct(v.errorPct)}</td><td>${fmtMs(v.meanResTime)}</td>
  <td>${fmtMs(v.medianResTime)}</td><td>${fmtMs(v.pct2ResTime)}</td>
  <td>${fmtMs(v.pct3ResTime)}</td><td>${(v.throughput||0).toFixed(2)}</td>
</tr>`).join('')}
</tbody></table>`;
    };

    function ltRenderSelectedScenarioSummary() {
        const el = document.getElementById('lt-jmeter-selected');
        if (!el) return;

        const scenario = selectedScenarioIdx >= 0 ? allScenarios[selectedScenarioIdx] : null;
        if (!scenario) {
            el.style.display = 'none';
            return;
        }

        const total = scenario.stats ? (scenario.stats['Total'] || Object.values(scenario.stats)[0]) : null;
        const mode = scenario.reportReady ? 'steady-state report + chart' : (scenario.rawJtlFile ? 'raw-only chart/log' : 'no scenario data');
        const note = scenario.reportReady
            ? `Viewing ${scenario.name}. Table metrics come from filtered steady-state data.`
            : `Viewing ${scenario.name}. Only raw artifacts are available, so the chart includes warm-up/login traffic and may not match the steady-state table used for completed scenarios.`;

        el.innerHTML = `
<div class="lt-selected-title">Selected scenario: <strong>${scenario.name}</strong></div>
<div class="lt-selected-meta">Mode: ${mode}${total ? ` · Samples: ${(total.sampleCount || 0).toLocaleString()} · Err: ${fmtPct(total.errorPct)} · Avg: ${fmtMs(total.meanResTime)}` : ''}</div>
<div class="lt-help-text">${note}</div>`;
        el.style.display = 'block';

        if (total) {
            const peakTps = Math.max(...Object.values(scenario.stats).map(v => v.throughput || 0));
            setStatCard('st-peak-tps', peakTps.toFixed(1) + ' /s');
            setStatCard('st-avg-resp', fmtMs(total.meanResTime));
            setStatCard('st-error-rate', fmtPct(total.errorPct));
            setStatCard('st-total-req', (total.sampleCount || 0).toLocaleString());
        } else {
            setStatCard('st-peak-tps', 'raw only');
            setStatCard('st-avg-resp', 'raw only');
            setStatCard('st-error-rate', 'raw only');
            setStatCard('st-total-req', 'raw only');
        }
    }

    window.ltOpenScenarioReport = function (scenario) {
        if (!scenario) return;
        const url = `/api/loadtest/jmeter/report/${encodeURIComponent(scenario)}/`;
        window.open(url, '_blank', 'noopener');
    };

    window.ltRunJmeter = async function () {
        // Removed — JMeter must be run from an external machine.
        // See load-tests/jmeter/EXTERNAL_MACHINE_GUIDE.md
        alert('JMeter runner has been removed from the dashboard.\nRun tests from an external machine to avoid overloading the VPS.\nSee: load-tests/jmeter/EXTERNAL_MACHINE_GUIDE.md');
    };

    // ── Graph Upload / Gallery ────────────────────────────────────────────────

    window.ltUploadGraph = async function () {
        const fileInput = document.getElementById('lt-graph-file');
        const labelInput = document.getElementById('lt-graph-label');
        const msgEl = document.getElementById('lt-graph-upload-msg');

        const file = fileInput && fileInput.files[0];
        if (!file) { if (msgEl) { msgEl.style.color = '#fc8181'; msgEl.textContent = 'Please select an image file first.'; } return; }

        const form = new FormData();
        form.append('graph', file);
        if (labelInput && labelInput.value.trim()) form.append('label', labelInput.value.trim());

        if (msgEl) { msgEl.style.color = '#a0aec0'; msgEl.textContent = 'Uploading…'; }

        try {
            const token = ltGetToken();
            const resp = await fetch('/api/loadtest/graphs/upload', {
                method: 'POST',
                headers: token ? { 'Authorization': 'Bearer ' + token } : {},
                body: form
            });
            const body = await resp.json();
            if (!resp.ok) {
                if (msgEl) { msgEl.style.color = '#fc8181'; msgEl.textContent = '✗ ' + (body.error || 'Upload failed'); }
                return;
            }
            if (msgEl) { msgEl.style.color = '#68d391'; msgEl.textContent = '✓ Uploaded: ' + body.filename; }
            if (fileInput) fileInput.value = '';
            if (labelInput) labelInput.value = '';
            await ltLoadGraphs();
        } catch (e) {
            if (msgEl) { msgEl.style.color = '#fc8181'; msgEl.textContent = '✗ ' + e.message; }
        }
    };

    window.ltLoadGraphs = async function () {
        const gallery = document.getElementById('lt-graph-gallery');
        if (!gallery) return;
        try {
            const token = ltGetToken();
            const resp = await fetch('/api/loadtest/graphs', {
                headers: token ? { 'Authorization': 'Bearer ' + token } : {}
            });
            if (!resp.ok) return;
            const { graphs } = await resp.json();
            if (!graphs || graphs.length === 0) {
                gallery.innerHTML = '<p style="color:#718096;font-size:0.82rem">No graphs uploaded yet. Run a JMeter test from an external machine, screenshot the HTML report charts, and upload them here.</p>';
                return;
            }
            gallery.innerHTML = graphs.map(g => {
                const date = new Date(g.mtime).toLocaleDateString();
                const kb = (g.size / 1024).toFixed(1);
                return `<div style="background:#1a2035;border:1px solid #2d3748;border-radius:8px;overflow:hidden">
                    <a href="/api/loadtest/graphs/${encodeURIComponent(g.filename)}" target="_blank" rel="noopener">
                        <img src="/api/loadtest/graphs/${encodeURIComponent(g.filename)}"
                             alt="${ltEscapeHtml(g.filename)}"
                             style="width:100%;display:block;aspect-ratio:16/10;object-fit:cover;cursor:pointer"
                             loading="lazy">
                    </a>
                    <div style="padding:6px 8px;display:flex;justify-content:space-between;align-items:center;gap:4px">
                        <span style="font-size:0.73rem;color:#a0aec0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1"
                              title="${ltEscapeHtml(g.filename)}">${ltEscapeHtml(g.filename.replace(/^\d+-/, ''))}</span>
                        <span style="font-size:0.7rem;color:#718096;white-space:nowrap">${date} · ${kb}KB</span>
                        <button onclick="ltDeleteGraph('${ltEscapeHtml(g.filename)}')"
                                style="background:none;border:none;color:#fc8181;cursor:pointer;font-size:0.85rem;padding:0 4px"
                                title="Delete">✕</button>
                    </div>
                </div>`;
            }).join('');
        } catch (e) {
            if (gallery) gallery.innerHTML = '<p style="color:#fc8181;font-size:0.82rem">Error loading graphs: ' + ltEscapeHtml(e.message) + '</p>';
        }
    };

    window.ltDeleteGraph = async function (filename) {
        if (!confirm('Delete graph: ' + filename + '?')) return;
        try {
            const token = ltGetToken();
            const resp = await fetch('/api/loadtest/graphs/' + encodeURIComponent(filename), {
                method: 'DELETE',
                headers: token ? { 'Authorization': 'Bearer ' + token } : {}
            });
            if (!resp.ok) { const b = await resp.json(); alert('Delete failed: ' + (b.error || resp.status)); return; }
            await ltLoadGraphs();
        } catch (e) {
            alert('Delete error: ' + e.message);
        }
    };

    function ltEscapeHtml(s) {
        return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;');
    }

    function ltStreamJob(jobId, token, logEl, statusEl, onDone) {
        const evtSrc = new EventSource(`/api/loadtest/run/${jobId}/stream?token=${encodeURIComponent(token || '')}`);
        evtSrc.addEventListener('log', e => {
            logEl.textContent += e.data + '\n';
            logEl.scrollTop = logEl.scrollHeight;
        });
        evtSrc.addEventListener('done', async e => {
            evtSrc.close();
            const info = JSON.parse(e.data || '{}');
            if (statusEl) {
                statusEl.textContent = info.code === 0 ? 'Done ✓' : 'Failed ✗';
                statusEl.className   = 'lt-badge ' + (info.code === 0 ? 'badge-ok' : 'badge-danger');
            }
            if (info.code === 0 && onDone) await onDone();
        });
        evtSrc.onerror = () => {
            evtSrc.close();
            if (statusEl) { statusEl.textContent = 'Error'; statusEl.className = 'lt-badge badge-danger'; }
            logEl.textContent += '\n[stream disconnected]\n';
        };
    }

    // ── DS Capacity render ───────────────────────────────────────────────────
    function ltRenderCapacity(data) {
        if (!data || data.error || !data.reports || data.reports.length === 0) {
            const el = document.getElementById('lt-capacity-table');
            if (el) el.innerHTML = '<p class="lt-empty">No DS capacity reports found.</p>';
            return;
        }

        const reports = data.reports.filter(r => r.results || r.summary);
        if (!reports.length) return;

        // Peak DS across all reports
        const peakDS = Math.max(...reports.map(r => {
            const res = r.results || r.summary || {};
            return res.peak_concurrent_ds || res.max_concurrent_ds || res.ds_count || 0;
        }));
        setStatCard('st-peak-ds', peakDS);

        ltDrawCapacityTable(reports);
        ltDrawCapacityChart(reports);
    }

    function ltDrawCapacityChart(reports) {
        const canvas = document.getElementById('lt-capacity-chart');
        if (!canvas) return;
        if (capacityChart) { capacityChart.destroy(); capacityChart = null; }
        if (typeof Chart === 'undefined') {
            canvas.parentElement.innerHTML = '<p class="lt-empty">Chart.js đang bị chặn hoặc chưa tải xong. Bảng DS report vẫn dùng được bên dưới.</p>';
            return;
        }

        const getRes = r => r.results || r.summary || {};

        // Each report is one run; use batches as series if available
        const hasTimeSeries = reports.some(r => r.results && r.results.batches);

        if (hasTimeSeries) {
            const latest = reports.find(r => r.results && r.results.batches) || reports[0];
            const batches = (latest.results && latest.results.batches) || [];
            const labels  = batches.map((b, i) => 'Batch ' + (i + 1));
            const dsData  = batches.map(b => b.concurrent_ds || b.max_concurrent || 0);
            const p50Data = batches.map(b => b.latency_p50 || b.p50_ms || null);
            const p95Data = batches.map(b => b.latency_p95 || b.p95_ms || null);
            const p99Data = batches.map(b => b.latency_p99 || b.p99_ms || null);

            capacityChart = new Chart(canvas.getContext('2d'), {
                type: 'bar',
                data: {
                    labels,
                    datasets: [
                        {
                            type: 'bar', yAxisID: 'y1', label: 'Concurrent DS',
                            data: dsData, backgroundColor: 'rgba(80,200,120,0.6)', borderWidth: 0,
                        },
                        {
                            type: 'line', yAxisID: 'y2', label: 'Latency p50 (ms)',
                            data: p50Data, borderColor: '#7be495', pointRadius: 3, tension: 0.3, fill: false,
                        },
                        {
                            type: 'line', yAxisID: 'y2', label: 'Latency p95 (ms)',
                            data: p95Data, borderColor: '#f0c040', pointRadius: 3, tension: 0.3, fill: false,
                        },
                        {
                            type: 'line', yAxisID: 'y2', label: 'Latency p99 (ms)',
                            data: p99Data, borderColor: '#e05555', pointRadius: 3, tension: 0.3, fill: false,
                        },
                    ]
                },
                options: {
                    responsive: true,
                    interaction: { mode: 'index', intersect: false },
                    plugins: {
                        legend: { position: 'top', labels: { color: '#ccc' } },
                        title: { display: true, text: 'DS Capacity per Batch — Latest Run', color: '#ccc', font: { size: 14 } },
                    },
                    scales: {
                        x:  { ticks: { color: '#999' }, grid: { color: '#2a2a3a' } },
                        y1: { type: 'linear', position: 'left',  title: { display: true, text: 'Concurrent DS',     color: '#aaa' }, ticks: { color: '#aaa' }, grid: { color: '#2a2a3a' }, min: 0 },
                        y2: { type: 'linear', position: 'right', title: { display: true, text: 'Latency (ms)',      color: '#aaa' }, ticks: { color: '#aaa' }, grid: { drawOnChartArea: false }, min: 0 },
                    }
                }
            });
        } else {
            // Fallback: one bar per report
            const labels   = reports.map(r => r.file.replace('ds_capacity_', '').replace('ds-fleet-test-','').replace('.json', ''));
            const getRes   = r => r.results || r.summary || {};
            const dsData   = reports.map(r => getRes(r).peak_concurrent_ds || getRes(r).max_concurrent_ds || 0);
            // hb_success_rate is 0-1, alloc_success_rate is 0-1
            const succData = reports.map(r => {
                const res = getRes(r);
                const pct = res.hb_success_rate || res.alloc_success_rate || res.success_rate_pct;
                return pct != null ? (pct <= 1 ? pct * 100 : pct) : null;
            });

            capacityChart = new Chart(canvas.getContext('2d'), {
                type: 'bar',
                data: {
                    labels,
                    datasets: [
                        {
                            type: 'bar', yAxisID: 'y1', label: 'Max Concurrent DS',
                            data: dsData, backgroundColor: 'rgba(80,200,120,0.6)', borderWidth: 0,
                        },
                        {
                            type: 'line', yAxisID: 'y2', label: 'Success Rate %',
                            data: succData, borderColor: '#f0c040', pointRadius: 4, tension: 0.2, fill: false,
                        },
                    ]
                },
                options: {
                    responsive: true,
                    interaction: { mode: 'index', intersect: false },
                    plugins: {
                        legend: { position: 'top', labels: { color: '#ccc' } },
                        title: { display: true, text: 'DS Capacity Test Runs', color: '#ccc', font: { size: 14 } },
                    },
                    scales: {
                        x:  { ticks: { color: '#999' }, grid: { color: '#2a2a3a' } },
                        y1: { type: 'linear', position: 'left',  title: { display: true, text: 'Max Concurrent DS', color: '#aaa' }, ticks: { color: '#aaa' }, grid: { color: '#2a2a3a' }, min: 0 },
                        y2: { type: 'linear', position: 'right', title: { display: true, text: 'Success Rate %',    color: '#aaa' }, ticks: { color: '#aaa', callback: v => v + '%' }, grid: { drawOnChartArea: false }, min: 0, max: 105 },
                    }
                }
            });
        }
    }

    function ltDrawCapacityTable(reports) {
        const el = document.getElementById('lt-capacity-table');
        if (!el) return;

        const html = `<div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
  <span style="font-size:0.85rem;color:var(--muted)">${reports.length} report(s)</span>
</div>
<table class="lt-table">
<thead><tr>
  <th>Report</th><th>Peak DS</th><th>HB Success</th><th>Total Heartbeats</th><th>HB Errors</th><th>Verdict</th><th>Download</th>
</tr></thead>
<tbody>
${reports.map(r => {
    const s = r.results || r.summary || {};
    const verdict = r.verdict || s.verdict || '–';
    const vclass = verdict.includes('EXCELLENT') || verdict.includes('GOOD') || verdict.includes('✅') ? 'lt-verdict-ok' : 'lt-verdict-warn';
    const succRate = s.hb_success_rate || s.alloc_success_rate || s.success_rate_pct;
    const succPct  = succRate != null ? (succRate <= 1 ? succRate * 100 : succRate) : null;
    return `<tr>
  <td>${r.file.replace('ds_capacity_', '').replace('ds-fleet-test-','').replace('.json', '')}</td>
  <td>${s.peak_concurrent_ds || s.max_concurrent_ds || '–'}</td>
  <td>${fmtPct(succPct)}</td>
  <td>${(s.total_heartbeats || 0).toLocaleString()}</td>
  <td>${s.hb_errors || s.total_errors || 0}</td>
  <td><span class="lt-verdict ${vclass}" title="${verdict}">${verdict.length > 30 ? verdict.substring(0,30)+'…' : verdict}</span></td>
  <td><button class="lt-btn lt-btn-dl" onclick="ltDownload('capacity','${r.file}')">&#11123; JSON</button></td>
</tr>`;
}).join('')}
</tbody></table>`;
        el.innerHTML = html;
    }

    // ── Download helper ──────────────────────────────────────────────────────
    window.ltDownload = function (type, file) {
        const token = ltGetToken();
        let url;
        if (type === 'capacity') url = `/api/loadtest/capacity/download/${encodeURIComponent(file)}`;
        else if (type === 'scenario') url = `/api/loadtest/jmeter/download/scenario/${encodeURIComponent(file)}`;
        else if (type === 'jtl') url = `/api/loadtest/jmeter/download/jtl/${encodeURIComponent(file)}`;
        else if (type === 'result') url = `/api/loadtest/jmeter/download/result/${encodeURIComponent(file)}`;
        else return;

        // Fetch with auth header then trigger download via blob URL
        fetch(url, { headers: token ? { 'Authorization': 'Bearer ' + token } : {} })
            .then(r => {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.blob();
            })
            .then(blob => {
                const a = document.createElement('a');
                a.href = URL.createObjectURL(blob);
                a.download = file;
                document.body.appendChild(a);
                a.click();
                setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
            })
            .catch(e => alert('Download failed: ' + e.message));
    };

    // ── Stat card helper ─────────────────────────────────────────────────────
    function setStatCard(id, val) {
        const el = document.getElementById(id);
        if (el) el.textContent = val;
    }

    // ── Register page ────────────────────────────────────────────────────────
    if (typeof registerPage === 'function') {
        registerPage('loadtest', 'Load Tests', window.renderLoadtest);
    }
}());
