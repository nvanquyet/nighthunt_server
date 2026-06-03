/**
 * NightHunt Realtime Gateway Load Test - k6/ws
 *
 * Production contract:
 *   1. Each VU logs in or uses its own access token + X-Session-Id.
 *   2. VU calls POST /api/realtime/tickets.
 *   3. VU connects WSS /api/ws/game?ticket=<one-time-ticket>.
 *   4. VU holds the socket for the scenario session duration and sends ping frames.
 *
 * Preferred env for 500+ VU:
 *   HOST=vawnwuyest.me
 *   HTTP_SCHEME=https
 *   USERNAMES_FILE=load-tests/generated/usernames-2000.txt
 *   PASSWORD=StressTest@123
 *
 * Legacy/token env for smoke-only or short tests:
 *   AUTH_TOKENS_FILE=path/to/tokens.txt
 *   SESSION_IDS_FILE=path/to/sessions.txt
 *
 * Optional:
 *   SCENARIO=smoke|ws_500|ws_1000|ws_2000|connection_ramp|ping_storm|soak
 *   SESSION_DURATION_SECONDS=override socket hold time
 *   INSECURE_TLS=true
 *   ALLOW_CREDENTIAL_REUSE=true          smoke-only functional override
 */

import ws from 'k6/ws';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Gauge, Rate, Trend } from 'k6/metrics';

const wsConnectErrors = new Counter('nighthunt_ws_connect_errors');
const wsRuntimeErrors = new Counter('nighthunt_ws_runtime_errors');
const wsEarlyCloses = new Counter('nighthunt_ws_early_closes');
const wsMessagesSent = new Counter('nighthunt_ws_messages_sent');
const wsMessagesReceived = new Counter('nighthunt_ws_messages_received');
const wsSkippedOverlappingPings = new Counter('nighthunt_ws_skipped_overlapping_pings');
const wsPongLatency = new Trend('nighthunt_ws_pong_latency_ms', true);
const wsActiveConns = new Gauge('nighthunt_ws_active_connections');
const wsConnectRate = new Rate('nighthunt_ws_connect_success_rate');
const ticketErrors = new Counter('nighthunt_ws_ticket_errors');
const loginErrors = new Counter('nighthunt_ws_login_errors');

const HOST = __ENV.HOST || 'localhost:8443';
const HTTP_SCHEME = (__ENV.HTTP_SCHEME || 'https').replace(/\/+$/, '');
const WS_SCHEME = HTTP_SCHEME === 'http' ? 'ws' : 'wss';
const BASE_URL = `${HTTP_SCHEME}://${HOST}`;
const USERNAMES = loadList('USERNAMES_FILE', __ENV.USERNAMES || __ENV.USERNAME || '');
const PASSWORDS = loadList('PASSWORDS_FILE', __ENV.PASSWORDS || __ENV.PASSWORD || '');
const TOKENS = loadList('AUTH_TOKENS_FILE', __ENV.AUTH_TOKENS || __ENV.JWT_TOKEN || '');
const SESSION_IDS = loadList('SESSION_IDS_FILE', __ENV.SESSION_IDS || __ENV.SESSION_ID || '');
const SCENARIO = __ENV.SCENARIO || 'connection_ramp';
const ALLOW_CREDENTIAL_REUSE = (__ENV.ALLOW_CREDENTIAL_REUSE || '').toLowerCase() === 'true';
const LOG_WS_ERRORS = (__ENV.LOG_WS_ERRORS || '').toLowerCase() === 'true';
const SESSION_DURATION_SECONDS = positiveInteger(
    __ENV.SESSION_DURATION_SECONDS,
    defaultSessionDuration(SCENARIO)
);

export const options = {
    insecureSkipTLSVerify: (__ENV.INSECURE_TLS || '').toLowerCase() === 'true',
    stages: scenarioStages(SCENARIO),
    thresholds: {
        nighthunt_ws_connect_success_rate: ['rate>0.99'],
        nighthunt_ws_login_errors: ['count<1'],
        nighthunt_ws_ticket_errors: ['count<1'],
        nighthunt_ws_pong_latency_ms: ['p(95)<250', 'p(99)<750'],
        http_req_failed: ['rate<0.001'],
    },
};

export function setup() {
    const requiredCredentials = Math.max(...scenarioStages(SCENARIO).map(stage => stage.target));
    const hasLoginUsers = USERNAMES.length > 0;
    const hasTokenSessions = TOKENS.length > 0 && SESSION_IDS.length > 0;

    if (!hasLoginUsers && !hasTokenSessions) {
        throw new Error(
            'Provide USERNAMES_FILE + PASSWORD for production tests, or AUTH_TOKENS_FILE + SESSION_IDS_FILE for short tests.'
        );
    }
    if (ALLOW_CREDENTIAL_REUSE && SCENARIO !== 'smoke') {
        throw new Error(
            'ALLOW_CREDENTIAL_REUSE=true is smoke-only. ' +
            `${SCENARIO} capacity certification requires one unique identity per VU.`
        );
    }
    if (!ALLOW_CREDENTIAL_REUSE && hasLoginUsers && USERNAMES.length < requiredCredentials) {
        throw new Error(
            `Scenario ${SCENARIO} needs ${requiredCredentials} unique usernames; received ${USERNAMES.length}.`
        );
    }
    if (!ALLOW_CREDENTIAL_REUSE && !hasLoginUsers && hasTokenSessions &&
        (TOKENS.length < requiredCredentials || SESSION_IDS.length < requiredCredentials)) {
        throw new Error(
            `Scenario ${SCENARIO} needs ${requiredCredentials} unique credentials; ` +
            `received ${TOKENS.length} tokens and ${SESSION_IDS.length} session ids.`
        );
    }
    if (hasLoginUsers && PASSWORDS.length === 0) {
        throw new Error('PASSWORD or PASSWORDS_FILE is required when USERNAMES_FILE/USERNAMES is used.');
    }
}

export default function () {
    if (__ITER > 0) {
        sleep(SESSION_DURATION_SECONDS);
        return;
    }

    const credentials = credentialsForVu();
    if (!credentials) {
        loginErrors.add(1);
        sleep(SESSION_DURATION_SECONDS);
        return;
    }

    const ticket = issueTicket(credentials.token, credentials.sessionId);
    if (!ticket) {
        ticketErrors.add(1);
        sleep(SESSION_DURATION_SECONDS);
        return;
    }

    const wsPath = ticket.wsPath || '/api/ws/game';
    const wsUrl = `${WS_SCHEME}://${HOST}${wsPath}?ticket=${encodeURIComponent(ticket.ticket)}`;
    const state = {
        opened: false,
        activeCounted: false,
        failed: false,
        awaitingPong: false,
        pingAt: 0,
        openedAt: 0,
    };

    const response = ws.connect(wsUrl, {
        tags: { name: 'realtime_gateway_ws' },
        headers: { 'User-Agent': 'k6-nighthunt-realtime/2026' },
    }, socket => {
        socket.on('open', () => {
            state.opened = true;
            state.openedAt = Date.now();
            wsConnectRate.add(true);
            if (!state.activeCounted) {
                state.activeCounted = true;
                wsActiveConns.add(1);
            }
            sendPing(socket, state);

            const intervalMs = SCENARIO === 'ping_storm' ? 500 : 10000;
            socket.setInterval(() => sendPing(socket, state), intervalMs);
            socket.setTimeout(() => socket.close(), SESSION_DURATION_SECONDS * 1000);
        });

        socket.on('message', data => handleMessage(data, state));
        socket.on('error', error => {
            if (!state.opened) {
                markConnectFailure(state);
                wsConnectErrors.add(1);
                console.warn(`[WS Connect Error] ${describeError(error)}`);
            } else {
                wsRuntimeErrors.add(1);
                if (LOG_WS_ERRORS) {
                    console.warn(`[WS Runtime Error] ${describeError(error)}`);
                }
            }
        });
        socket.on('close', () => {
            if (state.openedAt > 0 && Date.now() - state.openedAt < (SESSION_DURATION_SECONDS * 1000) - 5000) {
                wsEarlyCloses.add(1);
            }
            if (state.activeCounted) {
                state.activeCounted = false;
                wsActiveConns.add(0);
            }
            if (!state.opened) {
                markConnectFailure(state);
            }
        });
    });

    if (!state.opened && !state.failed) {
        markConnectFailure(state);
        wsConnectErrors.add(1);
        console.warn(`[WS Connect] status=${response ? response.status : 'none'} body=${truncate(response ? response.body : '', 240)}`);
    }
}

function credentialsForVu() {
    if (USERNAMES.length > 0) {
        const index = ALLOW_CREDENTIAL_REUSE ? 0 : (__VU - 1);
        const username = USERNAMES[index % USERNAMES.length];
        const password = PASSWORDS.length === 1 ? PASSWORDS[0] : PASSWORDS[index % PASSWORDS.length];
        return login(username, password);
    }

    if (TOKENS.length === 0 || SESSION_IDS.length === 0) return null;
    const index = ALLOW_CREDENTIAL_REUSE ? 0 : (__VU - 1);
    return {
        token: TOKENS[index % TOKENS.length],
        sessionId: SESSION_IDS[index % SESSION_IDS.length],
    };
}

function login(username, password) {
    const response = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({
        identifier: username,
        password: password,
    }), {
        tags: { name: 'realtime_login' },
        headers: { 'Content-Type': 'application/json' },
    });

    const ok = check(response, {
        'login status 200': r => r.status === 200,
        'login api success': r => {
            try { return r.json('success') === true; } catch (_) { return false; }
        },
    });
    if (!ok) {
        loginErrors.add(1);
        console.warn(`[login] user=${username} status=${response.status} body=${truncate(response.body, 240)}`);
        return null;
    }

    const data = response.json('data');
    if (!data || !data.accessToken || !data.sessionId) {
        loginErrors.add(1);
        console.warn(`[login] user=${username} missing token/session body=${truncate(response.body, 240)}`);
        return null;
    }

    return {
        token: String(data.accessToken),
        sessionId: String(data.sessionId),
    };
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
            if (state.awaitingPong && state.pingAt) {
                wsPongLatency.add(Date.now() - state.pingAt);
                state.awaitingPong = false;
                state.pingAt = 0;
            }
        }
        if (msg.type === 'connected') {
            check(msg, { 'got connected event': value => value.type === 'connected' });
        }
    } catch (_) {
        // Ignore non-JSON frames.
    }
}

function sendPing(socket, state) {
    if (!state.opened) return;
    if (state.awaitingPong) {
        wsSkippedOverlappingPings.add(1);
        return;
    }
    state.awaitingPong = true;
    state.pingAt = Date.now();
    socket.send(JSON.stringify({ type: 'ping' }));
    wsMessagesSent.add(1);
}

function markConnectFailure(state) {
    if (state.opened || state.failed) return;
    state.failed = true;
    wsConnectRate.add(false);
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
    if (name === 'ws_500') {
        return [
            { duration: '1m', target: 100 },
            { duration: '2m', target: 500 },
            { duration: '5m', target: 500 },
            { duration: '1m', target: 0 },
        ];
    }
    if (name === 'ws_1000') {
        return [
            { duration: '1m', target: 100 },
            { duration: '3m', target: 1000 },
            { duration: '5m', target: 1000 },
            { duration: '1m', target: 0 },
        ];
    }
    if (name === 'ws_2000') {
        return [
            { duration: '1m', target: 250 },
            { duration: '4m', target: 2000 },
            { duration: '5m', target: 2000 },
            { duration: '2m', target: 0 },
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

function defaultSessionDuration(name) {
    if (name === 'smoke') return 12;
    if (name === 'ws_500') return 600;
    if (name === 'ws_1000') return 660;
    if (name === 'ws_2000') return 780;
    if (name === 'ping_storm') return 660;
    if (name === 'soak') return 1800;
    return 600;
}

function loadList(fileEnvName, inlineValue) {
    const file = __ENV[fileEnvName];
    if (file && file.trim().length > 0) {
        return splitCsv(open(file.trim()));
    }
    return splitCsv(inlineValue || '');
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
