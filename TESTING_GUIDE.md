# NightHunt — Hướng dẫn Setup & Test Production

> **Cập nhật:** Session này thêm/sửa:
> - **`IBackendClient` using directive** fix trong `PlayerProfilePanel.cs`
> - **`ProfileManager` field** thêm vào `GameManager` — tự động re-fetch ELO/coins khi về Home sau match
> - **`docker rm -f`** thay `docker stop` trong `DockerManagerService` — container bị reclaim không còn tồn đọng trên VPS
> - **`smoke-test.yml`** GitHub Actions workflow — 6 flow smoke tests tự động sau mỗi deploy
> - **`PlayerProfilePanel`** + **`btn_TransferLeader`** đã được inject trực tiếp vào `01_Home.unity` qua YAML edit

---

## MỤC LỤC

1. [Prerequisites — Chuẩn bị trước khi bắt đầu](#1-prerequisites)
2. [Setup VPS lần đầu](#2-setup-vps)
3. [Setup GitHub Actions Secrets](#3-github-secrets)
4. [Setup Unity Build Scenes & Components](#4-unity-setup)
5. [Deploy lần đầu (manual)](#5-first-deploy)
6. [CI/CD — Sau đó tự động hóa](#6-cicd)
7. [Smoke Tests — 10 Flow test checklist](#7-smoke-tests)
8. [Log patterns để monitor từng flow](#8-logs)
9. [Troubleshooting phổ biến](#9-troubleshooting)

---

## 1. Prerequisites

### VPS Requirements
- Ubuntu 22.04+ (hoặc Debian 11+)
- Min 2 vCPU, 4GB RAM (8GB recommended cho production)
- Ports cần mở trên firewall:
  ```
  22/tcp    — SSH
  80/tcp    — HTTP (Let's Encrypt challenge)
  443/tcp   — HTTPS (game API + WebSocket)
  7777-7900/udp — Custom game relay UDP
  9000-9100/udp — Ranked game Dedicated Server UDP
  3001/tcp  — Dashboard (tùy chọn, có thể chỉ expose nội bộ)
  ```

### Local machine Requirements
- Docker Desktop (để build local nếu cần)
- PowerShell / bash
- `ssh` và `scp` available
- Unity Editor 2022.3 LTS với modules: Linux Build Support (IL2CPP), Linux Dedicated Server

### Accounts cần có
- [x] GitHub account + repo `nvanquyet/NightHunt` (hoặc tên repo của bạn)
- [x] GitHub PAT với scopes: `write:packages`, `read:packages`
- [x] Unity license (Personal hoặc Pro) — cần cho CI build DS

---

## 2. Setup VPS

### 2.1 Install Docker
```bash
# SSH vào VPS
ssh ubuntu@20.2.235.140

# Install Docker
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
newgrp docker

# Verify
docker --version  # phải >= 24.x
```

### 2.2 Install Docker Compose plugin
```bash
sudo apt-get update
sudo apt-get install -y docker-compose-plugin
docker compose version  # phải >= 2.x
```

### 2.3 Open firewall
```bash
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 7777:7900/udp   # Custom game relay
sudo ufw allow 9000:9100/udp   # Dedicated Server UDP
sudo ufw allow 3001/tcp        # Dashboard (có thể skip nếu chỉ dùng nội bộ)
sudo ufw enable
sudo ufw status
```

### 2.4 Tạo thư mục deploy
```bash
mkdir -p ~/nighthunt
cd ~/nighthunt
```

### 2.5 SSL Certificate (Let's Encrypt) — cần DOMAIN trỏ vào VPS trước
```bash
# Đảm bảo A record: vawnwuyest.me → 20.2.235.140 đã propagate
# Kiểm tra: curl https://dns.google/resolve?name=vawnwuyest.me&type=A

# Chạy certbot standalone lần đầu (backend chưa chạy):
sudo apt-get install -y certbot
sudo certbot certonly --standalone \
  -d vawnwuyest.me \
  --email your-email@gmail.com \
  --agree-tos --non-interactive

# Cert sẽ ở: /etc/letsencrypt/live/vawnwuyest.me/
sudo ls /etc/letsencrypt/live/vawnwuyest.me/
```

### 2.6 Tạo SSH key cho GitHub Actions deploy
```bash
# Trên local machine:
ssh-keygen -t ed25519 -C "github-actions-deploy" -f ~/.ssh/nighthunt_deploy -N ""

# Copy public key lên VPS:
ssh-copy-id -i ~/.ssh/nighthunt_deploy.pub ubuntu@20.2.235.140

# Private key (nighthunt_deploy) → save làm GitHub Secret VPS_SSH_KEY
cat ~/.ssh/nighthunt_deploy  # copy nội dung này vào GitHub Secret
```

---

## 3. GitHub Actions Secrets

Vào: `GitHub Repo → Settings → Secrets and variables → Actions → New repository secret`

| Secret Name | Giá trị | Ghi chú |
|-------------|---------|---------|
| `GHCR_TOKEN` | GitHub PAT với `write:packages` | Settings → Developer Settings → Tokens |
| `VPS_HOST` | `20.2.235.140` | IP VPS |
| `VPS_USER` | `ubuntu` | SSH user |
| `VPS_SSH_KEY` | Nội dung file `~/.ssh/nighthunt_deploy` | Private key PEM |
| `VPS_DEPLOY_PATH` | `/home/ubuntu/nighthunt` | Thư mục deploy |
| `ENV_PRODUCTION_B64` | base64 encoded `.env.production` | Xem bên dưới |
| `DS_ADMIN_SECRET` | Giá trị `DS_ADMIN_SECRET` trong `.env.production` | Cho smoke tests |
| `ADMIN_SECRET` | Giá trị `X-Admin-Secret` | Dashboard smoke test |
| `PRODUCTION_API_URL` | `https://vawnwuyest.me/api` | Base URL cho smoke-test.yml |
| `SMOKE_TEST_USER` | Username tài khoản test | Pre-create trong DB |
| `SMOKE_TEST_PASS` | Password tài khoản test | |
| `UNITY_LICENSE` | Unity license XML | Xem phần Unity CI |
| `UNITY_EMAIL` | Email Unity account | |
| `UNITY_PASSWORD` | Password Unity account | |

### Generate ENV_PRODUCTION_B64
```powershell
# PowerShell (Windows):
[Convert]::ToBase64String([IO.File]::ReadAllBytes("NightHuntServer\.env.production")) | Set-Clipboard
# Paste vào GitHub Secret ENV_PRODUCTION_B64

# Bash (Linux/Mac):
base64 -w0 NightHuntServer/.env.production | pbcopy
```

### Điền .env.production trước khi encode
```bash
# Các giá trị BẮTBUỘC phải thay trong .env.production:
MYSQL_PASSWORD=<strong-password-min-16-chars>
MYSQL_ROOT_PASSWORD=<strong-root-password>
DB_PASSWORD=<same-as-MYSQL_PASSWORD>
REDIS_PASSWORD=<strong-redis-password>
JWT_SECRET=<openssl rand -base64 48>     # 64 chars minimum
DS_ADMIN_SECRET=<openssl rand -hex 32>   # 64 hex chars
GHCR_TOKEN=<your-github-pat>
DASHBOARD_ADMIN_PASS=<strong-password>
DASHBOARD_ROOT_PASS=<strong-password>
CERTBOT_EMAIL=<your-email>
```

---

## 4. Unity Setup — Scene & Component Checklist

### 4.1 Kiến trúc Scene thực tế (theo BuildScript.cs)

Dự án có **3 file scene thực**, chia làm **2 build target** riêng biệt:

```
Assets/_Night_Hunt/Scenes/
  00_DS_Boot.unity   ← DS-only (Dedicated Server boot)
  01_Home.unity      ← Client-only (Login + Home + tất cả UI)
  02_Map_01.unity    ← SHARED (cả DS lẫn Client đều dùng)
```

**Build DS (Linux Headless — `BuildScript.BuildDedicatedServer`):**
| Index trong build | File scene | Vai trò |
|---|---|---|
| 0 | `00_DS_Boot.unity` | Boot DS: khởi động FishNet server, đăng ký với backend |
| 1 | `02_Map_01.unity` | Gameplay (DS tải khi backend gọi `game-ready`) |

**Build Client (Win64 / Android — `BuildScript.BuildClient`):**
| Index trong build | File scene | Vai trò |
|---|---|---|
| 0 | `01_Home.unity` | Login → Home → Lobby (toàn bộ UI non-gameplay) |
| 1 | `02_Map_01.unity` | Gameplay (tải khi nhận WS `match_ready`) |

> **KHÔNG có** Bootstrap scene riêng, Login scene riêng, hay GameplayDS/GameplayCustom scene riêng.
> Khi thêm map mới (ví dụ `02_Map_02.unity`) → thêm vào cả `DsScenes` và `ClientScenes` trong `BuildScript.cs`.

**Custom vs Ranked dùng chung map scene:**
Cùng một `02_Map_01.unity` phục vụ cả 2 chế độ. Sự khác biệt nằm ở runtime:
- `RoomState.CurrentGameMode == GameMode.Custom_Relay` → relay-based, không tính ELO
- `RoomState.CurrentGameMode == GameMode.Ranked_DS` → DS-based, tính ELO + rank points
- `MatchFlowCoordinator` detect mode từ WS event `game_starting` và set `RoomState` trước khi load scene

---

### 4.2 Scene `00_DS_Boot.unity` — Dedicated Server Boot

> Chỉ có trong **DS build**. Client không thấy scene này.

**GameObject: `NetworkManager`**
| Component | Ghi chú |
|---|---|
| `FishNet.NetworkManager` | `_startOnHeadless = true`, `_dontDestroyOnLoad = true` |
| `TimeManager` | `_tickRate = 45` |
| `ServerManager` | Quản lý FishNet server connections |
| `ClientManager` | Cần cho host-mode (không dùng trên pure DS) |
| `SceneManager` (FishNet) | Load map scene khi DS sẵn sàng |
| `ObserverManager` | Visibility condition management |
| `StatisticsManager` | Optional, `_runInRelease = false` |
| `Tugboat` transport | `_port = 7777` (override bởi CLI arg `--port`) |
| `SpawnablePrefabs` | Trỏ tới `DefaultPrefabObjects.asset` |

**GameObject: `ServerBootstrap`**
| Field Inspector | Giá trị |
|---|---|
| `networkManager` | Trỏ tới GameObject `NetworkManager` ở trên |
| `fallbackPort` | `7777` (dùng khi chạy trong Editor, override bởi `--port` CLI) |
| `fallbackBackendUrl` | `https://localhost:8443` (Editor dev) |
| `fallbackServerId` | `localhost-dev-test` |
| `fallbackServerSecret` | Lấy từ `/api/admin/ds/allocate` response |
| `fallbackMapId` | `map_01` |
| `fallbackMaxPlayers` | `16` |

> `ServerBootstrap` flow: parse CLI args → set Tugboat port → StartServer → POST `/api/ds/register` → heartbeat loop → nhận game-ready → load `02_Map_01`

**GameObject: `ServerUISuppressor`**
- Tự động suppress/destroy bất kỳ Canvas nào có tag `ServerCanvas` khi DS load map scene
- Đảm bảo DS không render UI gây lãng phí RAM/GPU

---

### 4.3 Scene `01_Home.unity` — Client Entry Point (Login + Home + Lobby)

> Chỉ có trong **Client build**. Scene index 0 — load đầu tiên khi app chạy.
> Không có scene Login riêng — toàn bộ Login UI là một Panel bên trong scene này.

#### DontDestroyOnLoad objects (tồn tại suốt vòng đời app)

**GameObject: `PersistentUICanvas`** — Canvas lớp trên cùng, không bị xóa khi chuyển scene
| Component / Child | Ghi chú |
|---|---|
| `PersistentUICanvas` (script) | Singleton, DontDestroyOnLoad |
| `LoadingManager` | Boot flow: internet check → auto-login → navigate |
| `MatchLoadingOverlay` | Overlay khi chờ DS boot / relay connect |
| `MatchFoundOverlay` | "Match Found!" popup (countdown accept) |
| `PingDisplay` | Hiển thị ping góc màn hình |
| `ToastService` | Toast notification |

**Inspector fields của `LoadingManager`:**
```
loadingPanel      → GameObject chứa spinner + progress bar (bắt đầu active)
progressBar       → UI.Slider (0→1)
loadingText       → TMP_Text
retryButton       → UI.Button (ẩn khi online, hiện khi offline)
_backendConfig    → BackendConfig ScriptableObject
_healthPath       → /api/actuator/health
minLoadingTime    → 1.2
internetTimeout   → 5
```

**GameObject: `GameManager`** — DontDestroyOnLoad, holds tất cả services
| SerializeField | Kiểu | Ghi chú |
|---|---|---|
| `backendClient` | `BackendHttpClient` | HTTP service |
| `backendConfig` | `BackendConfig` | ScriptableObject |
| `profileManager` | `ProfileManager` | ELO, coins, profile data |
| `roomService` | `RoomService` | Room CRUD API |
| `matchmakingService` | `MatchmakingService` | Matchmaking queue API |
| `friendService` | `FriendService` | Friend list API |
| `partyService` | `PartyService` | Party API |
| `gameWebSocketService` | `GameWebSocketService` | WS connection |
| `networkGameManager` | `NetworkGameManager` | FishNet client |
| `matchFlowCoordinator` | `MatchFlowCoordinator` | WS → scene flow |

> Tất cả service đều có `ResolveOrAdd<T>()` — nếu field null sẽ tự tìm hoặc tạo. Nhưng nên assign rõ trong Inspector.

**GameObject: `NetworkGameManager`** — DontDestroyOnLoad
| Field | Ghi chú |
|---|---|
| `networkManager` | FishNet NetworkManager trong map scene |
| Tugboat client | Kết nối tới DS IP:Port khi nhận `ds_ready` |

**GameObject: `MatchFlowCoordinator`** — DontDestroyOnLoad
- Subscribe `GameWebSocketService` events: `OnMatchReady`, `OnDsReady`, `OnMatchCancelled`, `OnMatchEnded`
- `match_ready` → resolve map scene từ `MapConfig` → `MatchLoadingOverlay.Show()` → `SceneLoader.LoadGame(mapSceneId)`
- `ds_ready` → `NetworkGameManager.NotifyDsReady()` → FishNet connect

#### Scene-local objects (trong 01_Home, bị xóa khi load gameplay scene)

**UI Hierarchy trong `01_Home.unity`:**
```
[Canvas] — Main UI
  ├── Main Panels
  │   ├── LoginPanel            — LoginPanelView: username/pass fields, Login + Register buttons
  │   ├── HomePanel             — HomePanelView: Ranked, Custom, thống kê, avatar/coins
  │   ├── LobbyPanel            — CustomLobbyView: room slots, Start button (host only)
  │   ├── FriendPanel           — FriendPanelView: friend list, search, online status
  │   ├── PartyPanel            — PartyPanelView: party members, invite, leave
  │   ├── PlayerProfilePanel    — PlayerProfilePanel: modal xem profile người chơi khác ← MỚI
  │   └── SettingsPanel         — SettingsPanelView (optional)
  ├── SharedPartyContextMenu    — Dropdown: View Profile / Kick / Leave / Transfer Leader
  ├── GameModalWindow           — Confirm dialogs
  └── ...
```

**`PlayerProfilePanel` Inspector fields** (gán trong Unity Editor):
```
root              → GameObject bao bọc toàn panel (inactive khi ẩn)
backdrop          → Button fullscreen — click đóng panel
txt_Username      → TMP_Text
txt_ELO           → TMP_Text
txt_Tier          → TMP_Text
txt_WinLoss       → TMP_Text (format "W / L")
txt_WinRate       → TMP_Text (format "XX%")
btn_Close         → Button
loadingIndicator  → Spinner GO (optional, ẩn khi data loaded)
```
> Gọi bằng: `PlayerProfilePanel.Instance?.Show(userId)`
> Tự động gọi từ: `FriendPanelView`, `SharedPartyContextMenu`, `CustomLobbyView`

**`SharedPartyContextMenu` Inspector fields:**
```
btn_ViewProfile       → Button (prefab)
btn_Kick              → Button (prefab)
btn_Leave             → Button (prefab)
btn_TransferLeader    → Button (prefab) ← MỚI (fileID 390211862 trong scene YAML)
```

---

### 4.4 Scene `02_Map_01.unity` — Shared Gameplay Scene

> Dùng cho cả **Client và DS**. Chứa toàn bộ gameplay logic + environment.
> Phân biệt DS vs Client tại runtime qua `InstanceFinder.IsServerStarted` (FishNet).

**Shared GameObjects (cả DS lẫn Client):**
| GameObject | Component | Ghi chú |
|---|---|---|
| `NetworkManager` | FishNet.NetworkManager | Trong client build: cấu hình Tugboat client. Trong DS: `_startOnHeadless=true` |
| `ServerGameManager` | `ServerGameManager` | Active chỉ khi IsServer. Quản lý spawn, phase transitions |
| `MatchEndManager` | `MatchEndManager` | POST `/api/match/end/ranked` hoặc `/custom` khi game kết thúc |
| Environment | Mesh renderers, colliders | Map geometry |
| `SpawnPoints_TeamA/B` | `Transform[]` | Điểm spawn team |

**Client-only GameObjects** (có thể disable trên DS bằng `#if !UNITY_SERVER` hoặc `IsServerBuild` check):
| GameObject | Component | Ghi chú |
|---|---|---|
| `PlayerCamera` | `CinemachineCamera` | Follow player |
| `HUD` | `HUDController` | HP, ammo, kill feed |
| `ResultsView` | `ResultsView` | Bảng kết quả sau match |
| `InputManager` | `PlayerInputManager` | Unity Input System |

**Flow trong `02_Map_01` (Ranked_DS):**
```
DS side:
  ServerBootstrap nhận game-ready → load map scene
  ServerGameManager.Start() → WaitForAllPlayers() → StartMatch()
  MatchEndManager detect thắng thua → POST /api/match/end/ranked → send WS match_ended

Client side:
  MatchFlowCoordinator nhận match_ready → SceneLoader.LoadGame(GameMap_01)
  NetworkGameManager.NotifyDsReady() → FishNet.StartClient(dsIp, dsPort)
  Match running...
  MatchFlowCoordinator nhận match_ended → ResultsView.Show()
  ResultsView countdown → NavigatePostMatch() → ProfileManager.FetchProfile() → SceneLoader.LoadHome()
```

**Flow trong `02_Map_01` (Custom_Relay):**
```
Host client:
  FishNet.StartHost() (via NetworkGameManager)
  Relay server forward packets từ other clients

All clients:
  FishNet.StartClient(relayIp, relayPort)
  Game running... (không có DS)
  MatchEndManager detect thắng thua → POST /api/match/end/custom → no ELO change
  WS match_ended → ResultsView → SceneLoader.LoadHome()
```

---

### 4.5 Build Settings (File → Build Settings)

**DS Build (Unity Editor → Build Settings):**
```
Platform: Dedicated Server (hoặc StandaloneLinux64 + Server subtarget)
Scenes:
  [0] Assets/_Night_Hunt/Scenes/00_DS_Boot.unity
  [1] Assets/_Night_Hunt/Scenes/02_Map_01.unity
  (thêm map mới ở đây theo thứ tự)
```

**Client Build (Unity Editor → Build Settings):**
```
Platform: PC/Mac/Android
Scenes:
  [0] Assets/_Night_Hunt/Scenes/01_Home.unity
  [1] Assets/_Night_Hunt/Scenes/02_Map_01.unity
  (thêm map mới ở đây theo thứ tự)
```

> **Quan trọng:** Scene indices trong Build Settings phải khớp với thứ tự trong `BuildScript.DsScenes[]` và `BuildScript.ClientScenes[]`. `SceneLoader` và `SceneConfig` dùng tên scene (không dùng index) nên tên file phải đúng.

---

### 4.6 Thêm map mới (ví dụ `02_Map_02.unity`)

1. Tạo scene file `02_Map_02.unity`, copy hierarchy từ `02_Map_01`
2. Trong `SceneConfig.cs` — enum `SceneId` đã có `GameMap_02 = 101` ✓
3. Trong `SceneConfig.asset` (Inspector) — thêm entry: `id = GameMap_02`, `sceneName = "02_Map_02"`
4. Trong `MapConfig.asset` (Inspector) — thêm `MapEntry`: `mapId = "map_02"`, `sceneId = GameMap_02`, điền display name
5. Trong `BuildScript.cs` — uncomment dòng `"Assets/_Night_Hunt/Scenes/02_Map_02.unity"` trong cả `DsScenes` và `ClientScenes`
6. **File → Build Settings** → thêm `02_Map_02.unity` vào danh sách cho cả DS build và Client build

---

### 4.7 BackendConfig (ScriptableObject)

```
Assets/_Night_Hunt/Data/Configs/CoreSystem Config/Resources/BackendConfig.asset

Inspector:
  Dev Server URL:  https://localhost:8443
  Prod Server URL: https://vawnwuyest.me
  Dev WS URL:      wss://localhost:8443/ws
  Prod WS URL:     wss://vawnwuyest.me/ws
  Use Production:  ✓ (bật khi build production, tắt khi dev local)
```

---

## 5. First Deploy (Manual)

### 5.1 Copy files lên VPS lần đầu
```powershell
# PowerShell — từ thư mục gốc workspace
$VPS = "ubuntu@20.2.235.140"
$DEPLOY = "/home/ubuntu/nighthunt"

# Copy server files
scp NightHuntServer/docker-compose.yml "${VPS}:${DEPLOY}/"
scp -r NightHuntServer/relay "${VPS}:${DEPLOY}/"
scp -r NightHuntServer/docker "${VPS}:${DEPLOY}/"

# Copy env production (điền đầy đủ trước)
scp NightHuntServer/.env.production "${VPS}:${DEPLOY}/"
```

### 5.2 Deploy lần đầu trên VPS
```bash
ssh ubuntu@20.2.235.140
cd ~/nighthunt

# Login ghcr.io
echo "YOUR_GHCR_TOKEN" | docker login ghcr.io -u nvanquyet --password-stdin

# Pull images
docker pull ghcr.io/nvanquyet/nighthunt-backend:latest

# Build relay (Python, nhanh)
docker-compose --env-file .env.production build nighthunt-relay

# Start toàn bộ stack
docker-compose --env-file .env.production up -d

# Xem status
docker-compose --env-file .env.production ps

# Xem logs backend
docker logs -f nighthunt-backend --tail 100
```

### 5.3 Verify deploy thành công
```bash
# Health check
curl https://vawnwuyest.me/api/actuator/health
# Expected: {"status":"UP","components":{...}}

# Relay health
curl http://localhost:7776/health
# Expected: {"status":"ok","sessions":0,"free_ports":124}
```

---

## 6. CI/CD — Tự động hóa

### 6.1 Trigger Backend CI/CD
```bash
# Commit + push bất kỳ thay đổi trong NightHuntServer/
git add NightHuntServer/
git commit -m "feat: update match end flow"
git push origin main

# GitHub Actions sẽ tự:
# 1. Build Gradle + run tests
# 2. Push Docker image lên ghcr.io
# 3. SSH vào VPS: pull image → docker-compose up -d backend
# 4. Wait health check
# 5. Run smoke tests
```

### 6.2 Trigger DS CI/CD
```bash
# Commit + push thay đổi trong NightHuntClient/
git add NightHuntClient/
git commit -m "feat: fix throwable aim controller"
git push origin main

# GitHub Actions sẽ tự:
# 1. Unity headless Linux build (StandaloneLinux64 + Server)
# 2. Package vào Docker image
# 3. Push lên ghcr.io/nvanquyet/nighthunt-ds:latest
# 4. Notify backend via /api/admin/ds/update-image → DS_IMAGE_REF cập nhật
```

### 6.3 Unity License cho CI
```bash
# Xem hướng dẫn game-ci: https://game.ci/docs/github/activation
# Tóm tắt:
# 1. Run workflow game-ci/unity-request-activation-file@v2
# 2. Download .alf file từ artifact
# 3. Upload lên https://license.unity3d.com → download .ulf
# 4. Add .ulf content làm GitHub Secret: UNITY_LICENSE
```

---

## 7. Smoke Tests — 10 Flow Test Checklist

### Cách chạy smoke tests thủ công

**Option A: GitHub Actions (recommended)**
```
GitHub Repo → Actions → "Smoke Tests (Production)" → Run workflow
```

**Option B: curl trực tiếp (nhanh)**
```bash
# Set base URL
BASE_URL="https://vawnwuyest.me"

# Login lấy token
TOKEN=$(curl -sf -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"test_user","password":"Test@123456"}' \
  | jq -r '.data.accessToken')
echo "Token: $TOKEN"
```

---

### FLOW 1: Login → Load Data → Init

**Mục tiêu:** User mở app → auto-login (nếu có refresh token) → load profile → connect WebSocket → show Home screen

**Test steps:**
```bash
# 1. Register (nếu chưa có account)
curl -X POST "$BASE_URL/api/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"username":"test01","password":"Test@123456","email":"test01@test.com"}'
# ✅ Expected: {"success":true,"data":{"userId":...,"accessToken":"..."}}

# 2. Login
curl -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"test01","password":"Test@123456"}'
# ✅ Expected: accessToken + refreshToken trong response

# 3. Load profile
curl "$BASE_URL/api/profile" \
  -H "Authorization: Bearer $TOKEN"
# ✅ Expected: username, elo, tier, coins, selectedCharacterId

# 4. Auto-login (simulate app restart với refresh token)
curl -X POST "$BASE_URL/api/auth/auto-login" \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"<refresh-token-từ-login>"}'
# ✅ Expected: mới accessToken
```

**Log pattern cần thấy (backend):**
```
[AUTH] Login SUCCESS: userId=X username=test01
[WS] Connection established: userId=X
[STATUS] User X → ONLINE
```

**Unity client log pattern:**
```
[LoadingManager] AutoLogin success → navigate to Home
[GameWebSocket] Connected to wss://vawnwuyest.me/ws
[ProfileManager] Profile loaded: username=test01 elo=1000
```

---

### FLOW 2: Matchmaking + Custom Game

**Test 2A: Ranked Matchmaking**
```bash
# 1. Join matchmaking queue
curl -X POST "$BASE_URL/api/matchmaking/queue" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"mode":"2v2"}'
# ✅ Expected: {"success":true,"data":{"queueId":"..."}}

# 2. Kiểm tra queue status
curl "$BASE_URL/api/matchmaking/queue/status" \
  -H "Authorization: Bearer $TOKEN"
# ✅ Expected: {"status":"searching","eloBand":{"min":900,"max":1100}}

# 3. Leave queue (test cancel)
curl -X DELETE "$BASE_URL/api/matchmaking/queue" \
  -H "Authorization: Bearer $TOKEN"
# ✅ Expected: {"success":true}
```

**Test 2B: Custom Game**
```bash
# 1. Create room
ROOM=$(curl -sf -X POST "$BASE_URL/api/rooms/create" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"mode":"2v2","isPublic":false,"mapId":"map_01"}')
ROOM_ID=$(echo $ROOM | jq -r '.data.id')
ROOM_CODE=$(echo $ROOM | jq -r '.data.roomCode')
echo "Room: $ROOM_ID code=$ROOM_CODE"
# ✅ Expected: roomId, roomCode (6 chars), status=WAITING

# 2. Join room với user thứ 2 (cần token2)
curl -X POST "$BASE_URL/api/rooms/join-by-code" \
  -H "Authorization: Bearer $TOKEN2" \
  -H 'Content-Type: application/json' \
  -d "{\"roomCode\":\"$ROOM_CODE\"}"
# ✅ Expected: room với 2 players

# 3. Disband
curl -X DELETE "$BASE_URL/api/rooms/$ROOM_ID/disband" \
  -H "Authorization: Bearer $TOKEN"
# ✅ Expected: {"success":true}
```

**Log pattern:**
```
[MM] Queue joined: userId=X mode=2v2 elo=1000
[MM] Match found: matchId=... players=[X,Y]
[Room] room_disbanded broadcast: roomId=X
[Relay] Session closed: token=... (khi custom game disband)
```

---

### FLOW 3: Player Status

**Test steps:**
```bash
# Online status sau login (WebSocket connected)
# Disconnect WS → status OFFLINE sau 30s (stale eviction)

# Check online count (dashboard)
curl "$BASE_URL/api/dashboard/stats" \
  -H "Authorization: Bearer $TOKEN"
# ✅ Expected: {"onlineUsers":N,"activeSessions":N,...}
```

**Log pattern:**
```
[STATUS] User X → ONLINE (friends notified: [Y,Z])
[STATUS] User X → OFFLINE (stale connection evicted)
[WS] Stale eviction: userId=X (no ping for 30s)
```

---

### FLOW 4: Friend System

**Test steps:**
```bash
# Cần 2 user: test01 (TOKEN) và test02 (TOKEN2)

# 1. Send friend request
curl -X POST "$BASE_URL/api/friends/requests" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"targetUserId":2}'
# ✅ Expected: {"success":true}

# 2. Accept request (từ test02)
curl -X PUT "$BASE_URL/api/friends/requests/1/accept" \
  -H "Authorization: Bearer $TOKEN2"
# ✅ Expected: {"success":true}

# 3. Get friend list (từ test01)
curl "$BASE_URL/api/friends" \
  -H "Authorization: Bearer $TOKEN"
# ✅ Expected: [{"userId":2,"username":"test02","status":"ONLINE",...}]

# 4. View public profile (GET /api/profile/{userId}) — không cần friendship
curl "$BASE_URL/api/profile/2" \
  -H "Authorization: Bearer $TOKEN"
# ✅ Expected: {userId, username, elo, tier, totalWins, totalLosses} — coins=0 (ẩn)

# 5. Remove friend
curl -X DELETE "$BASE_URL/api/friends/2" \
  -H "Authorization: Bearer $TOKEN"
# ✅ Expected: {"success":true}

# 6. Block user
curl -X POST "$BASE_URL/api/friends/block/2" \
  -H "Authorization: Bearer $TOKEN"
# ✅ Expected: {"success":true}

# 7. Transfer party leader (host → member)
#    Cần tạo party + member vào trước:
curl -X POST "$BASE_URL/api/party/create" -H "Authorization: Bearer $TOKEN"
# (token2 accept invite)
curl -X POST "$BASE_URL/api/party/transfer-leader" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"newLeaderId": 2}'
# ✅ Expected: PartyDTO với hostUserId=2; WS event party_host_changed broadcast
```

**Log pattern:**
```
[Friend] Request sent: from=1 to=2
[Friend] Request accepted: friendshipId=X
[STATUS] Broadcasting online status to friends of userId=1
[Profile] Public profile viewed: targetUserId=2
[Party] Leader transferred: party=X oldHost=1 newHost=2
```

**Unity — PlayerProfilePanel test:**
1. Mở FriendPanelView → right-click một friend → "View Profile"
2. ✅ Expected: modal mở, hiển thị loading spinner, sau đó populate username/ELO/tier/Win-Loss/WinRate
3. Click backdrop hoặc nút X → panel đóng

---

### FLOW 5: DS Startup (Ranked) + Relay (Custom)

**Test 5A: DS Allocate**
```bash
# Yêu cầu DS_DOCKER_ENABLED=false (local dev) hoặc có DS image

# Admin allocate
curl -X POST "$BASE_URL/api/admin/ds/allocate" \
  -H "X-Admin-Secret: $DS_ADMIN_SECRET" \
  -H 'Content-Type: application/json' \
  -d '{"region":"vn","mapId":"map_01","expectedPlayers":4}'
# ✅ Expected: {"serverId":"...","ip":"...","port":9000,"status":"starting"}

# Simulate DS register (nếu docker disabled)
curl -X POST "$BASE_URL/api/ds/register" \
  -H "X-DS-Secret: <server-secret>" \
  -H 'Content-Type: application/json' \
  -d '{"serverId":"<serverId>","serverSecret":"<secret>","maxPlayers":4}'
# ✅ Expected: {"success":true}

# Simulate DS game-ready (triggers ds_ready WS broadcast)
curl -X POST "$BASE_URL/api/ds/game-ready" \
  -H 'Content-Type: application/json' \
  -d '{"serverId":"<serverId>","serverSecret":"<secret>"}'
# ✅ Expected: ds_ready WS event sent to all players in match
```

**Test 5B: Relay session**
```bash
# Relay health
curl http://localhost:7776/health  # (internal VPS)
# ✅ Expected: {"status":"ok","sessions":0,"free_ports":124}

# List sessions
curl http://localhost:7776/session/list
# ✅ Expected: [] (empty khi không có game)

# Tạo relay session thủ công (test)
curl -X POST http://localhost:7776/session/create \
  -H 'Content-Type: application/json' \
  -d '{"token":"test-session-12345678"}'
# ✅ Expected: {"token":"test-session-12345678","port":7777}

# Close session
curl -X POST http://localhost:7776/session/close \
  -H 'Content-Type: application/json' \
  -d '{"token":"test-session-12345678"}'
# ✅ Expected: {"ok":true}
```

**Log pattern:**
```
[DS-Alloc] allocateServerForMatch ▶ region=vn expectedPlayers=4 matchId=...
[DockerManager] Starting container: nighthunt-ds-XXXXXXXX port=9000
[DS-Svc] game-ready ▶ serverId=... → READY
[DS-Svc] ds_ready broadcast: sent=4/4 players matchId=...
[Relay] Session created: token=XXXXXXXX port=7777
```

---

### FLOW 6: Loading / Connection Progress

**Kiến trúc loading (không có Bootstrap scene riêng):**
- `01_Home.unity` load (scene 0 trong Client build)
- `PersistentUICanvas` Awake → `LoadingManager` bắt đầu boot flow
- `GameManager` Awake → init tất cả services
- `LoadingManager.StartBootFlow()` chạy async

**Unity-side test (Play mode):**
1. Enter Play mode với `01_Home` là scene active → LoadingManager chạy tự động
2. Log expected:
```
[LoadingManager] Checking internet connectivity...
[LoadingManager] Backend health: UP
[LoadingManager] Auto-login attempt (refresh token found)
[LoadingManager] Auto-login SUCCESS → userId=X
[GameWebSocket] Connecting to wss://vawnwuyest.me/ws...
[GameWebSocket] Connected!
[LoadingManager] Init complete → UINavigator.GoHome()
```

3. Khi match bắt đầu (WS `match_ready` received từ `01_Home`):
```
[MFC] match_ready ▶ matchId=... mode=Ranked_DS mapId=map_01
[MFC] match_ready — scene: mapId='map_01' → sceneId=GameMap_01 isRelay=false
[MatchLoadingOverlay] Show: stage=WaitingForDS
[SceneLoader] LoadGame → GameMap_01 (02_Map_01)
→ Unity loads 02_Map_01.unity (scene 1 trong Client build)
[MFC] ds_ready ▶ dsIp=20.2.235.140 dsPort=9000
[NGM] NotifyDsReady → FishNet.StartClient(ip=20.2.235.140 port=9000)
[NGM] FishNet Client connected → server
[MatchLoadingOverlay] Hide
```

4. Khi Custom_Relay match bắt đầu:
```
[MFC] match_ready ▶ mode=Custom_Relay isRelay=true
[MFC] relay mode — MarkDsReady immediately (no DS boot)
[SceneLoader] LoadGame → GameMap_01 (02_Map_01)
[NGM] NotifyRelayReady → FishNet.StartClient(relayIp, relayPort)
```

---

### FLOW 7: Game Loop → Home

**DS calls /match/end/ranked:**
```bash
curl -X POST "$BASE_URL/api/match/end/ranked" \
  -H "X-DS-Secret: $DS_ADMIN_SECRET" \
  -H 'Content-Type: application/json' \
  -d '{
    "matchId":"<matchId>",
    "winnerTeamId":1,
    "endReason":"ELIMINATION",
    "playerResults":[
      {"userId":1,"teamId":1,"displayName":"test01","kills":5,"deaths":2,"score":150},
      {"userId":2,"teamId":2,"displayName":"test02","kills":1,"deaths":5,"score":30}
    ]
  }'
# ✅ Expected:
#   - ELO updated cho cả 2 players
#   - match_ended WS event broadcast
#   - DS container stopped (reclaimServerForMatch)
#   - Relay session closed (nếu custom mode)
```

**Log pattern:**
```
[MatchEnd] Processed match X winner=1 reason=ELIMINATION players=2
[DS-Reclaim] Stopping DS container nighthunt-ds-XXXXXXXX matchId=X
[DS-Reclaim] DS container stopped → DB marked STOPPED
[Relay] Session closed: token=... (nếu custom game)
[WS] match_ended broadcast: userIds=[1,2]
```

**Unity client log:**
```
[MatchFlowCoordinator] match_ended received: winnerTeam=1
[ResultsView] Show results: ELO +25 (user won)
[ResultsView] Continue → navigate to Home
[NGM] Disconnect: server stopped
```

---

### FLOW 8: Dashboard

**Test:**
```bash
# Stats tổng quan
curl "$BASE_URL/api/dashboard/stats" -H "Authorization: Bearer $TOKEN"
# ✅ Expected: {onlineUsers, totalMatches, activeRooms, activeDSCount, relaySessionCount}

# Analytics — online history (24h)
curl "$BASE_URL/api/dashboard/analytics/online-history" -H "Authorization: Bearer $TOKEN"
# ✅ Expected: [{timestamp:"...",value:N},...] (time series)

# Top players
curl "$BASE_URL/api/dashboard/analytics/top-players" -H "Authorization: Bearer $TOKEN"
# ✅ Expected: [{userId,username,elo,tier,wins},...] top 10

# Active DS sessions
curl "$BASE_URL/api/admin/ds" -H "X-Admin-Secret: $DS_ADMIN_SECRET"
# ✅ Expected: list DS containers với status

# Admin config update — DS image ref
curl -X POST "$BASE_URL/api/admin/ds/update-image" \
  -H "X-Admin-Secret: $DS_ADMIN_SECRET" \
  -H 'Content-Type: application/json' \
  -d '{"imageRef":"ghcr.io/nvanquyet/nighthunt-ds:abc1234"}'
# ✅ Expected: {"success":true,"imageRef":"..."}
```

---

### FLOW 9: Container/Relay Reclaim

**Test auto-reclaim sau match end** (đã test trong FLOW 7)

> **Lưu ý (session fix):** `DockerManagerService.stopContainer()` đã được đổi sang
> `docker rm -f <containerId>` — vừa stop vừa remove trong 1 lệnh. Containers start
> với `--rm` tự xóa khi exit bình thường, nhưng crashed/stuck containers sẽ được
> force-remove bởi scheduled cleanup.

**Test scheduled cleanup:**
```bash
# Kiểm tra containers đang chạy
ssh ubuntu@20.2.235.140 "docker ps | grep nighthunt-ds"

# Simulate DS crash (stop container thủ công)
ssh ubuntu@20.2.235.140 "docker stop nighthunt-ds-XXXXXXXX"

# Sau 90s → cleanupDeadServers scheduled job chạy
# Log expected:
# [DS-Svc] Cleaning dead server (no heartbeat): XXXXXXXX
# [DockerManager] Stopping container: XXXXXXXX
```

**Test relay session TTL:**
```bash
# Sessions idle > 2h tự expire
# Kiểm tra:
curl http://localhost:7776/session/list  # trên VPS
# Sessions với idle_sec > 7200 sẽ biến mất sau cleanup interval (60s)
```

---

### FLOW 10: Config & .env

**Checklist config production:**
```bash
# Trên VPS — verify các giá trị quan trọng
ssh ubuntu@20.2.235.140

# Check backend env
docker exec nighthunt-backend env | grep -E "VPS_PUBLIC_IP|RELAY|DS_DOCKER"
# ✅ Expected:
# VPS_PUBLIC_IP=20.2.235.140
# RELAY_SERVER_URL=http://nighthunt-relay:7776
# RELAY_HOST=20.2.235.140
# DS_DOCKER_ENABLED=true

# Check relay container
docker exec nighthunt-relay curl http://localhost:7776/health
# ✅ Expected: {"status":"ok","sessions":0,"free_ports":124}

# Check SSL cert
curl -I https://vawnwuyest.me/api/actuator/health
# ✅ Expected: HTTP/2 200, cert từ Let's Encrypt

# Check DB migrations applied
docker exec nighthunt-mysql mysql -u nighthunt -p<password> nighthunt \
  -e "SHOW TABLES;"
# ✅ Expected: users, rooms, matches, ... (tất cả tables từ Flyway)
```

---

## 8. Log Patterns

### Log format (production — logback-spring.xml)
```
2026-04-17T14:32:01.123+0700 INFO  [http-nio-8080-exec-3] c.n.auth.service.AuthService       rid=a1b2c3d4 uid=42 mid=-- [AUTH] Login SUCCESS: userId=42 username=test01
```
Fields: `timestamp | level | thread | logger(40) | rid=requestId uid=userId mid=matchId | message`

> **MDC tự động inject** bởi `MdcLoggingFilter` — mọi log trong cùng request đều có cùng `rid`.

### Backend log filter tags (SLF4J prefix)
```
[AUTH]          — Login, register, token rotation
[WS]            — WebSocket connect/disconnect/message
[STATUS]        — Player online/offline, status broadcast
[Room]          — Room CRUD, player join/leave
[MM]            — Matchmaking queue, match found
[DS-Alloc]      — DS server allocation
[DS-Svc]        — DS lifecycle (register, heartbeat, game-ready)
[DS-Reclaim]    — DS container stop after match end
[Docker]        — Docker CLI operations (start/stop container)
[Relay]         — Relay session create/close
[MatchEnd]      — Match result processing, ELO update
[Friend]        — Friend request, accept, block
[Party]         — Party create, invite, kick, queue, leader transfer
[Profile]       — Profile load, character update, public profile view
```

### Unity client log filter tags (Unity Console filter box)
```
[LoadingManager]        — App boot, auto-login, health check
[GameWebSocket]         — WS connect, disconnect, event received
[NGM]                   — NetworkGameManager (FishNet connect/disconnect)
[MatchFlowCoord]        — WS event → scene transition
[ProfileManager]        — Profile load/update
[RoomService]           — Room API calls
[PlayerProfilePanel]    — Public profile modal open/load
[PartyPanel]            — Party UI actions (kick, transfer leader)
[PartyService]          — Party API calls
[DS-Connect]            — DS connection progress
```

### Production log view
```bash
# Backend logs (tail 200 lines)
docker logs --tail 200 -f nighthunt-backend

# Filter by tag
docker logs nighthunt-backend 2>&1 | grep "\[DS-"
docker logs nighthunt-backend 2>&1 | grep "\[MatchEnd\]"
docker logs nighthunt-backend 2>&1 | grep "\[Relay\]"
docker logs nighthunt-backend 2>&1 | grep "\[Party\]"
docker logs nighthunt-backend 2>&1 | grep "\[Profile\]"

# Filter by userId (MDC) — xem tất cả logs của user cụ thể
docker logs nighthunt-backend 2>&1 | grep "uid=42"

# Filter by requestId (MDC) — trace 1 request cụ thể
docker logs nighthunt-backend 2>&1 | grep "rid=a1b2c3d4"

# File logs (nếu dùng production rolling file)
docker exec nighthunt-backend tail -f /app/logs/nighthunt.log
docker exec nighthunt-backend grep "ERROR" /app/logs/nighthunt-error.log

# Relay logs
docker logs --tail 50 -f nighthunt-relay
```

---

## 9. Troubleshooting

### "Backend container not healthy"
```bash
docker logs nighthunt-backend --tail 100
# Common causes:
# - DB connection fail → check MYSQL_PASSWORD match
# - Flyway migration fail → check DB exists
# - Port conflict → check SERVER_PORT in .env.production
```

### "Relay health check fail"
```bash
docker logs nighthunt-relay --tail 50
# Common: port 7776 already used → check docker ps
# Fix: docker-compose --env-file .env.production restart nighthunt-relay
```

### "DS container không start"
```bash
# Check docker.sock volume mounted
docker exec nighthunt-backend ls /var/run/docker.sock
# Check ghcr login
docker exec nighthunt-backend docker pull ghcr.io/nvanquyet/nighthunt-ds:latest
```

### "match_ended WS không nhận được"
```bash
# Check WS session còn active
docker logs nighthunt-backend 2>&1 | grep "userId=<X>"
# Nếu thấy "disconnected" trước match_ended → client disconnect quá sớm
# Tăng NGM reconnect timeout
```

### "SSL cert expired"
```bash
# Certbot auto-renew mỗi 12h trong container
docker logs nighthunt-certbot
# Manual renew:
docker exec nighthunt-certbot certbot renew
docker exec nighthunt-nginx nginx -s reload
```

### "DS UDP packets không tới"
```bash
# Check firewall
sudo ufw status | grep 9000
# Check DS binding
docker exec nighthunt-ds-XXXXXXXX netstat -unlp | grep 9000
# Test UDP connectivity từ client machine:
# nc -u 20.2.235.140 9000
```

---

## Checklist hoàn thiện — trước khi test production

### Backend
- [ ] `.env.production` — tất cả `CHANGE_ME_*` đã thay
- [ ] MySQL password mạnh (min 16 chars, có số + chữ hoa)
- [ ] JWT_SECRET min 32 chars (random)
- [ ] DS_ADMIN_SECRET min 32 chars (random)
- [ ] GHCR_TOKEN có scope `read:packages`, `write:packages`
- [ ] `SPRING_PROFILES_ACTIVE=prod` trong docker-compose env (để kích hoạt logback-spring.xml prod profile)
- [ ] Flyway migrations chạy thành công (`SHOW TABLES` trong MySQL)
- [ ] Actuator health trả về UP
- [ ] Relay health trả về `{"status":"ok"}`
- [ ] Log format verify: `docker logs nighthunt-backend 2>&1 | head -5` → thấy `rid=` và `uid=` trong output

### GitHub Actions
- [ ] Tất cả 8 required secrets đã thêm vào repo (xem Section 3)
- [ ] `UNITY_LICENSE` (nếu muốn DS CI)
- [ ] Deploy workflow chạy thành công ít nhất 1 lần
- [ ] Smoke test workflow pass sau deploy
- [ ] GitHub Secret `PRODUCTION_API_URL` = `https://vawnwuyest.me/api` đã add (dùng bởi smoke-test.yml)
- [ ] GitHub Secret `ADMIN_SECRET` đã add (dùng cho dashboard smoke test)

### Unity Client

**Build Settings — DS Build (Dedicated Server target):**
- [ ] Scene 0: `Assets/_Night_Hunt/Scenes/00_DS_Boot.unity`
- [ ] Scene 1: `Assets/_Night_Hunt/Scenes/02_Map_01.unity`
- [ ] Map scenes mới: thêm vào `BuildScript.DsScenes[]` VÀ Build Settings

**Build Settings — Client Build (Win64/Android):**
- [ ] Scene 0: `Assets/_Night_Hunt/Scenes/01_Home.unity`
- [ ] Scene 1: `Assets/_Night_Hunt/Scenes/02_Map_01.unity`
- [ ] Map scenes mới: thêm vào `BuildScript.ClientScenes[]` VÀ Build Settings

**BackendConfig (ScriptableObject):**
- [ ] `UseProd = true` trong production build
- [ ] `ProdServerUrl = https://vawnwuyest.me`
- [ ] `ProdWebSocketUrl = wss://vawnwuyest.me/ws`

**00_DS_Boot.unity — Inspector:**
- [ ] `NetworkManager` GameObject: Tugboat `_port = 7777`, `_startOnHeadless = true`
- [ ] `ServerBootstrap.networkManager` trỏ tới FishNet NetworkManager component

**01_Home.unity — Inspector:**
- [ ] `GameManager`: tất cả service fields assigned (hoặc auto-resolved, verify không có null warning)
- [ ] `PersistentUICanvas`: `LoadingManager`, `MatchLoadingOverlay`, `MatchFoundOverlay`, `ToastService` assigned
- [ ] `LoadingManager._backendConfig` trỏ tới `BackendConfig.asset`
- [ ] `NetworkGameManager.networkManager` trỏ đúng FishNet NetworkManager
- [ ] `PlayerProfilePanel` trong `Main Panels`: các field `root`, `backdrop`, `txt_*`, `btn_Close` assigned
  - MonoBehaviour đã inject vào scene YAML (fileID 157468060) — assign fields trong Editor
- [ ] `SharedPartyContextMenu.btn_TransferLeader` assigned (fileID 390211862 trong scene YAML)
- [ ] `PartyMemberItemView` prefab có `transferLeaderButton` field assigned

### VPS Network
- [ ] Port 443 open → `curl https://vawnwuyest.me/api/actuator/health`
- [ ] Port 7777-7900/udp open → relay UDP reachable
- [ ] Port 9000-9100/udp open → DS UDP reachable
- [ ] SSL cert valid → `curl -I https://vawnwuyest.me`
- [ ] Log file volume mounted: `docker exec nighthunt-backend ls /app/logs/`
