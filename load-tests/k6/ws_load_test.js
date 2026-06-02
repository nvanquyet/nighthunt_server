/**
 * NightHunt Realtime Gateway Load Test - k6/websockets
 *
 * Production contract:
 *   1. Each VU uses its own access token + X-Session-Id.
 *   2. VU calls POST /api/realtime/tickets.
 *   3. VU connects WSS /api/ws/game?ticket=<one-time-ticket>.
 *
 * Required env:
 *   HOST=domain-or-host:port
 *   AUTH_TOKENS=token1,token2,...        or JWT_TOKEN=<single-token smoke only>
 *   SESSION_IDS=session1,session2,...    or SESSION_ID=<single-session smoke only>
 *
 * Optional:
 *   SCENARIO=smoke|connection_ramp|ping_storm|soak
 *   SESSION_DURATION_SECONDS=3600
 *   INSECURE_TLS=true                    for local self-signed HTTPS/WSS only
 *   ALLOW_CREDENTIAL_REUSE=true          explicit functional-only override
 *
 * Examples:
 *   k6 run load-tests/k6/ws_load_test.js -e HOST=vawnwuyest.me -e AUTH_TOKENS=... -e SESSION_IDS=...
 *   k6 run load-tests/k6/ws_load_test.js -e SCENARIO=smoke -e HOST=localhost:8443 -e JWT_TOKEN=... -e SESSION_ID=... -e INSECURE_TLS=true
 */

import { WebSocket } from 'k6/websockets';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Gauge, Rate, Trend } from 'k6/metrics';

const wsConnectErrors = new Counter('nighthunt_ws_connect_errors');
const wsMessagesSent = new Counter('nighthunt_ws_messages_sent');
const wsMessagesReceived = new Counter('nighthunt_ws_messages_received');
const wsPongLatency = new Trend('nighthunt_ws_pong_latency_ms', true);
const wsActiveConns = new Gauge('nighthunt_ws_active_connections');
const wsConnectRate = new Rate('nighthunt_ws_connect_success_rate');
const ticketErrors = new Counter('nighthunt_ws_ticket_errors');

const HOST = __ENV.HOST || 'localhost:8443';
const HTTP_SCHEME = (__ENV.HTTP_SCHEME || 'https').replace(/\/+$/, '');
const WS_SCHEME = HTTP_SCHEME === 'http' ? 'ws' : 'wss';
const BASE_URL = `${HTTP_SCHEME}://${HOST}`;
const TOKENS = splitCsv(__ENV.AUTH_TOKENS || __ENV.JWT_TOKEN || '');
const SESSION_IDS = splitCsv(__ENV.SESSION_IDS || __ENV.SESSION_ID || '');
const SCENARIO = __ENV.SCENARIO || 'connection_ramp';
const ALLOW_CREDENTIAL_REUSE = (__ENV.ALLOW_CREDENTIAL_REUSE || '').toLowerCase() === 'true';
const SESSION_DURATION_SECONDS = positiveInteger(
    __ENV.SESSION_DURATION_SECONDS,
    SCENARIO === 'smoke' ? 12 : 3600
);

export const options = {
    insecureSkipTLSVerify: (__ENV.INSECURE_TLS || '').toLowerCase() === 'true',
    stages: scenarioStages(SCENARIO),
    thresholds: {
        nighthunt_ws_connect_success_rate: ['rate>0.99'],
        nighthunt_ws_ticket_errors: ['count<1'],
        nighthunt_ws_pong_latency_ms: ['p(95)<250', 'p(99)<750'],
        http_req_failed: ['rate<0.001'],
    },
};

export function setup() {
    const requiredCredentials = Math.max(...scenarioStages(SCENARIO).map(stage => stage.target));
    if (TOKENS.length === 0 || SESSION_IDS.length === 0) {
        throw new Error('AUTH_TOKENS/JWT_TOKEN and SESSION_IDS/SESSION_ID are required.');
    }
    if (!ALLOW_CREDENTIAL_REUSE && (TOKENS.length < requiredCredentials || SESSION_IDS.length < requiredCredentials)) {
        throw new Error(
            `Scenario ${SCENARIO} needs ${requiredCredentials} unique credentials; ` +
            `received ${TOKENS.length} tokens and ${SESSION_IDS.length} session ids.`
        );
    }
}

export default function () {
    const credentials = credentialsForVu();
    if (!credentials) {
        ticketErrors.add(1);
        sleep(1);
        return;
    }

    const ticket = issueTicket(credentials.token, credentials.sessionId);
    if (!ticket) {
        ticketErrors.add(1);
        sleep(1);
        return;
    }

    const wsPath = ticket.wsPath || '/api/ws/game';
    const wsUrl = `${WS_SCHEME}://${HOST}${wsPath}?ticket=${encodeURIComponent(ticket.ticket)}`;
    const socket = new WebSocket(wsUrl, [], {
        tags: { name: 'realtime_gateway_ws' },
        headers: { 'User-Agent': 'k6-nighthunt-realtime/2026' },
    });
    const state = {
        opened: false,
        failed: false,
        pingAt: 0,
        intervalId: null,
        timeoutId: null,
    };

    socket.addEventListener('open', () => {
        state.opened = true;
        wsConnectRate.add(true);
        wsActiveConns.add(1);
        sendPing(socket, state);

        const intervalMs = SCENARIO === 'ping_storm' ? 500 : 10000;
        state.intervalId = setInterval(() => sendPing(socket, state), intervalMs);
        state.timeoutId = setTimeout(() => socket.close(), SESSION_DURATION_SECONDS * 1000);
    });

    socket.addEventListener('message', event => handleMessage(event.data, state));
    socket.addEventListener('error', error => {
        markConnectFailure(state);
        wsConnectErrors.add(1);
        console.warn(`[WS Error] ${describeError(error)}`);
    });
    socket.addEventListener('close', () => {
        if (state.intervalId !== null) clearInterval(state.intervalId);
        if (state.timeoutId !== null) clearTimeout(state.timeoutId);
        if (state.opened) wsActiveConns.add(-1);
        else markConnectFailure(state);
    });
}

function issueTicket(token, sessionId) {
    const response = http.post(`${BASE_URL}/api/realtime/tickets`, null, {
        tags: { name: 'realtime_ticket' },
        headers: {
            Authorization: `Bearer ${token}`,
            'X-Session-Id': sessionId,
            'Content-Type': 'application/json',
        },
    });

    const ok = check(response, {
        'ticket status 200': r => r.status === 200,
        'ticket api success': r => {
            try { return r.json('success') === true; } catch (_) { return false; }
        },
    });
    if (!ok) {
        console.warn(`[ticket] status=${response.status} body=${truncate(response.body, 240)}`);
        return null;
    }
    return response.json('data');
}

function handleMessage(data, state) {
    wsMessagesReceived.add(1);
    try {
        const msg = JSON.parse(data);
        if (msg.type === 'pong') {
            wsPongLatency.add(state.pingAt ? Date.now() - state.pingAt : 0);
        }
        if (msg.type === 'connected') {
            check(msg, { 'got connected event': value => value.type === 'connected' });
        }
    } catch (_) {
        // Ignore non-JSON frames.
    }
}

function sendPing(socket, state) {
    if (socket.readyState !== 1) return;
    state.pingAt = Date.now();
    socket.send(JSON.stringify({ type: 'ping' }));
    wsMessagesSent.add(1);
}

function markConnectFailure(state) {
    if (state.opened || state.failed) return;
    state.failed = true;
    wsConnectRate.add(false);
}

function credentialsForVu() {
    if (TOKENS.length === 0 || SESSION_IDS.length === 0) return null;
    const index = (__VU - 1) % TOKENS.length;
    const sessionIndex = (__VU - 1) % SESSION_IDS.length;
    return { token: TOKENS[index], sessionId: SESSION_IDS[sessionIndex] };
}

function scenarioStages(name) {
    if (name === 'smoke') {
        return [
            { duration: '10s', target: 1 },
            { duration: '5s', target: 0 },
        ];
    }
    if (name === 'ping_storm') {
        return [
            { duration: '1m', target: 100 },
            { duration: '3m', target: 1000 },
            { duration: '5m', target: 1000 },
            { duration: '1m', target: 0 },
        ];
    }
    if (name === 'soak') {
        return [
            { duration: '5m', target: 1000 },
            { duration: '30m', target: 1000 },
            { duration: '5m', target: 0 },
        ];
    }
    return [
        { duration: '1m', target: 100 },
        { duration: '3m', target: 1000 },
        { duration: '3m', target: 3000 },
        { duration: '3m', target: 5000 },
        { duration: '5m', target: 10000 },
        { duration: '5m', target: 10000 },
        { duration: '2m', target: 0 },
    ];
}

function positiveInteger(value, fallback) {
    const parsed = Number.parseInt(value || '', 10);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function splitCsv(value) {
    return value.split(',').map(item => item.trim()).filter(item => item.length > 0);
}

function truncate(value, max) {
    if (!value || value.length <= max) return value;
    return value.substring(0, max) + '...';
}

function describeError(error) {
    if (!error) return 'unknown';
    return error.error || error.message || String(error);
}
