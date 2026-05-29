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

const app = express();
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
    const type = req.params.type; // 'jtl' or 'scenario'
    let filePath;
    if (type === 'jtl') {
        filePath = path.join(JMETER_DIR, 'results', safe);
        if (!filePath.startsWith(path.join(JMETER_DIR, 'results'))) return res.status(400).end();
    } else if (type === 'scenario') {
        const scenarioName = path.basename(req.params.file, '.json');
        filePath = path.join(JMETER_DIR, 'reports', scenarioName, 'statistics.json');
        if (!filePath.startsWith(path.join(JMETER_DIR, 'reports'))) return res.status(400).end();
    } else {
        return res.status(400).json({ error: 'Invalid type' });
    }
    if (!fs.existsSync(filePath)) return res.status(404).json({ error: 'Not found' });
    res.download(filePath, safe);
});

// GET /api/loadtest/jmeter — latest JMeter statistics.json summary
app.get('/api/loadtest/jmeter', requireToken, (req, res) => {
    try {
        const reportsDir = path.join(JMETER_DIR, 'reports');
        const resultsDir = path.join(JMETER_DIR, 'results');
        if (!fs.existsSync(reportsDir)) return res.json({ scenarios: [] });

        const scenarios = fs.readdirSync(reportsDir)
            .filter(d => fs.statSync(path.join(reportsDir, d)).isDirectory())
            .sort().reverse()
            .map(dir => {
                const statsPath = path.join(reportsDir, dir, 'statistics.json');
                if (!fs.existsSync(statsPath)) return { name: dir, stats: null };
                try {
                    return { name: dir, stats: JSON.parse(fs.readFileSync(statsPath, 'utf8')) };
                } catch { return { name: dir, stats: null }; }
            });

        // Parse latest .jtl for time-series (aggregate into 30s buckets)
        let timeSeries = null;
        if (fs.existsSync(resultsDir)) {
            const jtls = fs.readdirSync(resultsDir)
                .filter(f => f.endsWith('.jtl'))
                .map(f => ({ f, mtime: fs.statSync(path.join(resultsDir, f)).mtimeMs }))
                .sort((a, b) => b.mtime - a.mtime)
                .map(x => x.f);
            if (jtls.length > 0) {
                try {
                    timeSeries = parseJtlTimeSeries(path.join(resultsDir, jtls[0]), jtls[0]);
                } catch (e) {
                    timeSeries = { error: e.message };
                }
            }
        }
        res.json({ scenarios, timeSeries });
    } catch (e) {
        res.status(500).json({ error: e.message });
    }
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

// ── SPA fallback ──────────────────────────────────────────────────────────────
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

