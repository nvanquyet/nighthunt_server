'use strict';

const express = require('express');
const axios   = require('axios');
const https   = require('https');
const si      = require('systeminformation');
const cors    = require('cors');
const jwt     = require('jsonwebtoken');
const path    = require('path');

// ── Config ────────────────────────────────────────────────────────────────────
const PORT         = process.env.PORT                || 3000;
const BACKEND_URL  = process.env.BACKEND_URL         || 'https://nighthunt-backend:8443';
const ADMIN_SECRET = process.env.DS_ADMIN_SECRET     || 'change-me-in-production';
const ADMIN_USER   = process.env.DASHBOARD_ADMIN_USER || 'admin';
const ADMIN_PASS   = process.env.DASHBOARD_ADMIN_PASS || 'admin123';
// Root admin = highest privilege (sees passwordHash & full user data)
const ROOT_USER    = process.env.DASHBOARD_ROOT_USER  || 'root';
const ROOT_PASS    = process.env.DASHBOARD_ROOT_PASS  || 'root_changeme';
const JWT_SECRET   = process.env.JWT_SECRET          || 'dashboard-jwt-secret-change-me';
const JWT_EXPIRES  = '12h';
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
    const url = `${BACKEND_URL}${req.path}`;
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
app.delete('/api/admin/*', requireToken, proxyAdmin);

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

