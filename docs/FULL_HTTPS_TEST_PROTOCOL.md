# NightHunt Full HTTPS Test Protocol

Muc tieu cua protocol nay la chay full-scope theo dung transport production:

- HTTP APIs qua `https://.../api`
- Game WebSocket qua `wss://.../api/ws/game?token=...`
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

Runner noi vao:

- `wss://<host>/api/ws/game?token=<accessToken>`

No kiem tra:

- ket noi thanh cong
- nhan su kien `connected`
- gui `{"type":"ping"}` va nhan `{"type":"pong"}`
- dang nhap lai cung user de xac nhan `force_logout` tren ket noi cu

Muc tieu:

- xac nhan protocol production dung `wss`, khong phai SockJS
- xac nhan single-device policy van dung duoi tai that
- xac nhan Redis session + WebSocket handshake khong bi lech logic

### C. Ranked queue HTTPS probes

Runner goi:

- `POST /api/matchmaking/queue`
- `GET /api/matchmaking/queue/status`
- `DELETE /api/matchmaking/queue`

Body mac dinh:

```json
{
  "gameMode": "2v2",
  "platform": "PC"
}
```

Co the bo sung `mapId` neu can khoa kich ban vao 1 map cu the.

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

Scenario chot hien tai:

- `500 VU`
- `1000 VU`
- `2000 VU`

### E. Dedicated Server stress

Van dung 2 harness san co:

- [load-tests/ds-fleet-test/run_capacity_test.py](../load-tests/ds-fleet-test/run_capacity_test.py)
- [load-tests/ds-fleet-test/run_fleet_test.py](../load-tests/ds-fleet-test/run_fleet_test.py)

Hai bai nay do:

- peak DS concurrent
- alloc/register/heartbeat latency
- fleet reclaim behavior
- failure after kill / no-heartbeat

## 2. Lenh chay full suite

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