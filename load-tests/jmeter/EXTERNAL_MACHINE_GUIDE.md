# NightHunt – JMeter Load Testing (External Machine)

> **Rule:** Never run load tests on the same VPS that hosts the backend.  
> Always run from a separate laptop/PC targeting `vawnwuyest.me`.

---

## 1. Install JMeter on Your Machine

### Windows

1. Download **JMeter 5.6.3** from: https://jmeter.apache.org/download_jmeter.cgi  
   → choose `apache-jmeter-5.6.3.zip`
2. Extract to `C:\jmeter\`
3. Double-click `C:\jmeter\bin\jmeter.bat` to open the GUI  
   **OR** run CLI from PowerShell:
   ```powershell
   $env:JMETER_HOME = "C:\jmeter"
   $env:PATH += ";$env:JMETER_HOME\bin"
   jmeter --version
   ```
4. Java 11+ required. Install from https://adoptium.net if missing.

### Linux / macOS

```bash
wget https://downloads.apache.org/jmeter/binaries/apache-jmeter-5.6.3.tgz
tar -xzf apache-jmeter-5.6.3.tgz
export JMETER_HOME=$PWD/apache-jmeter-5.6.3
export PATH=$JMETER_HOME/bin:$PATH
jmeter --version
```

### Docker (any OS — no Java required)

```bash
# Windows PowerShell
docker pull justb4/jmeter:latest

# Linux / macOS
docker pull justb4/jmeter:latest
```

---

## 2. Get the Test File

Copy **one file** from the repo to your machine:

```
load-tests/jmeter/nighthunt-stress-test.jmx
```

That's it. No other files needed to run. Create output folders next to it:

```bash
# Linux / macOS
mkdir -p results reports

# Windows PowerShell
New-Item -ItemType Directory -Force -Path results, reports
```

---

## 3. Pre-Test Checklist (do this BEFORE running)

### Step 1 — Disable backend rate limiting (from Dashboard)

The backend has IP rate limiting enabled by default. Under load test conditions
with hundreds of VUs all hitting the same login endpoint, this would trigger
auto-bans and corrupt your results.

**How to disable** (no SSH needed — done from dashboard UI):

1. Open dashboard → **Load Test Reports** page
2. Find the **Backend Rate Limiting** card at the top
3. Click **🔴 Disable Rate Limit**
4. Confirm the status flips to "Disabled"

> ⚠ **Remember to re-enable after the test** (same button becomes 🟢 Enable Rate Limit).

### Step 2 — Verify the backend is healthy

```bash
# Quick health check from your machine
curl -k https://vawnwuyest.me/api/actuator/health
# Expected: {"status":"UP"}
```

### Step 3 — Ensure stress-test users exist

The JMX file has a **[0] SETUP** phase that auto-registers `nh_stress_1` …
`nh_stress_N` before the main load starts. **No manual user creation needed.**

The SETUP phase runs first, ramps up slowly (`setupRampup` seconds), then
the main load groups fire. All of this is automatic.

---

## 4. Running a Test

### Option A — JMeter GUI (Windows step-by-step)

> **Khi nào dùng GUI?** Lần đầu chạy, muốn xem cây test plan, hoặc muốn
> debug từng request. Cho production load test nên dùng CLI (Option B) vì
> GUI tiêu tốn thêm RAM.

---

#### A1 — Mở JMeter GUI

**Windows:**
```
C:\jmeter\bin\jmeter.bat
```
Double-click file trên trong Explorer, hoặc chạy trong PowerShell.  
Cửa sổ GUI sẽ mở — có thanh menu trên cùng và cột cây bên trái.

**Linux / macOS:**
```bash
jmeter
```

> Nếu cửa sổ không xuất hiện, kiểm tra Java: `java -version` phải là 11+.

---

#### A2 — Mở file test plan

1. Trên menu bar: **File → Open**
2. Điều hướng đến thư mục `load-tests/jmeter/`
3. Chọn `nighthunt-stress-test.jmx` → **Open**

Cột cây bên trái sẽ hiện ra cấu trúc như sau:
```
📁 Test Plan
  📁 [0] SETUP – Register & Login Users
      HTTP Request – /api/auth/register
      HTTP Request – /api/auth/login
  📁 [1] Authenticate (Main Load)
      HTTP Request – /api/auth/login
  📁 [2] Game Actions (Main Load)
      HTTP Request – /api/room/...
      HTTP Request – /api/game/...
  📁 Summary Report          ← listener xem kết quả
  📁 View Results Tree       ← listener xem từng request
```

---

#### A3 — Đặt thông số trước khi chạy

1. Click vào node **Test Plan** (dòng đầu tiên trong cây)
2. Ở panel bên phải, tìm phần **User Defined Variables** (bảng có cột Name / Value)
3. Double-click vào ô **Value** của từng biến để sửa:

   | Biến          | Mặc định          | Sửa thành                          |
   |---------------|-------------------|------------------------------------|
   | `BASE_URL`    | `vawnwuyest.me`   | Giữ nguyên (hoặc đổi nếu test server khác) |
   | `PORT`        | `443`             | Giữ nguyên                         |
   | `PROTOCOL`    | `https`           | Giữ nguyên                         |
   | `vusers`      | `500`             | `500` / `1000` / `2000`            |
   | `rampup`      | `30`              | Xem bảng presets bên dưới          |
   | `duration`    | `60`              | Xem bảng presets bên dưới          |
   | `setupRampup` | `120`             | Xem bảng presets bên dưới          |
   | `password`    | `StressTest@123`  | Không đổi                          |

4. Nhấn **Ctrl+S** để lưu lại thay đổi vào file JMX.

---

#### A4 — Thêm listener để xem kết quả (nếu chưa có)

Nếu cây chưa có **Summary Report** hoặc **View Results Tree**:

1. Right-click vào **Test Plan** trong cây → **Add → Listener → Summary Report**
2. Right-click vào **Test Plan** → **Add → Listener → View Results Tree**

> **Summary Report** — hiện số liệu tổng hợp: throughput, error %, avg response time.  
> **View Results Tree** — hiện từng request (xanh = pass, đỏ = fail). Chỉ dùng khi debug vì nặng bộ nhớ.

---

#### A5 — Cấu hình ghi kết quả ra file (khuyến nghị)

1. Click vào node **Summary Report** trong cây
2. Ở phần **Filename**, nhập:
   ```
   results/1000vu-raw.jtl
   ```
   (Tạo thư mục `results/` trong cùng thư mục với file JMX trước nếu chưa có)
3. Tích ✅ **Configure** → đảm bảo định dạng là **CSV**

File `.jtl` này sẽ được dùng để gen HTML report ở bước 6.

---

#### A6 — Chạy test

| Hành động | Cách thực hiện                              |
|-----------|----------------------------------------------|
| **Bắt đầu** | Menu **Run → Start** hoặc `Ctrl+R` hoặc nút **▶ (xanh lá)** trên toolbar |
| **Dừng nhẹ** | Menu **Run → Stop** hoặc `Ctrl+.` — chờ thread hoàn thành |
| **Dừng ngay** | Menu **Run → Shutdown** hoặc `Ctrl+,` — cắt ngay lập tức |

**Theo dõi test đang chạy:**
- Góc trên bên phải cửa sổ hiện **số thread đang active** (ví dụ `1000/1000`)
- Tab **Summary Report** hiện live: Samples, Average, Error%, Throughput
- Thanh progress màu xanh lá = đang chạy; khi hết màu = test đã xong

---

#### A7 — Đọc kết quả trong Summary Report

Sau khi test kết thúc, click vào **Summary Report** trong cây:

| Cột             | Ý nghĩa                                         | Mục tiêu 1000 VU   |
|-----------------|-------------------------------------------------|-------------------|
| **# Samples**   | Tổng số request đã gửi                          | —                 |
| **Average**     | Thời gian phản hồi trung bình (ms)             | < 3000 ms         |
| **90% Line**    | 90% request phản hồi trong X ms                | < 5000 ms         |
| **Error %**     | Tỉ lệ lỗi                                       | < 2%              |
| **Throughput**  | Requests/giây                                   | > 50 req/s        |

---

#### A8 — Xóa dữ liệu cũ trước khi chạy lại

Trước mỗi lần chạy mới, xóa kết quả cũ để tránh nhầm lẫn:
1. Menu **Run → Clear All** hoặc `Ctrl+E`
2. Hoặc click vào từng listener → nút **Clear** (biểu tượng thùng rác)

> File `.jtl` đã ghi ra disk không bị xóa bởi Clear — chỉ xóa data trên màn hình.

### Option B — CLI (recommended for clean results + HTML report)

#### Linux / macOS

```bash
# 500 VU — smoke test
jmeter -n \
  -t nighthunt-stress-test.jmx \
  -Jvusers=500 -Jrampup=30 -Jduration=60 -JsetupRampup=60 \
  -Jpassword=StressTest@123 \
  -JBASE_URL=vawnwuyest.me -JPORT=443 -JPROTOCOL=https \
  -l results/500vu-raw.jtl -j results/500vu.log

# 1000 VU — standard load
jmeter -n \
  -t nighthunt-stress-test.jmx \
  -Jvusers=1000 -Jrampup=60 -Jduration=120 -JsetupRampup=180 \
  -Jpassword=StressTest@123 \
  -JBASE_URL=vawnwuyest.me -JPORT=443 -JPROTOCOL=https \
  -l results/1000vu-raw.jtl -j results/1000vu.log

# 2000 VU — stress ceiling
jmeter -n \
  -t nighthunt-stress-test.jmx \
  -Jvusers=2000 -Jrampup=120 -Jduration=120 -JsetupRampup=300 \
  -Jpassword=StressTest@123 \
  -JBASE_URL=vawnwuyest.me -JPORT=443 -JPROTOCOL=https \
  -l results/2000vu-raw.jtl -j results/2000vu.log
```

#### Windows PowerShell

```powershell
# 1000 VU — standard load
jmeter -n `
  -t nighthunt-stress-test.jmx `
  -Jvusers=1000 -Jrampup=60 -Jduration=120 -JsetupRampup=180 `
  -Jpassword=StressTest@123 `
  -JBASE_URL=vawnwuyest.me -JPORT=443 -JPROTOCOL=https `
  -l results\1000vu-raw.jtl -j results\1000vu.log
```

#### Docker (any OS)

```bash
# Linux / macOS
docker run --rm \
  -v "$PWD:/work" -w /work \
  justb4/jmeter -n \
  -t nighthunt-stress-test.jmx \
  -Jvusers=1000 -Jrampup=60 -Jduration=120 -JsetupRampup=180 \
  -Jpassword=StressTest@123 \
  -JBASE_URL=vawnwuyest.me -JPORT=443 -JPROTOCOL=https \
  -l results/1000vu-raw.jtl -j results/1000vu.log

# Windows PowerShell (note: use ${PWD} not $PWD in some shells)
docker run --rm `
  -v "${PWD}:/work" -w /work `
  justb4/jmeter -n `
  -t nighthunt-stress-test.jmx `
  -Jvusers=1000 -Jrampup=60 -Jduration=120 -JsetupRampup=180 `
  -Jpassword=StressTest@123 `
  -JBASE_URL=vawnwuyest.me -JPORT=443 -JPROTOCOL=https `
  -l results/1000vu-raw.jtl -j results/1000vu.log
```

---

## 5. Recommended VU Presets

| Scenario     | vusers | rampup | duration | setupRampup | Purpose                        |
|-------------|--------|--------|----------|-------------|--------------------------------|
| Smoke       | 500    | 30     | 60       | 60          | Quick sanity check, < 2 min    |
| Standard    | 1000   | 60     | 120      | 180         | Normal load test baseline      |
| Stress      | 2000   | 120    | 120      | 300         | Near-ceiling, expect errors    |

**Interpretation targets:**

| VU   | Error rate | Avg response | Verdict               |
|------|-----------|--------------|----------------------|
| 500  | < 0.5%    | < 1 s        | Healthy               |
| 1000 | < 2%      | < 3 s        | Acceptable            |
| 2000 | < 5%      | < 6 s        | Near capacity         |
| 2000 | > 10%     | > 8 s        | Backend saturated     |

---

## 6. Generating the HTML Report (with charts)

### Step 1 — Filter steady-state JTL

The raw `.jtl` includes SETUP (register/login) rows which skew results.  
Filter them out first:

**Linux / macOS**
```bash
awk -F',' 'NR==1 || (NF==17 && $3 !~ /auth\/login|auth\/register|nh_stress/)' \
  results/1000vu-raw.jtl > results/1000vu.jtl

wc -l results/1000vu.jtl   # must be > 1; if 1 = header only, use raw instead
```

**Windows PowerShell**
```powershell
# Requires PowerShell 5+
$header = Get-Content results\1000vu-raw.jtl -TotalCount 1
$rows = Get-Content results\1000vu-raw.jtl |
  Select-Object -Skip 1 |
  Where-Object {
    ($_ -split ',').Count -eq 17 -and
    $_ -notmatch 'auth/login|auth/register|nh_stress'
  }
$header, $rows | Set-Content results\1000vu.jtl
(Get-Content results\1000vu.jtl).Count  # must be > 1
```

If the count is 1 (header only), SSL errors in SETUP corrupted the CSV — just use the raw file directly:
```bash
cp results/1000vu-raw.jtl results/1000vu.jtl          # Linux/macOS
Copy-Item results\1000vu-raw.jtl results\1000vu.jtl   # Windows
```

### Step 2 — Generate HTML report

```bash
# Linux / macOS
rm -rf reports/1000vu/
jmeter -g results/1000vu.jtl -o reports/1000vu/

# Windows PowerShell
Remove-Item -Recurse -Force reports\1000vu -ErrorAction SilentlyContinue
jmeter -g results\1000vu.jtl -o reports\1000vu\
```

### Step 3 — Open the report

```bash
# Linux
xdg-open reports/1000vu/index.html

# macOS
open reports/1000vu/index.html

# Windows
Start-Process reports\1000vu\index.html
```

The report contains: **Response Time Over Time**, **Transactions Per Second**,
**Error rate per sampler**, and **Latency percentiles** (p50/p90/p95/p99).

---

## 7. Post-Test Checklist

### Step 1 — Re-enable backend rate limiting (IMPORTANT)

1. Open dashboard → **Load Test Reports** page
2. Click **🟢 Enable Rate Limit**
3. Confirm status flips back to "Enabled"

> Do not leave rate limiting disabled in production — it protects the backend
> from login brute-force and DDoS.

### Step 2 — Upload graphs to dashboard

1. Open the HTML report → right-click any chart → **Save image as PNG**
2. Dashboard → **Load Test Reports** → **Upload Load Test Graph**
3. Enter a label like `1000vu-2026-05-30`, pick the PNG, click Upload
4. Graphs are stored permanently for future comparison

### Step 3 — Clean up test users (optional, monthly)

The SETUP phase creates `nh_stress_1` … `nh_stress_N` in the DB.  
They do not affect normal users but if you want to clean them up:

```sql
-- Run on VPS: docker exec -it nighthunt-mysql mysql -u nighthunt -p nighthunt_db
DELETE FROM users WHERE username LIKE 'nh_stress_%';
```

---

## 8. Troubleshooting

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| All steady-state rows missing from JTL | SSL errors in SETUP corrupt CSV | Use raw JTL directly; check VPS cert |
| `INVALID_TOKEN` on all requests | Login rate limit blocked SETUP users | Disable rate limit in dashboard first |
| `NoHttpResponseException` on auth endpoints | Backend connection pool saturated | Reduce VU count; deploy auth optimization |
| Error rate > 20% at 500 VU | Backend OOM / GC pause | Check `docker stats` on VPS; restart backend |
| JMeter hangs at start | SSL handshake timeout | Check VPS port 443 accessible from your IP |
| Docker: `volume` mount fails on Windows | Path format | Use `${PWD}` or absolute path like `C:/work:/work` |
| JMeter GUI shows no data | Listeners not attached | Add **Summary Report** listener in GUI before running |

