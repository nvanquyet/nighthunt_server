'use strict';

const express    = require('express');
const axios      = require('axios');
const https      = require('https');
const si         = require('systeminformation');
const cors       = require('cors');
const jwt        = require('jsonwebtoken');
const path       = require('path');
const fs         = require('fs');
const rateLimit  = require('express-rate-limit');
const multer     = require('multer');

// ── Config ────────────────────────────────────────────────────────────────────
const PORT         = process.env.PORT                || 3000;
const BACKEND_URL  = process.env.BACKEND_URL         || 'https://nighthunt-backend:8443';
const ADMIN_SECRET = process.env.ADMIN_SECRET        || 'change-me-in-production';
const ADMIN_USER   = process.env.DASHBOARD_ADMIN_USER || 'admin';
const ADMIN_PASS   = process.env.DASHBOARD_ADMIN_PASS || 'admin123';
// Root admin = highest privilege (sees passwordHash & full user data)
const ROOT_USER    = process.env.DASHBOARD_ROOT_USER  || 'root';
const ROOT_PASS    = process.env.DASHBOARD_ROOT_PASS  || 'root_changeme';
const JWT_SECRET   = process.env.JWT_SECRET          || 'dashboard-jwt-secret-change-me';
const JWT_EXPIRES  = '12h';
// DASHBOARD_RATE_LIMIT_ENABLED — protects dashboard UI itself from IP spam
// Default: true in production. Set to false only during internal testing.
const DASHBOARD_RATE_LIMIT = process.env.DASHBOARD_RATE_LIMIT_ENABLED !== 'false';

const IS_HTTPS_BACKEND = BACKEND_URL.startsWith('https://');
const backendClient = axios.create({
    timeout: 10000,
    httpsAgent: IS_HTTPS_BACKEND
        ? new https.Agent({ rejectUnauthorized: false })
        : undefined
});

async function getBackendRateLimitStatus() {
    const response = await backendClient.get(`${BACKEND_URL}/api/admin/rate-limit/status`, {
        headers: { 'X-Admin-Secret': ADMIN_SECRET }
    });
    return response.data?.data?.enabled ?? response.data?.enabled ?? false;
}

async function setBackendRateLimitEnabled(enabled) {
    const current = await getBackendRateLimitStatus();
    if (current === enabled) return current;
    const response = await backendClient.request({
        method: 'POST',
        url: `${BACKEND_URL}/api/admin/rate-limit/toggle`,
        data: { enabled },
        headers: { 'X-Admin-Secret': ADMIN_SECRET }
    });
    return response.data?.data?.enabled ?? response.data?.enabled ?? current;
}

const app = express();
// Dashboard sits behind nginx, so trust the first proxy hop for client IPs.
app.set('trust proxy', 1);
app.use(cors());
app.use(express.json());

// ── Dashboard rate limiting (IP-based, protects from login brute-force / scraping) ──
if (DASHBOARD_RATE_LIMIT) {
    // Login endpoint: max 20 attempts per 15 min per IP
    const loginLimiter = rateLimit({
        windowMs: 15 * 60 * 1000,
        max: 20,
        message: { error: 'Too many login attempts. Try again in 15 minutes.' },
        standardHeaders: true,
        legacyHeaders: false,
        skipSuccessfulRequests: true,  // only count failed attempts
    });
    // General dashboard API: 300 req/min per IP
    const apiLimiter = rateLimit({
        windowMs: 60 * 1000,
        max: 300,
        message: { error: 'Rate limit exceeded. Slow down.' },
        standardHeaders: true,
        legacyHeaders: false,
    });
    app.use('/auth/login', loginLimiter);
    app.use('/api/', apiLimiter);
    console.log('[NightHunt Dashboard] IP rate limiting ENABLED (login: 20/15min, api: 300/min)');
} else {
    console.log('[NightHunt Dashboard] IP rate limiting DISABLED (DASHBOARD_RATE_LIMIT_ENABLED=false)');
}

app.use(express.static(path.join(__dirname, 'public')));

// ── Auth middleware ───────────────────────────────────────────────────────────
function requireToken(req, res, next) {
    const auth = req.headers['authorization'];
    if (!auth || !auth.startsWith('Bearer ')) {
        return res.status(401).json({ error: 'Missing token' });
    }
    try {
        req.admin = jwt.verify(auth.slice(7), JWT_SECRET);
        next();
    } catch (e) {
        return res.status(401).json({ error: 'Invalid or expired token' });
    }
}

function requireTokenOrQueryToken(req, res, next) {
    const auth = req.headers['authorization'];
    const queryToken = req.query.token;
    const bearer = auth && auth.startsWith('Bearer ') ? auth.slice(7) : null;
    const token = bearer || queryToken;
    if (!token) return res.status(401).send('Missing token');
    try {
        req.admin = jwt.verify(token, JWT_SECRET);
        next();
    } catch {
        return res.status(401).send('Invalid or expired token');
    }
}

// Root admin only (role === 'root')
function requireRoot(req, res, next) {
    requireToken(req, res, () => {
        if (req.admin.role !== 'root') {
            return res.status(403).json({ error: 'Root admin access required' });
        }
        next();
    });
}

// ── POST /auth/login — issue dashboard JWT ────────────────────────────────────
app.post('/auth/login', (req, res) => {
    const { username, password } = req.body || {};
    // Root admin check first
    if (username === ROOT_USER && password === ROOT_PASS) {
        const token = jwt.sign({ username, role: 'root' }, JWT_SECRET, { expiresIn: JWT_EXPIRES });
        return res.json({ token, role: 'root' });
    }
    // Regular admin
    if (username === ADMIN_USER && password === ADMIN_PASS) {
        const token = jwt.sign({ username, role: 'admin' }, JWT_SECRET, { expiresIn: JWT_EXPIRES });
        return res.json({ token, role: 'admin' });
    }
    return res.status(401).json({ error: 'Invalid credentials' });
});

// ── POST /auth/verify — verify dashboard JWT ──────────────────────────────────
app.post('/auth/verify', requireToken, (req, res) => {
    res.json({ valid: true, username: req.admin.username, role: req.admin.role || 'admin' });
});

// ── GET /api/system/stats — host system info ──────────────────────────────────
app.get('/api/system/stats', requireToken, async (req, res) => {
    try {
        const [cpuInfo, mem, processes, load, disk] = await Promise.all([
            si.cpu(),
            si.mem(),
            si.processes(),
            si.currentLoad(),
            si.fsSize()
        ]);
        res.json({
            cpu: {
                manufacturer: cpuInfo.manufacturer,
                brand: cpuInfo.brand,
                cores: cpuInfo.cores,
                physicalCores: cpuInfo.physicalCores,
                speed: cpuInfo.speed,
                usage: load.currentLoad.toFixed(1)
            },
            memory: {
                total: mem.total,
                free: mem.free,
                used: mem.used,
                available: mem.available,
                usagePct: ((mem.used / mem.total) * 100).toFixed(1)
            },
            processes: {
                all: processes.all,
                running: processes.running
            },
            disk: disk.map(d => ({
                fs: d.fs, size: d.size, used: d.used,
                usagePct: d.use ? d.use.toFixed(1) : '0'
            }))
        });
    } catch (e) {
        res.status(500).json({ error: e.message });
    }
});

// ── Admin API proxy ───────────────────────────────────────────────────────────
async function proxyAdmin(req, res) {
    const url = `${BACKEND_URL}/api${req.path.replace(/^\/api/, '')}`;
    try {
        const headers = {
            'Content-Type': 'application/json',
            'X-Admin-Secret': ADMIN_SECRET
        };
        // Forward root privilege so backend /full endpoint is accessible
        if (req.admin && req.admin.role === 'root') {
            headers['X-Root-Admin'] = 'true';
        }
        const response = await backendClient({
            method:  req.method,
            url,
            params:  req.query,
            data:    req.body,
            headers
        });
        res.status(response.status).json(response.data);
    } catch (e) {
        if (e.response) res.status(e.response.status).json(e.response.data);
        else res.status(502).json({ error: 'Backend unreachable: ' + e.message });
    }
}

app.get   ('/api/admin/*', requireToken, proxyAdmin);
app.post  ('/api/admin/*', requireToken, proxyAdmin);
app.put   ('/api/admin/*', requireToken, proxyAdmin);
app.patch ('/api/admin/*', requireToken, proxyAdmin);
app.delete('/api/admin/*', requireToken, proxyAdmin);

// Proxy public game data endpoints (maps, modes) — needed for zone-config reads etc.
app.get('/api/maps/*',  requireToken, proxyAdmin);
app.get('/api/modes/*', requireToken, proxyAdmin);

// ── Relay proxy — backend relay is at /relay/* (no /api prefix) ───────────────
async function proxyRelay(req, res) {
    const url = `${BACKEND_URL}/api${req.path}`;  // context-path=/api, so /relay/health → /api/relay/health
    try {
        const response = await backendClient({
            method:  req.method,
            url,
            params:  req.query,
            data:    req.body,
            headers: { 'Content-Type': 'application/json', 'X-Admin-Secret': ADMIN_SECRET }
        });
        res.status(response.status).json(response.data);
    } catch (e) {
        if (e.response) res.status(e.response.status).json(e.response.data);
        else res.status(502).json({ error: 'Backend unreachable: ' + e.message });
    }
}
app.get('/relay/*', requireToken, proxyRelay);

// ── Legacy backend stats ──────────────────────────────────────────────────────
app.get('/api/backend/stats', requireToken, async (req, res) => {
    try {
        const r = await backendClient.get(`${BACKEND_URL}/api/dashboard/stats`, {
            headers: { 'X-Admin-Secret': ADMIN_SECRET }, timeout: 5000
        });
        res.json(r.data);
    } catch (e) {
        res.status(502).json({ error: String(e.message) });
    }
});

// ── Health ────────────────────────────────────────────────────────────────────
app.get('/health', (req, res) => res.json({ status: 'ok' }));

// ── Load Test Reports API ─────────────────────────────────────────────────────
// In container: /app/load-tests is mounted from ./load-tests (docker-compose volume)
// In dev (no container): falls back to ../load-tests relative to /app
const LOADTEST_BASE = fs.existsSync(path.join(__dirname, 'load-tests'))
    ? path.join(__dirname, 'load-tests')
    : path.join(__dirname, '..', 'load-tests');
const LOADTEST_DIR = path.join(LOADTEST_BASE, 'ds-fleet-test', 'reports');
const JMETER_DIR   = path.join(LOADTEST_BASE, 'jmeter');
const GRAPHS_DIR   = path.join(JMETER_DIR, 'graphs');

// Ensure graphs upload dir exists on startup
if (!fs.existsSync(GRAPHS_DIR)) fs.mkdirSync(GRAPHS_DIR, { recursive: true });

// Multer storage — graphs uploaded via UI
const graphStorage = multer.diskStorage({
    destination: (_req, _file, cb) => cb(null, GRAPHS_DIR),
    filename: (_req, file, cb) => {
        const ts   = Date.now();
        const ext  = path.extname(file.originalname).toLowerCase() || '.png';
        const base = path.basename(file.originalname, ext)
            .replace(/[^a-zA-Z0-9_-]/g, '_')
            .slice(0, 60);
        cb(null, `${ts}-${base}${ext}`);
    }
});
const graphUpload = multer({
    storage: graphStorage,
    limits: { fileSize: 10 * 1024 * 1024 },  // 10 MB
    fileFilter: (_req, file, cb) => {
        if (/^image\/(png|jpeg|webp|gif)$/.test(file.mimetype)) cb(null, true);
        else cb(new Error('Only PNG / JPEG / WEBP / GIF images are accepted'));
    }
});

// GET /api/loadtest/capacity — list + data from DS capacity JSON reports
app.get('/api/loadtest/capacity', requireToken, (req, res) => {
    try {
        if (!fs.existsSync(LOADTEST_DIR)) return res.json({ reports: [] });
        const files = fs.readdirSync(LOADTEST_DIR)
            .filter(f => f.endsWith('.json'))
            .map(f => ({ f, mtime: fs.statSync(path.join(LOADTEST_DIR, f)).mtimeMs }))
            .sort((a, b) => b.mtime - a.mtime)
            .map(x => x.f);
        const reports = files.map(f => {
            try {
                const raw = JSON.parse(fs.readFileSync(path.join(LOADTEST_DIR, f), 'utf8'));
                return { file: f, ...raw };
            } catch { return { file: f, error: 'parse error' }; }
        });
        res.json({ reports });
    } catch (e) {
        res.status(500).json({ error: e.message });
    }
});

// GET /api/loadtest/capacity/download/:file — download raw JSON
app.get('/api/loadtest/capacity/download/:file', requireToken, (req, res) => {
    const safe = path.basename(req.params.file);
    if (!safe.endsWith('.json')) return res.status(400).json({ error: 'Invalid file' });
    const filePath = path.join(LOADTEST_DIR, safe);
    if (!filePath.startsWith(LOADTEST_DIR) || !fs.existsSync(filePath))
        return res.status(404).json({ error: 'Not found' });
    res.download(filePath, safe);
});

// GET /api/loadtest/jmeter/download/:file — download raw JTL or statistics.json
app.get('/api/loadtest/jmeter/download/:type/:file', requireToken, (req, res) => {
    const safe = path.basename(req.params.file);
    const type = req.params.type; // 'jtl', 'scenario', or 'result'
    let filePath;
    if (type === 'jtl') {
        if (!safe.endsWith('.jtl')) return res.status(400).json({ error: 'JTL only' });
        filePath = path.join(JMETER_DIR, 'results', safe);
        if (!filePath.startsWith(path.join(JMETER_DIR, 'results'))) return res.status(400).end();
    } else if (type === 'scenario') {
        const scenarioName = path.basename(req.params.file, '.json');
        filePath = path.join(JMETER_DIR, 'reports', scenarioName, 'statistics.json');
        if (!filePath.startsWith(path.join(JMETER_DIR, 'reports'))) return res.status(400).end();
    } else if (type === 'result') {
        if (!/\.(log|txt|csv)$/i.test(safe)) return res.status(400).json({ error: 'Invalid result file' });
        filePath = path.join(JMETER_DIR, 'results', safe);
        if (!filePath.startsWith(path.join(JMETER_DIR, 'results'))) return res.status(400).end();
    } else {
        return res.status(400).json({ error: 'Invalid type' });
    }
    if (!fs.existsSync(filePath)) return res.status(404).json({ error: 'Not found' });
    res.download(filePath, safe);
});

// GET /api/loadtest/jmeter/report/:scenario/* — serve full HTML report assets
// Public on purpose so generated report CSS/JS/assets can load in a new tab.
app.get('/api/loadtest/jmeter/report/:scenario', (req, res) => {
    return res.redirect(302, `${req.baseUrl || '/api/loadtest/jmeter/report'}/${encodeURIComponent(path.basename(req.params.scenario))}/`);
});

app.get('/api/loadtest/jmeter/report/:scenario/', (req, res) => {
    const scenario = path.basename(req.params.scenario);
    const reportDir = path.join(JMETER_DIR, 'reports', scenario);
    const filePath = path.join(reportDir, 'index.html');
    if (!filePath.startsWith(path.join(JMETER_DIR, 'reports')) || !fs.existsSync(filePath)) {
        return res.status(404).send('Report not found');
    }
    res.sendFile(filePath);
});

app.get('/api/loadtest/jmeter/report/:scenario/*', (req, res) => {
    const scenario = path.basename(req.params.scenario);
    const reportDir = path.join(JMETER_DIR, 'reports', scenario);
    const relPath = req.params[0] || '';
    const filePath = path.join(reportDir, relPath);
    if (!filePath.startsWith(reportDir) || !fs.existsSync(filePath)) {
        return res.status(404).send('Asset not found');
    }
    res.sendFile(filePath);
});

// GET /api/loadtest/jmeter — latest JMeter statistics.json summary
app.get('/api/loadtest/jmeter', requireToken, (req, res) => {
    try {
        const reportsDir = path.join(JMETER_DIR, 'reports');
        const resultsDir = path.join(JMETER_DIR, 'results');

        // Build JTL file list (with mtime) for matching + standalone list
        const jtlMeta = fs.existsSync(resultsDir)
            ? fs.readdirSync(resultsDir)
                .filter(f => f.endsWith('.jtl'))
                .map(f => ({ f, mtime: fs.statSync(path.join(resultsDir, f)).mtimeMs }))
                .sort((a, b) => b.mtime - a.mtime)
            : [];
        const jtlByName = Object.fromEntries(jtlMeta.map(x => [x.f, x]));

        const reportDirs = fs.existsSync(reportsDir)
            ? fs.readdirSync(reportsDir)
                .filter(d => fs.statSync(path.join(reportsDir, d)).isDirectory())
            : [];

        const scenarioNames = new Set(reportDirs);
        jtlMeta.forEach(({ f }) => {
            const match = f.match(/^(scenario-[^.]+?)(?:-raw)?\.jtl$/);
            if (match) scenarioNames.add(match[1]);
        });

        const scenarios = Array.from(scenarioNames)
            .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }))
            .map(name => {
                const statsPath = path.join(reportsDir, name, 'statistics.json');
                let stats = null;
                try {
                    if (fs.existsSync(statsPath)) stats = JSON.parse(fs.readFileSync(statsPath, 'utf8'));
                } catch {}

                const jtlFile = jtlByName[name + '.jtl'] ? name + '.jtl' : null;
                const rawJtlFile = jtlByName[name + '-raw.jtl'] ? name + '-raw.jtl' : null;
                const logFile = fs.existsSync(path.join(resultsDir, name + '-jmeter.log')) ? name + '-jmeter.log' : null;
                const collectorFile = fs.existsSync(path.join(resultsDir, name + '-collector.log')) ? name + '-collector.log' : null;
                const reportReady = Boolean(stats && fs.existsSync(path.join(reportsDir, name, 'index.html')));
                const chartFile = jtlFile || rawJtlFile || null;

                return {
                    name,
                    stats,
                    jtlFile,
                    rawJtlFile,
                    logFile,
                    collectorFile,
                    chartFile,
                    reportReady,
                    status: reportReady ? 'complete' : (rawJtlFile ? 'raw-only' : 'missing')
                };
            });

        const scenarioFiles = new Set();
        scenarios.forEach(s => {
            if (s.jtlFile) scenarioFiles.add(s.jtlFile);
            if (s.rawJtlFile) scenarioFiles.add(s.rawJtlFile);
        });

        // Standalone JTLs (no matching scenario bundle)
        const standaloneJtls = jtlMeta.filter(x => !scenarioFiles.has(x.f)).map(x => x.f);

        // Parse latest JTL for default time-series
        let timeSeries = null;
        if (jtlMeta.length > 0) {
            const preferredScenario = scenarios.find(s => s.chartFile && s.reportReady)
                || scenarios.find(s => s.chartFile)
                || null;
            const preferredFile = preferredScenario?.chartFile;
            const preferred = preferredFile
                ? jtlMeta.find(x => x.f === preferredFile)
                : jtlMeta[0];
            try {
                timeSeries = parseJtlTimeSeries(path.join(resultsDir, preferred.f), preferred.f);
            } catch (e) {
                timeSeries = { error: e.message };
            }
        }

        res.json({ scenarios, standaloneJtls, timeSeries });
    } catch (e) {
        res.status(500).json({ error: e.message });
    }
});

// GET /api/loadtest/jmeter/series/:file — timeSeries for a specific JTL
app.get('/api/loadtest/jmeter/series/:file', requireToken, (req, res) => {
    const safe = path.basename(req.params.file);
    if (!safe.endsWith('.jtl')) return res.status(400).json({ error: 'JTL files only' });
    const resultsDir = path.join(JMETER_DIR, 'results');
    const filePath   = path.join(resultsDir, safe);
    if (!filePath.startsWith(resultsDir) || !fs.existsSync(filePath))
        return res.status(404).json({ error: 'Not found' });
    try { res.json(parseJtlTimeSeries(filePath, safe)); }
    catch (e) { res.status(500).json({ error: e.message }); }
});

function parseJtlTimeSeries(filePath, fileName) {
    const content = fs.readFileSync(filePath, 'utf8');
    const lines   = content.trim().split('\n');
    if (lines.length < 2) return { fileName, buckets: [] };

    const header = lines[0].split(',');
    const tsIdx  = header.indexOf('timeStamp');
    const elIdx  = header.indexOf('elapsed');
    const thIdx  = header.indexOf('allThreads');
    const okIdx  = header.indexOf('success');

    if (tsIdx < 0 || elIdx < 0) return { fileName, buckets: [] };

    // Find min timestamp for relative time
    let minTs = Infinity;
    for (let i = 1; i < lines.length; i++) {
        const p = lines[i].split(',');
        const ts = parseInt(p[tsIdx]);
        if (!isNaN(ts) && ts < minTs) minTs = ts;
    }

    const BUCKET_MS = 15000; // 15-second buckets
    const buckets   = {};
    for (let i = 1; i < lines.length; i++) {
        const p  = lines[i].split(',');
        const ts = parseInt(p[tsIdx]);
        if (isNaN(ts)) continue;
        const elapsed = parseInt(p[elIdx]) || 0;
        const threads = parseInt(p[thIdx]) || 0;
        const ok      = p[okIdx] === 'true';
        const b = Math.floor((ts - minTs) / BUCKET_MS);
        if (!buckets[b]) buckets[b] = { count: 0, errors: 0, elapsedSum: 0, threads: 0 };
        buckets[b].count++;
        if (!ok) buckets[b].errors++;
        buckets[b].elapsedSum += elapsed;
        buckets[b].threads = Math.max(buckets[b].threads, threads);
    }

    const sorted = Object.entries(buckets)
        .sort(([a], [b]) => +a - +b)
        .map(([b, v]) => ({
            t:         +b * BUCKET_MS / 1000,   // seconds from start
            tps:       +(v.count / (BUCKET_MS / 1000)).toFixed(2),
            avgMs:     +(v.elapsedSum / v.count).toFixed(0),
            errors:    v.errors,
            errorPct:  +((v.errors / v.count) * 100).toFixed(1),
            threads:   v.threads,
        }));

    return { fileName, buckets: sorted };
}

// ── Load Test Runner ──────────────────────────────────────────────────────────
const { spawn } = require('child_process');
const jobs = new Map(); // jobId -> { proc, chunks, done, code }

// POST /api/loadtest/run/capacity — launch DS capacity test
app.post('/api/loadtest/run/capacity', requireToken, (req, res) => {
    const testScript = path.join(LOADTEST_BASE, 'ds-fleet-test', 'run_capacity_test.py');
    if (!fs.existsSync(testScript)) {
        return res.status(404).json({ error: 'Test script not found: ' + testScript });
    }

    const jobId = Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
    const env = Object.assign({}, process.env, {
        BACKEND_URL: (process.env.BACKEND_URL || 'http://nighthunt-backend:8080').replace(/^https/, 'http'),
        PYTHONUNBUFFERED: '1'
    });
    const proc = spawn('python3', [testScript], {
        cwd: path.join(LOADTEST_BASE, 'ds-fleet-test'),
        env,
        stdio: ['ignore', 'pipe', 'pipe']
    });

    const job = { proc, chunks: [], done: false, code: null, clients: [] };
    jobs.set(jobId, job);

    const onData = (data) => {
        const line = data.toString();
        job.chunks.push(line);
        job.clients.forEach(client => client.write(`event: log\ndata: ${line.trimEnd()}\n\n`));
    };
    proc.stdout.on('data', onData);
    proc.stderr.on('data', onData);
    proc.on('close', (code) => {
        job.done = true;
        job.code = code;
        const info = JSON.stringify({ code });
        job.clients.forEach(client => {
            client.write(`event: done\ndata: ${info}\n\n`);
            client.end();
        });
        job.clients = [];
        // Clean up after 5 min
        setTimeout(() => jobs.delete(jobId), 5 * 60 * 1000);
    });

    res.json({ jobId });
});

// POST /api/loadtest/graphs/upload — upload a PNG/JPG graph screenshot
app.post('/api/loadtest/graphs/upload', requireToken, (req, res) => {
    graphUpload.single('graph')(req, res, (err) => {
        if (err) return res.status(400).json({ error: err.message });
        if (!req.file) return res.status(400).json({ error: 'No file uploaded' });
        res.json({
            filename: req.file.filename,
            label:    req.body.label || req.file.originalname,
            size:     req.file.size
        });
    });
});

// GET /api/loadtest/graphs — list uploaded graph images
app.get('/api/loadtest/graphs', requireToken, (req, res) => {
    try {
        if (!fs.existsSync(GRAPHS_DIR)) return res.json({ graphs: [] });
        const graphs = fs.readdirSync(GRAPHS_DIR)
            .filter(f => /\.(png|jpe?g|webp|gif)$/i.test(f))
            .map(f => {
                const stat = fs.statSync(path.join(GRAPHS_DIR, f));
                return { filename: f, size: stat.size, mtime: stat.mtimeMs };
            })
            .sort((a, b) => b.mtime - a.mtime);
        res.json({ graphs });
    } catch (e) {
        res.status(500).json({ error: e.message });
    }
});

// GET /api/loadtest/graphs/:filename — serve an uploaded graph image
app.get('/api/loadtest/graphs/:filename', requireToken, (req, res) => {
    const safe = path.basename(req.params.filename);
    const filePath = path.join(GRAPHS_DIR, safe);
    if (!filePath.startsWith(GRAPHS_DIR) || !fs.existsSync(filePath))
        return res.status(404).json({ error: 'Not found' });
    res.sendFile(filePath);
});

// DELETE /api/loadtest/graphs/:filename — delete an uploaded graph
app.delete('/api/loadtest/graphs/:filename', requireToken, (req, res) => {
    const safe = path.basename(req.params.filename);
    const filePath = path.join(GRAPHS_DIR, safe);
    if (!filePath.startsWith(GRAPHS_DIR) || !fs.existsSync(filePath))
        return res.status(404).json({ error: 'Not found' });
    fs.unlinkSync(filePath);
    res.json({ ok: true });
});

// POST /api/loadtest/run/jmeter — REMOVED
// Running JMeter from the same VPS that hosts the backend saturates resources
// and can cause SSH loss. Run JMeter from a separate machine instead.
// See: load-tests/jmeter/EXTERNAL_MACHINE_GUIDE.md
app.post('/api/loadtest/run/jmeter', requireToken, (_req, res) => {
    return res.status(410).json({
        error: 'JMeter runner removed from dashboard.',
        hint:  'Run JMeter from a separate machine to avoid saturating the VPS. See load-tests/jmeter/EXTERNAL_MACHINE_GUIDE.md'
    });
});

// GET /api/loadtest/run/:jobId/stream — SSE log stream (token via query param)
app.get('/api/loadtest/run/:jobId/stream', (req, res) => {
    // Auth via query param (EventSource can't set headers)
    const token = req.query.token;
    try { if (token) jwt.verify(token, JWT_SECRET); else throw new Error('no token'); }
    catch { return res.status(401).json({ error: 'Unauthorized' }); }

    const job = jobs.get(req.params.jobId);
    if (!job) return res.status(404).json({ error: 'Job not found' });

    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection', 'keep-alive');
    res.flushHeaders();

    // Replay buffered output
    job.chunks.forEach(line => res.write(`event: log\ndata: ${line.trimEnd()}\n\n`));

    if (job.done) {
        res.write(`event: done\ndata: ${JSON.stringify({ code: job.code })}\n\n`);
        return res.end();
    }

    job.clients.push(res);
    // Heartbeat every 20s so nginx proxy_read_timeout never fires during quiet periods
    const heartbeat = setInterval(() => {
        if (!job.done) res.write(': heartbeat\n\n');
        else { clearInterval(heartbeat); }
    }, 20000);
    req.on('close', () => {
        clearInterval(heartbeat);
        job.clients = job.clients.filter(c => c !== res);
    });
});

// ── SPA fallback — must be LAST so all API routes register first ──────────────
app.get('*', (req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

// ── Start ─────────────────────────────────────────────────────────────────────
app.listen(PORT, () => {
    console.log(`[NightHunt Dashboard] Running on port ${PORT}`);
    console.log(`[NightHunt Dashboard] Backend -> ${BACKEND_URL}`);
    if (IS_HTTPS_BACKEND) {
        console.log('[NightHunt Dashboard] TLS verification disabled for internal localhost dashboard access');
    }
    console.log(`[NightHunt Dashboard] Admin user: ${ADMIN_USER}`);
    console.log(`[NightHunt Dashboard] Root  user: ${ROOT_USER}`);
});

