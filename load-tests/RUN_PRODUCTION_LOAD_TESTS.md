# NightHunt Production Load Test Runner

Chay cac lenh nay tu may generator ben ngoai VPS. Khong chay stress tren VPS dang host backend.

## 1. File can co

Dung folder trong repo:

```text
NightHuntServer/load-tests/
```

Neu muon copy ban dang nam tren VPS:

```bash
scp -r <vps-user>@20.2.235.140:/opt/nighthunt/load-tests ./load-tests-from-vps
```

Thuong khong can copy tu VPS vi CI/CD da lay file tu repo nay de deploy.

## 2. Tool can cai tren may generator

- k6: realtime WebSocket CCU.
- Apache JMeter 5.6.x hoac Docker: REST 500/1000/2000 VU.
- PowerShell 7 khuyen nghi neu chay tren Windows.

May local hien tai dang dung portable tools, khong cai vao o C:

```powershell
$env:PATH = "E:\Env\tools\nighthunt-loadtest\k6-v1.7.1-windows-amd64;" +
            "E:\Env\tools\nighthunt-loadtest\apache-jmeter-5.6.3\bin;" +
            $env:PATH
```

Kiem tra:

```powershell
k6 version
jmeter --version
docker --version
```

## 3. Smoke public truoc khi stress

```powershell
cd NightHuntServer
.\scripts\test-vps-api.ps1
```

Ky vong: `Failed: 0`.

## 4. k6 realtime WebSocket 500/1000/2000

Tao 2000 account rieng. Script cung xuat token/session, nhung capacity test nen dung
`usernames-2000.txt` de moi VU login dung 1 lan, xin realtime ticket va giu WebSocket
den het scenario:

```powershell
cd NightHuntServer
.\load-tests\k6\prepare-realtime-identities.ps1 `
  -ApiBase "https://vawnwuyest.me/api" `
  -Count 2000 `
  -UsernamePrefix "nh_ws_" `
  -Password "StressTest@123" `
  -Parallelism 20
```

Chay 3 scenario:

```powershell
.\load-tests\k6\run-realtime-certification.ps1 `
  -HostName "vawnwuyest.me" `
  -HttpScheme "https" `
  -UsernamesFile ".\load-tests\generated\usernames-2000.txt" `
  -Password "StressTest@123" `
  -Scenarios ws_500,ws_1000,ws_2000
```

Neu vua co login storm hoac server tra `AUTH_011`, dung tiep test tu IP do cho den khi
het thoi gian khoa trong response. Chay tiep truoc khi het khoa se chi tao report sai.

Xem bang ket qua:

```powershell
powershell -ExecutionPolicy Bypass -File .\load-tests\k6\summarize-realtime-results.ps1
```

Metric can lay:

```text
ConnectSuccessRate
PongP95Ms
PongP99Ms
TicketErrors
ConnectErrors
HttpFailedRate
```

Nguong pass hien tai:

```text
ConnectSuccessRate > 0.99
TicketErrors = 0
PongP95Ms < 250
PongP99Ms < 750
HttpFailedRate < 0.001
```

## 5. JMeter REST 500/1000/2000 VU

Linux, WSL, Git Bash, hoac PowerShell co `bash`:

```bash
cd NightHuntServer/load-tests/jmeter
export NH_USERNAME="nh_stress_1"
export NH_PASSWORD="StressTest@123"
export ADMIN_SECRET="<optional-admin-secret>"
bash run-all-scenarios.sh
```

Report HTML:

```text
load-tests/jmeter/reports/scenario-500vu/index.html
load-tests/jmeter/reports/scenario-1000vu/index.html
load-tests/jmeter/reports/scenario-2000vu/index.html
```

Bang summary nhanh:

```powershell
.\load-tests\jmeter\summarize-jmeter-results.ps1
```

Metric can lay:

```text
ThroughputReqSec
AvgMs
P95Ms
P99Ms
ErrorPercent
```

Neu co `ADMIN_SECRET`, them metric server trong:

```text
load-tests/jmeter/results/server-metrics-scenario-500vu.csv
load-tests/jmeter/results/server-metrics-scenario-1000vu.csv
load-tests/jmeter/results/server-metrics-scenario-2000vu.csv
```
