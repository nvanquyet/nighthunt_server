/* ═══════════════════════════════════════════════════════════════════════
   NightHunt Admin Dashboard — Core App Module
   Handles: state, auth, API, formatters, nav, modal, alerts
   ═══════════════════════════════════════════════════════════════════════ */

'use strict';

// ── DOM shorthand ────────────────────────────────────────────────────────
const $ = id => document.getElementById(id);

// ── State ────────────────────────────────────────────────────────────────
let TOKEN        = null;
let adminRole    = 'admin';     // 'admin' | 'root'
let refreshTimer = null;

// ── JWT Helpers ──────────────────────────────────────────────────────────
function parseJwtPayload(t) {
  try {
    return JSON.parse(atob(t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
  } catch { return {}; }
}

function updateRoleBadge() {
  const badge = document.getElementById('role-badge');
  const user  = document.getElementById('sidebar-username');
  if (!badge || !TOKEN) return;
  const p = parseJwtPayload(TOKEN);
  adminRole = p.role || 'admin';
  badge.textContent = adminRole.toUpperCase();
  badge.className   = adminRole === 'root' ? 'root' : 'admin';
  if (user) user.textContent = p.sub || 'admin';
  document.getElementById('sidebar-avatar').textContent =
    (p.sub || 'A').charAt(0).toUpperCase();
}

// ── API Wrapper ──────────────────────────────────────────────────────────
async function api(method, path, data, params) {
  const url = new URL(path, window.location.origin);
  if (params) Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, v);
  });
  const opts = {
    method,
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${TOKEN}` }
  };
  if (data && method !== 'GET') opts.body = JSON.stringify(data);
  const res = await fetch(url, opts);
  if (!res.ok) {
    const err = await res.text().catch(() => res.statusText);
    throw new Error(`HTTP ${res.status}: ${err}`);
  }
  const ct = res.headers.get('content-type') || '';
  return ct.includes('json') ? res.json() : res.text();
}

// ── Formatters ───────────────────────────────────────────────────────────
const fmt = n => n == null ? '—' : Number(n).toLocaleString();

function fmtDate(d) {
  if (!d) return '—';
  return new Date(d).toLocaleString('vi-VN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  });
}

function fmtBytes(b) {
  if (b == null) return '—';
  if (b < 1024) return b + ' B';
  if (b < 1048576) return (b / 1024).toFixed(1) + ' KB';
  if (b < 1073741824) return (b / 1048576).toFixed(1) + ' MB';
  return (b / 1073741824).toFixed(2) + ' GB';
}

function fmtAge(ms) {
  if (!ms) return '—';
  const s = Math.floor(ms / 1000), m = Math.floor(s / 60), h = Math.floor(m / 60);
  if (h > 0) return `${h}h ${m % 60}m`;
  if (m > 0) return `${m}m ${s % 60}s`;
  return `${s}s`;
}

function elo2color(e) {
  if (!e) return 'var(--muted)';
  if (e < 1000) return 'var(--muted)';
  if (e < 1200) return 'var(--green)';
  if (e < 1500) return 'var(--cyan)';
  if (e < 1800) return 'var(--yellow)';
  return 'var(--red)';
}

function tierBadge(tier) {
  const MAP = {
    BRONZE: 'badge-muted', SILVER: 'badge-muted',
    GOLD: 'badge-yellow',  PLATINUM: 'badge-cyan',
    DIAMOND: 'badge-purple', MASTER: 'badge-red',
    GRANDMASTER: 'badge-red', CHALLENGER: 'badge-orange'
  };
  const cls = MAP[tier] || 'badge-muted';
  return `<span class="badge ${cls}">${tier || '—'}</span>`;
}

function statusBadge(s) {
  if (!s) return '<span class="badge badge-muted">—</span>';
  const up = s.toUpperCase();
  const m = {
    WAITING: 'badge-green', IN_GAME: 'badge-cyan', CLOSED: 'badge-muted',
    FINISHED: 'badge-muted', LOBBY: 'badge-yellow', BANNED: 'badge-red',
    ACTIVE: 'badge-green', INACTIVE: 'badge-muted',
    ONLINE: 'badge-green', OFFLINE: 'badge-muted'
  };
  return `<span class="badge ${m[up] || 'badge-muted'}">${s}</span>`;
}

function eventBadge(t) {
  const m = {
    LOGIN: 'badge-green', LOGOUT: 'badge-muted', REGISTER: 'badge-cyan',
    BAN: 'badge-red', UNBAN: 'badge-yellow', UPDATE: 'badge-purple',
    MATCH_START: 'badge-cyan', MATCH_END: 'badge-muted',
    CHANGE_PASSWORD: 'badge-yellow'
  };
  return `<span class="badge ${m[t] || 'badge-muted'}">${t || '—'}</span>`;
}

// ── Toast Alerts ─────────────────────────────────────────────────────────
function showAlert(msg, type = 'info') {
  const box = document.getElementById('alert-box');
  if (!box) return;
  const el = document.createElement('div');
  el.className = `alert-toast ${type}`;
  el.textContent = msg;
  box.appendChild(el);
  setTimeout(() => el.remove(), 4000);
}

// ── Modal ────────────────────────────────────────────────────────────────
/**
 * showModal(html?) — if html is provided, replaces modal-container content.
 * Can also be called with no args to just show (when pages set container themselves).
 */
function showModal(html) {
  const bg = $('modal-bg');
  const container = $('modal-container');
  if (html !== undefined && container) container.innerHTML = html;
  bg.classList.add('open');
}

function closeModal(e) {
  if (!e || e.target === $('modal-bg') || e.currentTarget?.classList?.contains('modal-close')) {
    $('modal-bg').classList.remove('open');
  }
}

// ── Pagination helper ─────────────────────────────────────────────────────
/**
 * renderPager(cid, springPage, onPageFn)
 * springPage = { page, totalPages, totalElements }
 * onPageFn   = function(newPage) { ... }
 */
function renderPager(cid, springPage, onPageFn) {
  const el = $(cid);
  if (!el) return;
  const p   = springPage.page || 0;
  const tot = springPage.totalPages || 1;
  const total = springPage.totalElements || 0;
  if (tot <= 1 && total === 0) { el.innerHTML = ''; return; }
  const fnStr = onPageFn.toString();
  let h = `<div class="pager">`;
  h += `<span class="pager-info">${fmt(total)} total &bull; page ${p + 1} / ${tot}</span>`;
  h += `<div class="pager-btns">`;
  h += `<button class="btn btn-ghost btn-sm" ${p === 0 ? 'disabled' : ''} onclick="(${fnStr})(0)">&laquo;</button>`;
  h += `<button class="btn btn-ghost btn-sm" ${p === 0 ? 'disabled' : ''} onclick="(${fnStr})(${p - 1})">&#8249;</button>`;
  for (let i = Math.max(0, p - 2); i <= Math.min(tot - 1, p + 2); i++) {
    h += `<button class="btn btn-ghost btn-sm ${i === p ? 'btn-primary' : ''}" onclick="(${fnStr})(${i})">${i + 1}</button>`;
  }
  h += `<button class="btn btn-ghost btn-sm" ${p >= tot - 1 ? 'disabled' : ''} onclick="(${fnStr})(${p + 1})">&#8250;</button>`;
  h += `<button class="btn btn-ghost btn-sm" ${p >= tot - 1 ? 'disabled' : ''} onclick="(${fnStr})(${tot - 1})">&raquo;</button>`;
  h += `</div></div>`;
  el.innerHTML = h;
}

// ── Auth ────────────────────────────────────────────────────────────────
async function doLogin() {
  const u = document.getElementById('loginUser').value.trim();
  const p = document.getElementById('loginPass').value;
  const errEl = document.getElementById('login-error');
  if (!u || !p) { errEl.textContent = 'Enter username and password'; return; }
  try {
    const btn = document.querySelector('#login-overlay .btn-primary');
    btn.disabled = true;
    const res = await fetch('/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: u, password: p })
    });
    if (!res.ok) { errEl.textContent = 'Invalid credentials'; btn.disabled = false; return; }
    const d = await res.json();
    TOKEN = d.token;
    sessionStorage.setItem('nh_token', TOKEN);
    document.getElementById('login-overlay').style.display = 'none';
    updateRoleBadge();
    nav('overview');
    startAutoRefresh();
  } catch {
    errEl.textContent = 'Connection error';
    document.querySelector('#login-overlay .btn-primary').disabled = false;
  }
}

function doLogout() {
  TOKEN = null;
  clearInterval(refreshTimer);
  document.getElementById('login-overlay').style.display = 'flex';
  document.getElementById('loginPass').value = '';
  document.getElementById('login-error').textContent = '';
  document.getElementById('content').innerHTML = '';
}

async function verifyToken() {
  if (!TOKEN) return false;
  try {
    const r = await fetch('/auth/verify', { method: 'POST', headers: { 'Authorization': `Bearer ${TOKEN}` } });
    return r.ok;
  } catch { return false; }
}

function startAutoRefresh() {
  clearInterval(refreshTimer);
  refreshTimer = setInterval(() => {
    verifyToken().then(ok => { if (!ok) doLogout(); });
  }, 5 * 60 * 1000);
}

// ── Navigation ────────────────────────────────────────────────────────────
const PAGE_MAP = {
  overview:    () => typeof renderOverview   === 'function' && renderOverview(),
  users:       () => typeof renderUsers      === 'function' && renderUsers(),
  activity:    () => typeof renderActivity   === 'function' && renderActivity(),
  rooms:       () => typeof renderRooms      === 'function' && renderRooms(),
  matches:     () => typeof renderMatches    === 'function' && renderMatches(),
  bans:        () => typeof renderBans       === 'function' && renderBans(),
  system:      () => typeof renderSystem     === 'function' && renderSystem(),
  online:      () => typeof renderOnlineUsers=== 'function' && renderOnlineUsers(),
  dssessions:  () => typeof renderDsSessions === 'function' && renderDsSessions(),
  config:      () => typeof renderConfig     === 'function' && renderConfig(),
  dbclean:     () => typeof renderDbCleanup  === 'function' && renderDbCleanup(),
  loadtest:    () => typeof renderLoadtest   === 'function' && renderLoadtest(),
};

function nav(page) {
  document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));
  const activeEl = document.getElementById(`nav-${page}`);
  if (activeEl) activeEl.classList.add('active');
  const titles = {
    overview: '📊 Overview', users: '👤 Users', activity: '📋 Activity',
    rooms: '🚪 Rooms', matches: '⚔️ Matches', bans: '🔨 Bans',
    system: '🖥️ System Health', online: '🟢 Online Users',
    dssessions: '🎮 DS Sessions', config: '⚙️ Config',
    dbclean: '🗄️ Database Monitor', loadtest: '📊 Load Tests'
  };
  const titleEl = document.getElementById('page-title');
  if (titleEl) titleEl.textContent = titles[page] || page;

  if (PAGE_MAP[page]) {
    PAGE_MAP[page]();
  } else {
    document.getElementById('content').innerHTML =
      `<div class="empty-state"><div class="empty-icon">🔧</div><p>Page "${page}" not found.</p></div>`;
  }

  // Auto-close sidebar on mobile
  if (window.innerWidth <= 768) closeSidebar();
}

// ── Sidebar toggle (mobile) ──────────────────────────────────────────────
function toggleSidebar() {
  document.getElementById('sidebar').classList.toggle('open');
  document.getElementById('sidebar-overlay').classList.toggle('open');
}

function closeSidebar() {
  document.getElementById('sidebar').classList.remove('open');
  document.getElementById('sidebar-overlay').classList.remove('open');
}

// ── Boot ─────────────────────────────────────────────────────────────────
window.addEventListener('DOMContentLoaded', async () => {
  // Keyboard: Enter on login fields
  ['loginUser', 'loginPass'].forEach(id => {
    document.getElementById(id)?.addEventListener('keydown', e => {
      if (e.key === 'Enter') doLogin();
    });
  });

  // Modal close on background click
  document.getElementById('modal-bg')?.addEventListener('click', closeModal);

  // Sidebar overlay click
  document.getElementById('sidebar-overlay')?.addEventListener('click', closeSidebar);

  // Try auto-login from session
  const saved = sessionStorage.getItem('nh_token');
  if (saved) {
    TOKEN = saved;
    const ok = await verifyToken();
    if (ok) {
      document.getElementById('login-overlay').style.display = 'none';
      updateRoleBadge();
      nav('overview');
      startAutoRefresh();
      return;
    }
    TOKEN = null;
  }
});

// Escape key closes modal
document.addEventListener('keydown', e => { if (e.key === 'Escape') $('modal-bg')?.classList.remove('open'); });
