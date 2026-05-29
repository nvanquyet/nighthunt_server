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

    // ── Main render ──────────────────────────────────────────────────────────
    window.renderLoadtest = async function () {
        const el = document.getElementById('content');
        if (!el) return;

        el.innerHTML = `
<div id="page-loadtest">
<div class="page-header">
  <h1>&#128200; Load Test Reports</h1>
  <p class="page-subtitle">DS Capacity &amp; JMeter stress test results</p>
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

<!-- JMeter charts -->
<div class="lt-section">
  <div class="lt-card-header" style="margin-bottom:8px">
    <h2 class="lt-section-title" style="margin:0">JMeter Stress Test</h2>
    <select id="lt-scenario-sel" class="lt-select" onchange="ltSelectScenario()"></select>
  </div>
  <div class="lt-chart-wrap">
    <canvas id="lt-jmeter-chart" height="100"></canvas>
  </div>
  <div class="lt-section-title" style="margin-top:24px;font-size:14px">Endpoint Summary</div>
  <div id="lt-endpoint-table" class="lt-table-wrap"></div>
</div>

<!-- DS Capacity chart -->
<div class="lt-section">
  <h2 class="lt-section-title">DS Capacity Test</h2>
  <div class="lt-chart-wrap">
    <canvas id="lt-capacity-chart" height="100"></canvas>
  </div>
  <div id="lt-capacity-table" class="lt-table-wrap" style="margin-top:16px"></div>
</div>
</div>`;

        // Load data in parallel
        const [jmeterData, capacityData] = await Promise.all([
            ltFetch('/api/loadtest/jmeter').catch(e => ({ error: e.message })),
            ltFetch('/api/loadtest/capacity').catch(e => ({ error: e.message }))
        ]);

        ltRenderRateLimitStatus();
        ltRenderJmeter(jmeterData);
        ltRenderCapacity(capacityData);
    };

    // ── API fetch helper ─────────────────────────────────────────────────────
    async function ltFetch(url) {
        const token = localStorage.getItem('dashboard_token');
        const res = await fetch(url, { headers: token ? { 'Authorization': 'Bearer ' + token } : {} });
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
            const data = await ltFetch('/api/admin/rate-limit/toggle');
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
    let allScenarios = [];

    function ltRenderJmeter(data) {
        if (!data || data.error) {
            document.getElementById('lt-endpoint-table').innerHTML = '<p class="lt-empty">No JMeter data available.</p>';
            return;
        }

        allScenarios = (data.scenarios || []).filter(s => s.stats);
        const sel = document.getElementById('lt-scenario-sel');

        if (sel) {
            sel.innerHTML = allScenarios.length
                ? allScenarios.map((s, i) => `<option value="${i}">${s.name}</option>`).join('')
                : '<option>No scenarios</option>';
        }

        // Update stat cards with Total from latest scenario
        if (allScenarios.length > 0) {
            const latest = allScenarios[0].stats;
            const total  = latest['Total'] || Object.values(latest)[0];
            if (total) {
                const peakTps   = Math.max(...Object.values(latest).map(v => v.throughput || 0));
                setStatCard('st-peak-tps',  peakTps.toFixed(1) + ' /s');
                setStatCard('st-avg-resp',  fmtMs(total.meanResTime));
                setStatCard('st-error-rate', fmtPct(total.errorPct));
                setStatCard('st-total-req', (total.sampleCount || 0).toLocaleString());
            }
        }

        // Render time-series chart
        ltDrawJmeterChart(data.timeSeries);
        // Render endpoint table for first scenario
        if (allScenarios.length > 0) ltDrawEndpointTable(allScenarios[0].stats);
    }

    window.ltSelectScenario = function () {
        const sel = document.getElementById('lt-scenario-sel');
        const idx = sel ? +sel.value : 0;
        const s   = allScenarios[idx];
        if (s) ltDrawEndpointTable(s.stats);
    };

    function ltDrawJmeterChart(ts) {
        const canvas = document.getElementById('lt-jmeter-chart');
        if (!canvas) return;
        if (jmeterChart) { jmeterChart.destroy(); jmeterChart = null; }

        if (!ts || ts.error || !ts.buckets || ts.buckets.length === 0) {
            canvas.parentElement.innerHTML = '<p class="lt-empty">No JMeter time-series data available.</p>';
            return;
        }

        const labels  = ts.buckets.map(b => b.t + 's');
        const tpsData = ts.buckets.map(b => b.tps);
        const msData  = ts.buckets.map(b => b.avgMs);
        const vuData  = ts.buckets.map(b => b.threads);

        jmeterChart = new Chart(canvas.getContext('2d'), {
            data: {
                labels,
                datasets: [
                    {
                        type: 'line', yAxisID: 'y2', label: 'Avg Response (ms)',
                        data: msData, borderColor: '#4e9af1', backgroundColor: 'rgba(78,154,241,0.12)',
                        tension: 0.3, fill: true, pointRadius: 2, borderWidth: 2,
                    },
                    {
                        type: 'line', yAxisID: 'y1', label: 'Throughput (TPS)',
                        data: tpsData, borderColor: '#f0c040', backgroundColor: 'rgba(240,192,64,0.10)',
                        tension: 0.3, fill: false, pointRadius: 2, borderWidth: 2,
                    },
                    {
                        type: 'bar', yAxisID: 'y1', label: 'Active Users',
                        data: vuData, backgroundColor: 'rgba(150,150,180,0.25)', borderWidth: 0,
                    },
                ]
            },
            options: {
                responsive: true,
                interaction: { mode: 'index', intersect: false },
                plugins: {
                    legend: { position: 'top', labels: { color: '#ccc' } },
                    title:  { display: true, text: 'Throughput & Response Times vs. Time', color: '#ccc', font: { size: 14 } },
                    tooltip: {
                        callbacks: {
                            label: ctx => {
                                const v = ctx.parsed.y;
                                if (ctx.dataset.label.includes('ms')) return ctx.dataset.label + ': ' + fmtMs(v);
                                if (ctx.dataset.label.includes('TPS')) return ctx.dataset.label + ': ' + v + '/s';
                                return ctx.dataset.label + ': ' + v;
                            }
                        }
                    }
                },
                scales: {
                    x:  { ticks: { color: '#999', maxRotation: 0, autoSkip: true, maxTicksLimit: 15 }, grid: { color: '#2a2a3a' } },
                    y1: {
                        type: 'linear', position: 'left',
                        title: { display: true, text: 'TPS / Active Users', color: '#aaa' },
                        ticks: { color: '#aaa' }, grid: { color: '#2a2a3a' },
                        min: 0,
                    },
                    y2: {
                        type: 'linear', position: 'right',
                        title: { display: true, text: 'Response Time (ms)', color: '#aaa' },
                        ticks: { color: '#4e9af1', callback: v => fmtMs(v) },
                        grid: { drawOnChartArea: false },
                        min: 0,
                    },
                }
            }
        });
    }

    function ltDrawEndpointTable(stats) {
        const el = document.getElementById('lt-endpoint-table');
        if (!el) return;
        if (!stats) { el.innerHTML = '<p class="lt-empty">No endpoint data.</p>'; return; }

        const rows = Object.entries(stats)
            .sort(([a], [b]) => (a === 'Total' ? 1 : b === 'Total' ? -1 : a.localeCompare(b)));

        const html = `<table class="lt-table">
<thead><tr>
  <th>Endpoint</th><th>Samples</th><th>Errors</th><th>Err%</th>
  <th>Avg</th><th>p50</th><th>p95</th><th>p99</th><th>TPS</th>
</tr></thead>
<tbody>
${rows.map(([label, s]) => {
    const isTot = label === 'Total';
    return `<tr class="${isTot ? 'lt-row-total' : ''}">
  <td>${label}</td>
  <td>${(s.sampleCount||0).toLocaleString()}</td>
  <td>${(s.errorCount||0).toLocaleString()}</td>
  <td>${fmtPct(s.errorPct)}</td>
  <td>${fmtMs(s.meanResTime)}</td>
  <td>${fmtMs(s.medianResTime)}</td>
  <td>${fmtMs(s.pct2ResTime)}</td>
  <td>${fmtMs(s.pct3ResTime)}</td>
  <td>${(s.throughput||0).toFixed(2)}</td>
</tr>`;
}).join('')}
</tbody></table>`;
        el.innerHTML = html;
    }

    // ── DS Capacity render ───────────────────────────────────────────────────
    function ltRenderCapacity(data) {
        if (!data || data.error || !data.reports || data.reports.length === 0) {
            const el = document.getElementById('lt-capacity-table');
            if (el) el.innerHTML = '<p class="lt-empty">No DS capacity reports found.</p>';
            return;
        }

        const reports = data.reports.filter(r => r.summary);
        if (!reports.length) return;

        // Peak DS across all reports
        const peakDS = Math.max(...reports.map(r => r.summary.max_concurrent_ds || 0));
        setStatCard('st-peak-ds', peakDS);

        ltDrawCapacityChart(reports);
        ltDrawCapacityTable(reports);
    }

    function ltDrawCapacityChart(reports) {
        const canvas = document.getElementById('lt-capacity-chart');
        if (!canvas) return;
        if (capacityChart) { capacityChart.destroy(); capacityChart = null; }

        // Each report is one run; use batches as series if available
        // Try to build time-series from results.batches
        const hasTimeSeries = reports.some(r => r.results && r.results.batches);

        if (hasTimeSeries) {
            // Use the latest report's batch data
            const latest = reports.find(r => r.results && r.results.batches) || reports[0];
            const batches = (latest.results && latest.results.batches) ? latest.results.batches : [];
            const labels  = batches.map((b, i) => 'Batch ' + (i + 1));
            const dsData  = batches.map(b => b.concurrent_ds || b.max_concurrent || 0);
            const p50Data = batches.map(b => b.latency_p50 || b.p50_ms || null);
            const p95Data = batches.map(b => b.latency_p95 || b.p95_ms || null);
            const p99Data = batches.map(b => b.latency_p99 || b.p99_ms || null);

            capacityChart = new Chart(canvas.getContext('2d'), {
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
            // Fallback: one data point per report
            const labels   = reports.map(r => r.file.replace('ds_capacity_', '').replace('.json', ''));
            const dsData   = reports.map(r => (r.summary && r.summary.max_concurrent_ds) || 0);
            const succData = reports.map(r => (r.summary && r.summary.success_rate_pct) || 0);

            capacityChart = new Chart(canvas.getContext('2d'), {
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

        const html = `<table class="lt-table">
<thead><tr>
  <th>Report</th><th>DS Count</th><th>Success Rate</th><th>Total Heartbeats</th><th>Errors</th><th>Verdict</th>
</tr></thead>
<tbody>
${reports.map(r => {
    const s = r.summary || {};
    const vclass = (s.verdict || '').toLowerCase().includes('excellent') ? 'lt-verdict-ok'
                 : (s.verdict || '').toLowerCase().includes('good')      ? 'lt-verdict-ok'
                 : 'lt-verdict-warn';
    return `<tr>
  <td>${r.file.replace('ds_capacity_', '').replace('.json', '')}</td>
  <td>${s.max_concurrent_ds || s.ds_count || '–'}</td>
  <td>${fmtPct(s.success_rate_pct)}</td>
  <td>${(s.total_heartbeats || 0).toLocaleString()}</td>
  <td>${s.total_errors || 0}</td>
  <td><span class="lt-verdict ${vclass}">${s.verdict || '–'}</span></td>
</tr>`;
}).join('')}
</tbody></table>`;
        el.innerHTML = html;
    }

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
