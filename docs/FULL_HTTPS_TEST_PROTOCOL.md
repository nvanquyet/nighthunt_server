# NightHunt Full HTTPS Test Protocol

Muc tieu cua protocol nay la chay full-scope theo dung transport production:

- HTTP APIs qua `https://.../api`
- Game WebSocket qua `wss://.../api/ws/game?ticket=...`
- Dedicated Server lifecycle qua admin/ds APIs va ds callbacks

Khac voi bai JMeter steady-state truoc do, protocol nay ghep du 3 lop:

1. HTTP serving path
2. WebSocket realtime/session path
3. Dedicated Server provisioning path

## 1. Thanh phan test

### A. HTTPS auth + authenticated API probes

Runner dang nhap nhieu user that su qua:

- `POST /api/auth/login`
- `GET /api/auth/check-session`
- `GET /api/profile`
- `GET /api/friends`
- `GET /api/game-modes/available`
- `GET /api/match/history`

Muc tieu:

- xac nhan session/token hop le tren production transport
- lay `errorPct` cho authenticated path
- phan biet loi serving path voi loi login churn

### B. WSS session / realtime probes

Runner xin ticket roi noi vao:

- `POST /api/realtime/tickets` voi `Authorization: Bearer <accessToken>`
- `wss://<host>/api/ws/game?ticket=<oneTimeTicket>`

No kiem tra:

- ket noi thanh cong
- nhan su kien `connected`
- gui `{"type":"ping"}` va nhan `{"type":"pong"}`
- thu dung lai ticket da consume va xac nhan gateway reject
- dang nhap lai cung user de xac nhan `force_logout` tren ket noi cu

Muc tieu:

- xac nhan protocol production dung `wss`, khong phai SockJS
- xac nhan single-device policy van dung duoi tai that
- xac nhan Redis session + one-time ticket handshake khong bi lech logic

### C. Ranked queue HTTPS probes

Runner goi:

- `POST /api/matchmaking/queue`
- `GET /api/matchmaking/queue/status`
- `DELETE /api/matchmaking/queue`

Body mac dinh cua runner load-test:

```json
{
  "gameMode": "2v2",
  "mapId": "map_01",
  "platform": "PC"
}
```

Trong Unity client that:

- Nguoi choi chon `gameMode` va `mapId` tu UI/config server.
- `platform` khong phai lua chon UI; client tu detect `Application.isMobilePlatform ? "MOBILE" : "PC"`.
- Load-test truyen `platform` de gia lap loai client khi chay tu may generator.

Muc tieu:

- verify full auth header + `X-Session-Id`
- verify queue state duoc luu/dong bo dung qua HTTPS
- tach rieng loi queue API voi loi WS event

### D. JMeter HTTPS load

Van dung harness san co tai:

- [load-tests/jmeter/run-all-scenarios.sh](../load-tests/jmeter/run-all-scenarios.sh)
- [load-tests/jmeter/nighthunt-stress-test.jmx](../load-tests/jmeter/nighthunt-stress-test.jmx)

JMX hien da dung:

- `PROTOCOL=https`
- `PORT=443`
- warm-up login mot lan moi VU
- chi tinh steady-state cho 5 authenticated endpoints

Scenario REST/JMeter hien tai:

- `500 VU`
- `1000 VU`
- `2000 VU`

Day la moc HTTP REST stress, khong phai gioi han WS CCU. Realtime CCU phai do bang k6:

- [load-tests/k6/run-realtime-certification.ps1](../load-tests/k6/run-realtime-certification.ps1)
- [load-tests/k6/ws_load_test.js](../load-tests/k6/ws_load_test.js)

Khi tang VU manh, chay JMeter REST song song voi k6 WS de do ca REST request-serving va realtime connection-holding.

Moi k6 VU capacity phai co mot access token va session ID rieng. Khong dung credential reuse de ket luan CCU. Runner `run-realtime-certification.ps1` se fail preflight neu thieu identity:

| Scenario | Unique identities bat buoc | Muc dich |
|---|---:|---|
| `smoke` | 1 | Contract check |
| `ping_storm` | 1000 | Ping RTT stress |
| `soak` | 1000 | Long-held socket stability |
| `connection_ramp` | 10000 | CCU ladder `100 -> 1000 -> 3000 -> 5000 -> 10000` |

### E. Party fill / no-fill acceptance

Client that gui party queue payload:

```json
{
  "gameMode": "4v4",
  "mapId": "map_01",
  "allowFill": true,
  "platform": "PC"
}
```

`platform` do client tu detect. `allowFill` la lua chon cua host khi party dang thieu slot.

Kich ban staging bat buoc:

1. Tao premade party A gom 2 player, chon `4v4`, tat Fill Party.
2. Queue party A va party B tuong thich. Xac nhan moi premade party giu nguyen cung mot team; khong co solo nao duoc chen vao team da lock.
3. Tao premade party C gom 2 player, chon `4v4`, bat Fill Party.
4. Queue them 2 solo hoac party D gom 2 player. Xac nhan matcher ghep du 4 player vao cung team nhung khong them filler vao bang `party_members`.
5. Ket thuc match qua `POST /api/match/end`.
6. Xac nhan party goc van con dung member ban dau, party status tro ve `IDLE`, party mode tro ve `NONE`.
7. Xac nhan filler khong con lien ket party tam sau match.

Database check:

```sql
SELECT user_id, queue_group_id, party_id, party_size, allow_fill
FROM matchmaking_queue
ORDER BY queued_at;

SELECT party_id, user_id
FROM party_members
ORDER BY party_id, join_order;
```

### F. Dedicated Server stress

Van dung 2 harness san co:

- [load-tests/ds-fleet-test/run_capacity_test.py](../load-tests/ds-fleet-test/run_capacity_test.py)
- [load-tests/ds-fleet-test/run_fleet_test.py](../load-tests/ds-fleet-test/run_fleet_test.py)

Hai bai nay do:

- peak DS concurrent
- alloc/register/heartbeat latency
- fleet reclaim behavior
- failure after kill / no-heartbeat

## 2. Lenh chay full suite

Setup Python dependency:

```bash
pip install -r load-tests/requirements.txt
```

Lenh toi thieu:

```bash
python3 load-tests/run_full_https_suite.py \
  --api-base https://vawnwuyest.me/api \
  --username-prefix nh_stress_ \
  --password StressTest@123 \
  --start-user 1 \
  --user-count 20 \
  --ws-users 10
```

Neu mot so stress account dang bi ket party/room, tach rieng range cho queue phase:

```bash
python3 load-tests/run_full_https_suite.py \
  --api-base https://vawnwuyest.me/api \
  --username-prefix nh_stress_ \
  --password StressTest@123 \
  --start-user 4 \
  --user-count 8 \
  --ws-users 4 \
  --queue-start-user 9 \
  --queue-users 2
```

Lenh full gom JMeter + DS:

```bash
ADMIN_SECRET=nh_ds_xxx JMETER_HOME=/opt/apache-jmeter-5.6.3 \
python3 load-tests/run_full_https_suite.py \
  --api-base https://vawnwuyest.me/api \
  --username-prefix nh_stress_ \
  --password StressTest@123 \
  --start-user 1 \
  --user-count 20 \
  --ws-users 10 \
  --queue-users 2 \
  --admin-secret "$ADMIN_SECRET" \
  --run-jmeter \
  --run-capacity \
  --run-fleet
```

Neu dung cert self-signed trong moi truong staging:

```bash
python3 load-tests/run_full_https_suite.py --api-base https://staging.example/api --insecure
```

## 3. Cach tinh error

### HTTP error

HTTP error la request tra ma `>= 400` trong phase HTTPS probes hoac trong JMeter `statistics.json`.

Cong thuc:

`errorPct = failed_requests / total_requests * 100`

### WebSocket error

WS error duoc tinh rieng, khong tron vao HTTP error:

- khong connect duoc `wss`
- khong nhan `connected`
- gui `ping` ma khong nhan `pong`
- login takeover ma khong quan sat duoc `force_logout`

### DS error

DS error duoc tinh rieng theo lifecycle:

- `test-alloc` fail
- `register` fail
- `heartbeat` fail
- fleet reclaim fail
- game-ready / cleanup khong dat expectation

## 4. Watch / download report

Dashboard da ho tro san:

- JSON summary: `GET /api/loadtest/jmeter`
- download JTL / `statistics.json`
- mo HTML report goc
- time-series `TPS / Avg ms / Error % / Active Users`

Code tham chieu:

- [dashboard/server.js](../dashboard/server.js)
- [dashboard/public/js/pages/loadtest.js](../dashboard/public/js/pages/loadtest.js)

Full-suite runner se ghi them 1 JSON report local tai:

- `load-tests/reports/full-suite/full-suite-YYYYmmdd-HHMMSS.json`

File nay la diem tong hop de doi chieu:

- login errors
- HTTP error pct
- WS errors
- queue errors
- exit code cua JMeter / capacity / fleet

## 5. Mapping voi DS

HTTP + WSS khong thay the DS test, vi DS co lifecycle rieng:

1. player queue / room / matchmaking
2. backend allocate DS
3. DS register
4. DS heartbeat
5. backend broadcast `ds_ready`
6. match end / cleanup / reclaim

Vi vay doc report theo 3 lop rieng:

- lop 1: request-serving (`https` API)
- lop 2: realtime delivery (`wss`)
- lop 3: provisioning + reclaim (`DS`)

Neu can muc tieu sat `0%`, phai dong thoi theo doi ca 3 lop nay, khong chi moi JMeter HTTP.
