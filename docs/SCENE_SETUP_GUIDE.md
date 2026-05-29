# NightHunt — Scene Setup Guide (Step-by-Step)

> Tài liệu này mô tả **chính xác** từng scene có gì, đặt ở đâu, assign cái gì.  
> Dựa trực tiếp trên code (`BuildScript.cs`, `SceneConfig.cs`, `MapConfig.cs`,  
> `ServerBootstrap.cs`, `GameManager.cs`, `PersistentUICanvas.cs`, `LoadingManager.cs`...).

---

## Kiến trúc tổng quan

```
Project chỉ có 3 file scene thực:

  00_DS_Boot.unity   ← Dedicated Server ONLY (build DS)
  01_Home.unity      ← Client ONLY (build Client — scene index 0)
  02_Map_01.unity    ← SHARED: cả DS lẫn Client đều dùng

Build DS   → scenes: [00_DS_Boot, 02_Map_01, 02_Map_02, ...]
Build Client → scenes: [01_Home, 02_Map_01, 02_Map_02, ...]

KHÔNG có: Bootstrap scene, Login scene, GameplayDS/GameplayCustom scene riêng.
Custom và Ranked dùng CHUNG cùng map scene; phân biệt bằng GameMode enum tại runtime.
```

---

## SCENE 1: `00_DS_Boot.unity`

> **Mục đích:** Boot entry point cho Dedicated Server (Linux Headless).  
> **Không xuất hiện trong Client build.**  
> Khi chạy: khởi động FishNet server → đăng ký với backend → load map scene.

### GameObject: `NetworkManager`

Đây là FishNet's NetworkManager. **Phải là root GameObject** (không có parent).

| Component | Script/Type | Các field quan trọng |
|---|---|---|
| `NetworkManager` | `FishNet.Managing.NetworkManager` | `_startOnHeadless = ✓`, `_dontDestroyOnLoad = ✓`, `_changeFrameRate = ✓`, `_frameRate = 500`, `_shareIds = ✓` |
| `ServerManager` | `FishNet.Managing.Server.ServerManager` | Mặc định OK |
| `ClientManager` | `FishNet.Managing.Client.ClientManager` | Mặc định OK |
| `TimeManager` | `FishNet.Managing.Timing.TimeManager` | `_tickRate = 45`, `_physicsMode = TimeManager.PhysicsMode.Unity` |
| `SceneManager` (FishNet) | `FishNet.Managing.Scened.SceneManager` | Mặc định OK |
| `ObserverManager` | `FishNet.Managing.Observing.ObserverManager` | `_defaultConditions = []` (thêm conditions nếu cần) |
| `StatisticsManager` | `FishNet.Managing.Statistic.StatisticsManager` | `_runInRelease = ☐` |
| `PredictionManager` | `FishNet.Managing.Predicting.PredictionManager` | Mặc định OK |
| `Tugboat` transport | `FishNet.Transporting.Tugboat.Tugboat` | `_port = 7777` (override bởi CLI `--serverPort`), `_maximumClients = 4095`, `_enableIpv6 = ✓` |
| `DefaultPrefabObjects` | `FishNet.Managing.Object.DefaultPrefabObjects` | `_spawnablePrefabs` → trỏ tới `DefaultPrefabObjects.asset` |

**Inspector setup:**
```
NetworkManager GameObject:
  ✓ _startOnHeadless = true      ← DS tự start khi headless
  ✓ _dontDestroyOnLoad = true    ← survive sang map scene
  ✓ _changeFrameRate = true
    _frameRate = 500              ← server không bị giới hạn display framerate
  ✓ _shareIds = true
  _spawnablePrefabs → DefaultPrefabObjects.asset
    (Create: FishNet → DefaultPrefabObjects → right-click → Save To Project)
```

---

### GameObject: `ServerBootstrap`

| Field Inspector | Kiểu | Giá trị |
|---|---|---|
| `networkManager` | `FishNet.Managing.NetworkManager` | Drag GameObject `NetworkManager` vào đây |
| `fallbackPort` | `ushort` | `7777` (dùng trong Editor, bị override bởi `--serverPort` CLI) |
| `fallbackBackendUrl` | `string` | `https://localhost:8443` (Editor dev) |
| `fallbackServerId` | `string` | `localhost-dev-test` |
| `fallbackServerSecret` | `string` | Lấy từ `/api/admin/ds/allocate` response (dev test) |
| `fallbackMapId` | `string` | `map_01` |
| `fallbackMaxPlayers` | `int` | `16` |

**Boot flow (tự động, không cần thêm gì):**
```
Awake() → DontDestroyOnLoad(self) → ParseCommandLineArgs() → BootSequence coroutine
  → Set port trên Tugboat → ServerManager.StartConnection()
  → Wait ≤10s for server start
  → RegisterWithBackend(): POST /api/ds/register (3 retries)
  → LoadGameScene(): dùng MapConfig → SceneConfig → FishNet.SceneManager.LoadGlobalScenes()
  → PostSceneLoadSetup(): subscribe MatchEndManager → NotifyGameReady()
  → NotifyGameReady(): POST /api/ds/game-ready → backend broadcasts ds_ready WS
  → HeartbeatLoop(): POST /api/ds/heartbeat mỗi 30s
```

**Docker entrypoint CLI args** (set bởi Docker ENV via entrypoint.sh):
```
--serverPort    9000
--backendUrl    https://vawnwuyest.me
--serverId      <uuid từ allocate response>
--serverSecret  <secret từ allocate response>
--mapId         map_01
--maxPlayers    4
--expectedPlayers 4
--matchId       <matchId>
```

---

### GameObject: `ServerUISuppressor`

| Field | Giá trị |
|---|---|
| `_serverCanvasTag` | `"ServerCanvas"` |

> Tự động suppress/destroy Canvas nào có tag `ServerCanvas` khi DS load map scene.  
> Đảm bảo DS không render UI (lãng phí RAM/GPU trên headless server).

---

### Scene Roots (tổng kết)
```
00_DS_Boot.unity
  ├── NetworkManager          [FishNet NetworkManager + transport + managers]
  ├── ServerBootstrap         [ServerBootstrap.cs]
  └── ServerUISuppressor      [ServerUISuppressor.cs]
```

---

## SCENE 2: `01_Home.unity`

> **Mục đích:** Client-only. Scene index 0 — load đầu tiên khi app chạy.  
> Chứa: Login UI + Home UI + Lobby UI + tất cả non-gameplay panels.  
> Không có scene Login riêng. Không có Bootstrap scene riêng.

### Sơ đồ hierarchy tổng quan
```
01_Home.unity
  ├── [PERSIST] GameManager           ← SingletonPersistent (DontDestroyOnLoad)
  ├── [PERSIST] RoomState             ← SingletonPersistent (DontDestroyOnLoad)
  ├── [PERSIST] SessionState          ← SingletonPersistent (DontDestroyOnLoad)
  ├── [PERSIST] GameWebSocketService  ← SingletonPersistent (DontDestroyOnLoad)
  ├── [PERSIST] NetworkGameManager    ← Singleton (scene-scoped, NOT DDOL)
  ├── [PERSIST] MatchFlowCoordinator  ← SingletonPersistent (DontDestroyOnLoad)
  ├── [PERSIST] PersistentUICanvas    ← SingletonPersistent (DontDestroyOnLoad)
  │     ├── LoadingManager
  │     ├── MatchLoadingOverlay
  │     ├── MatchFoundOverlay
  │     ├── PingDisplay
  │     └── ToastService
  └── [HOME UI Canvas]
        ├── UINavigator                ← Singleton (scene-scoped, NOT DDOL)
        ├── Main Panels
        │     ├── LoginPanel           ← LoginView.cs
        │     ├── HomePanel            ← HomeView.cs
        │     ├── LobbyPanel           ← CustomLobbyView.cs
        │     ├── FriendPanel          ← FriendPanelView.cs
        │     ├── PartyPanel           ← PartyPanelView.cs
        │     └── PlayerProfilePanel   ← PlayerProfilePanel.cs
        ├── SharedPartyContextMenu     ← SharedPartyContextMenu.cs
        └── GameModalWindow            ← GameModalWindow.cs
```

> **[PERSIST]** = DontDestroyOnLoad — tồn tại qua cả vòng đời app.  
> Khi trở về 01_Home từ gameplay, LoadingManager.Start() chạy lại (scene-local instance) → boot flow → UINavigator.GoHome() → HomeView.OnShow().

---

### Step 1: Tạo `GameManager` GameObject

**Vị trí:** Root của scene (không có parent).

**Components cần thêm vào cùng 1 GameObject:**

| Component | Namespace | Ghi chú |
|---|---|---|
| `GameManager` | `NightHunt.Core` | **Bắt buộc** — singleton chính |
| `BackendHttpClient` | `NightHunt.Services.Backend` | HTTP service |
| `AuthService` | `NightHunt.Services.Auth` | Login/Register/AutoLogin/Logout |
| `GameConfigService` | `NightHunt.Services.Config` | Fetch game config từ backend |
| `FriendService` | `NightHunt.Services.Friend` | Friend list, request, block |
| `PartyService` | `NightHunt.Services.Party` | Party CRUD + WS |
| `RoomService` | `NightHunt.Services.Game` | Room CRUD |
| `ProfileManager` | `NightHunt.Services.Profile` | ELO, coins, profile |

> **Lưu ý:** `ResolveOrAdd<T>()` tự tìm hoặc tạo component nếu field null.  
> Nhưng **nên assign rõ trong Inspector** để tránh AddComponent tạo duplicate.

**Inspector fields của `GameManager`:**
```
[Services]
  backendHttpClient    → BackendHttpClient component (cùng GO)
  authService          → AuthService component (cùng GO)
  gameConfigService    → GameConfigService component (cùng GO)
  friendService        → FriendService component (cùng GO)
  partyService         → PartyService component (cùng GO)
  roomService          → RoomService component (cùng GO)
  gameWebSocketService → GameWebSocketService component (xem bên dưới)
  profileManager       → ProfileManager component (cùng GO)

[Config]
  instanceConfig → InstanceConfig.asset
    (Assets/_Night_Hunt/Data/Configs/CoreSystem Config/Resources/InstanceConfig.asset)

[State]
  sessionState → SessionState component (xem bên dưới)
  roomState    → RoomState component (xem bên dưới)

[Debug]
  _debugConfig → NightHuntDebugConfig.asset (optional)
```

---

### Step 2: Tạo `SessionState` và `RoomState` GameObjects

**Có thể đặt trên cùng GameObject với GameManager hoặc riêng.**

| Component | Ghi chú |
|---|---|
| `SessionState` | SingletonPersistent — lưu userId, accessToken, elo, coins, tier |
| `RoomState` | SingletonPersistent — lưu roomId, gameMode, relay/DS info, matchId |

**Inspector fields của `SessionState`:** Không có field cần assign thủ công.

**Inspector fields của `RoomState`:** Không có field cần assign thủ công.

---

### Step 3: Tạo `GameWebSocketService` GameObject

**Khuyến nghị:** Đặt cùng GameObject với GameManager HOẶC child của nó.

| Field Inspector | Giá trị |
|---|---|
| `wsPathOverride` | Để trống (dùng BackendConfig.wsPath = `/api/ws/game`) |

**Kết nối:** `BackendConfig` → dùng thông qua `BackendHttpClient.BackendConfig`.  
WS URL tự động resolve: `BackendConfig.overrideWsBaseUrl` → `BackendConfig.apiHost (wss/ws)`.

---

### Step 4: Tạo `NetworkGameManager` GameObject

> **Quan trọng:** `NetworkGameManager : Singleton<NetworkGameManager>` — KHÔNG phải DDOL.  
> Bị xóa khi gameplay scene load. Được tạo lại khi 01_Home load.

**Components:**
| Component | Ghi chú |
|---|---|
| `NetworkGameManager` | `NightHunt.Networking` |

**Inspector fields:**
```
networkManager    → Để trống (FishNet NM trong gameplay scene, link tại runtime)
port              → 7777 (default, override bởi DS info từ WS)
defaultServerAddress → "localhost"
_retryDelay       → 3.0
_maxRetries       → 2
```

> `NetworkGameManager` tự tìm FishNet `NetworkManager` khi cần (qua `InstanceFinder`).  
> Không cần drag FishNet NM từ gameplay scene vào đây.

---

### Step 5: Tạo `MatchFlowCoordinator` GameObject

**Components:**
| Component | Ghi chú |
|---|---|
| `MatchFlowCoordinator` | `NightHunt.UI` — SingletonPersistent (DDOL) |

**Không có Inspector fields cần assign.** Subscribe WS events tự động trong `OnEnable`/`Start`.

**Flow:**
```
OnMatchReady  → MatchLoadingOverlay.Show(mapId) → SceneLoader.LoadGame(mapId)
OnDsReady     → NetworkGameManager.NotifyDsReady() → FishNet.StartClient(dsIp, dsPort)
OnMatchCancelled → Toast "match cancelled"
OnMatchEnded  → reset _lastHandledMatchId (ResultsView handles show/navigate)
```

---

### Step 6: Tạo `PersistentUICanvas` GameObject

> SingletonPersistent — DDOL. **Phải là root của scene**, không có parent.  
> Chứa canvas + tất cả persistent UI components.

**Components trên root GameObject:**
| Component | Ghi chú |
|---|---|
| `PersistentUICanvas` | `NightHunt.UI` |
| `Canvas` | `renderMode = ScreenSpaceOverlay`, `sortingOrder = 100` (phải cao hơn game UI) |
| `CanvasScaler` | `UIScaleMode = ScaleWithScreenSize`, `referenceResolution = (1920, 1080)` |
| `GraphicRaycaster` | Mặc định OK |

**Child GameObjects của PersistentUICanvas:**

#### Child 1: `LoadingScreen`
| Component | Field | Giá trị |
|---|---|---|
| `LoadingManager` | `loadingPanel` | Chính GameObject này (hoặc child panel GO) |
| | `progressBar` | UI.Slider child |
| | `loadingText` | TMP_Text child |
| | `retryButton` | UI.Button child (ẩn by default) |
| | `_backendConfig` | `BackendConfig.asset` |
| | `_healthPath` | `/api/actuator/health` |
| | `minLoadingTime` | `1.2` |
| | `internetTimeout` | `5` |

**Inspector fields của `PersistentUICanvas`:**
```
canvas               → Canvas component trên cùng GO
canvasScaler         → CanvasScaler component
graphicRaycaster     → GraphicRaycaster component
loadingManager       → LoadingManager component trên child LoadingScreen
matchLoadingOverlay  → MatchLoadingOverlay component trên child MatchLoadingScreen
matchFoundOverlay    → MatchFoundOverlay component trên child MatchFoundScreen
pingDisplay          → PingDisplay component trên child PingDisplay
toastService         → ToastService component trên child ToastPanel
```

#### Child 2: `MatchLoadingScreen`
| Component | Field | Giá trị |
|---|---|---|
| `MatchLoadingOverlay` | `panel` | Root GameObject của overlay (start inactive) |
| | `canvasGroup` | CanvasGroup để fade |
| | `statusText` | TMP_Text "Starting game server…" |
| | `overallPercentText` | TMP_Text "0%" |
| | `mapNameText` | TMP_Text tên map |
| | `vsLabel` | TMP_Text "VS" |
| | `tipText` | TMP_Text tip ngẫu nhiên |
| | `overallProgressBar` | UI.Slider (fill area) |
| | `playerCardPrefab` | Prefab MatchPlayerCardView |
| | `teamAContainer` | Transform chứa team A cards |
| | `teamBContainer` | Transform chứa team B cards |
| | `tips` | string[] tips hiển thị ngẫu nhiên |
| | `fadeDuration` | `0.3` |
| | `minimumDisplayDuration` | `10` |
| | `delayAfterReady` | `1.5` |
| | `connectionTimeout` | `45` |

#### Child 3: `MatchFoundScreen`
| Component | Field | Giá trị |
|---|---|---|
| `MatchFoundOverlay` | Fields theo Inspector | Popup "Match Found!" |

#### Child 4: `PingDisplay`
| Component | Field | Giá trị |
|---|---|---|
| `PingDisplay` | `pingText` | TMP_Text góc màn hình |

#### Child 5: `ToastPanel`
| Component | Field | Giá trị |
|---|---|---|
| `ToastService` | `toastPrefab` | Prefab toast notification |
| | `toastContainer` | Transform để spawn toasts |

---

### Step 7: Tạo Home UI Canvas

> **Quan trọng:** Canvas này ở trong 01_Home scene, KHÔNG phải DDOL.  
> Bị xóa khi gameplay scene load, tạo lại khi 01_Home reload.

**Root GameObject: `HomeCanvas`**
| Component | Field | Giá trị |
|---|---|---|
| `Canvas` | `renderMode` | `ScreenSpaceOverlay` |
| | `sortingOrder` | `0` (thấp hơn PersistentUICanvas) |
| `CanvasScaler` | `UIScaleMode` | `ScaleWithScreenSize`, ref `(1920, 1080)` |
| `GraphicRaycaster` | | Mặc định |

#### 7a. `UINavigator` — đặt trực tiếp trên `HomeCanvas` hoặc child riêng

> `UINavigator : Singleton<UINavigator>` — KHÔNG DDOL, scene-scoped.

**Inspector wiring — UnityEvents:**
```
OnGoLogin → LoginPanel.SetActive(true) + HomePanel.SetActive(false) + LobbyPanel.SetActive(false)
          → (hoặc) Animator.SetTrigger("GoLogin")

OnGoHome  → HomePanel.SetActive(true) + LoginPanel.SetActive(false) + LobbyPanel.SetActive(false)
          → HomeView.OnShow()     ← PHẢI wire cái này để HomeView init

OnGoLobby → LobbyPanel.SetActive(true) + HomePanel.SetActive(false)
          → CustomLobbyView.OnShow()

OnGoNone  → (thường để trống hoặc hide tất cả)
```

> **Cách đơn giản nhất:** Dùng 1 `UIManager.cs` (MonoBehaviour) có `ShowLogin()`, `ShowHome()`, `ShowLobby()` — wire UnityEvents vào đó.  
> **Cách khác:** Dùng Unity Animator với triggers.  
> `UINavigator` chỉ cần gọi đúng UnityEvent — implementation phụ thuộc vào setup UI của bạn.

---

#### 7b. `Main Panels` — parent empty GameObject chứa tất cả panels

---

##### Panel: `LoginPanel`

**Script:** `LoginView.cs` trên root của panel.

| Field Inspector | Kiểu | Ghi chú |
|---|---|---|
| `usernameInput` | `TMP_InputField` | Login form |
| `passwordInput` | `TMP_InputField` | Login form |
| `rememberMeSwitch` | `SwitchManager` | Custom toggle component |
| `loginButton` | `Button` | Trigger `OnLoginClicked()` |
| `regUsernameInput` | `TMP_InputField` | Register form |
| `emailInput` | `TMP_InputField` | Register form |
| `regPasswordInput` | `TMP_InputField` | Register form |
| `confirmPasswordInput` | `TMP_InputField` | Register form |
| `agreeToTermsSwitch` | `SwitchManager` | Terms checkbox |
| `registerButton` | `Button` | Trigger `OnRegisterClicked()` |
| `loginLoadingIndicator` | `GameObject` | Spinner khi login đang chờ |
| `registerLoadingIndicator` | `GameObject` | Spinner khi register đang chờ |
| `authService` | `AuthService` | Từ GameManager (drag component) |

**UnityEvents trên LoginView:**
```
onLoginSuccess  → UINavigator.GoHome() + HomeView.OnShow()
onLoginFailed   → (optional: shake animation)
onRegisterSuccess → (optional: show success toast + switch to login form)
onRegisterFailed  → (optional: shake animation)
```

---

##### Panel: `HomePanel`

**Script:** `HomeView.cs` trên root của panel.

| Field Inspector | Kiểu | Ghi chú |
|---|---|---|
| `_sessionState` | `SessionState` | Để show username/ELO/coins |
| `_profileManager` | `ProfileManager` | Để refresh profile |
| `_matchmakingService` hoặc reference | | Để trigger matchmaking queue |
| `txt_Username` | `TMP_Text` | Display name |
| `txt_ELO` | `TMP_Text` | ELO số |
| `txt_Rank` | `TMP_Text` | Tier (Bronze, Silver...) |
| `txt_Coins` | `TMP_Text` | Coin balance |
| `btn_Ranked` | `Button` | Vào ranked matchmaking |
| `btn_Custom` | `Button` | Tạo/join custom lobby |
| `btn_Friends` | `Button` | Mở FriendPanel |
| `btn_Party` | `Button` | Mở PartyPanel |
| `btn_Settings` | `Button` | Mở SettingsPanel |
| `characterAvatarImage` | `Image` | Selected character avatar |

**HomeView.OnShow()** được gọi bởi UINavigator.OnGoHome UnityEvent:
```
→ Fetch profile (ProfileManager.FetchProfile)
→ Subscribe WS events: OnPartyInvitationReceived, OnFriendStatusChanged
→ Update UI với SessionState data
```

---

##### Panel: `LobbyPanel`

**Script:** `CustomLobbyView.cs` trên root của panel.

| Field Inspector | Kiểu | Ghi chú |
|---|---|---|
| `_roomService` | `RoomService` | |
| `_roomState` | `RoomState` | |
| `_sessionState` | `SessionState` | |
| `_gameWebSocket` | `GameWebSocketService` | Để subscribe WS events |
| `playerSlots` | `PlayerSlotView[]` | 4 slot panels (2 team A, 2 team B) |
| `btn_Start` | `Button` | Host only — trigger `OnStartClicked()` |
| `btn_Ready` | `Button` | Non-host — toggle ready |
| `btn_Leave` | `Button` | Leave room |
| `txt_RoomCode` | `TMP_Text` | Hiển thị room code |
| `txt_Status` | `TMP_Text` | "Waiting for players..." |
| `mapCarousel` | `MapCarouselView` | Chọn map (host only) |

**WS events CustomLobbyView subscribe:**
```
OnRoomUpdated           → RefreshPlayerList()
OnPlayerJoined          → RefreshPlayerList() + toast
OnPlayerLeft            → RefreshPlayerList() + toast
OnPlayerReady           → RefreshPlayerList()
OnRoomStatusChanged     → if IN_GAME: MatchLoadingOverlay.Show()
OnRoomDisbanded         → UINavigator.GoHome()
OnYouWereKicked         → UINavigator.GoHome() + toast
OnGameStarting          → (handle relay session setup)
```

---

##### Panel: `FriendPanel`

**Script:** `FriendPanelView.cs` trên root của panel.

| Field Inspector | Kiểu | Ghi chú |
|---|---|---|
| `_friendService` | `FriendService` | |
| `_sessionState` | `SessionState` | |
| `_gws` | `GameWebSocketService` | |
| `friendListContainer` | `Transform` | Parent của friend items |
| `friendItemPrefab` | `GameObject` | Prefab FriendItemView |
| `searchInput` | `TMP_InputField` | Tìm friend theo username |
| `btn_Search` | `Button` | Gọi SearchUser |
| `btn_Close` | `Button` | Đóng panel |
| `pendingRequestContainer` | `Transform` | Incoming requests |
| `pendingItemPrefab` | `GameObject` | Prefab PendingRequestView |
| `sharedContextMenu` | `SharedPartyContextMenu` | Context menu (View Profile / Invite to Party...) |

**WS events FriendPanelView subscribe:**
```
OnFriendStatusChanged    → update status indicator (ONLINE/OFFLINE/IN_GAME)
OnFriendRequestReceived  → add to pending requests list + toast
OnFriendRequestAccepted  → refresh friend list + toast
OnFriendRemoved          → remove from list
```

---

##### Panel: `PartyPanel`

**Script:** `PartyPanelView.cs` trên root của panel.

| Field Inspector | Kiểu | Ghi chú |
|---|---|---|
| `_partyService` | `PartyService` | |
| `_sessionState` | `SessionState` | |
| `_gws` | `GameWebSocketService` | |
| `memberListContainer` | `Transform` | Parent của member slots |
| `memberItemPrefab` | `GameObject` | Prefab PartyMemberItemView |
| `btn_Leave` | `Button` | Rời party |
| `btn_Invite` | `Button` | Mở invite dialog |
| `btn_Close` | `Button` | Đóng panel |
| `txt_Status` | `TMP_Text` | "In Party (2/4)" |

**WS events PartyPanelView subscribe:**
```
OnPartyMemberJoined   → Refresh member list
OnPartyMemberLeft     → Refresh member list
OnPartyMemberKicked   → Refresh member list
OnPartyDisbanded      → Đóng panel, về Home
OnPartyHostChanged    → Refresh UI (transfer leader indicator)
OnPartyStatusChanged  → Cập nhật status text
```

---

##### Panel: `PlayerProfilePanel`

**Script:** `PlayerProfilePanel.cs` trên root của panel.

> **Context:** Modal xem public profile của người chơi khác.  
> Được gọi bằng `PlayerProfilePanel.Instance?.Show(userId)`.

| Field Inspector | Kiểu | Ghi chú |
|---|---|---|
| `root` | `GameObject` | Root panel GO (inactive khi ẩn) |
| `backdrop` | `Button` | Fullscreen button — click đóng panel |
| `txt_Username` | `TMP_Text` | |
| `txt_ELO` | `TMP_Text` | |
| `txt_Tier` | `TMP_Text` | |
| `txt_WinLoss` | `TMP_Text` | Format: "W / L" |
| `txt_WinRate` | `TMP_Text` | Format: "XX%" |
| `btn_Close` | `Button` | |
| `loadingIndicator` | `GameObject` | Spinner (optional) |

**Gọi từ:** `FriendPanelView`, `SharedPartyContextMenu`, `CustomLobbyView`.  
**API:** GET `/api/profile/{userId}` (public profile, không cần friendship).

---

#### 7c. `SharedPartyContextMenu`

**Script:** `SharedPartyContextMenu.cs`  
**Vị trí:** Sibling của `Main Panels` (không phải child) để tránh clipping.  
**Canvas settings:** `SetAsLastSibling()` khi hiện để luôn render trên top.

| Field Inspector | Kiểu | Ghi chú |
|---|---|---|
| `btn_ViewProfile` | `Button` | Xem profile → `PlayerProfilePanel.Show(userId)` |
| `btn_Kick` | `Button` | Kick member (host only) |
| `btn_Leave` | `Button` | Rời party |
| `btn_TransferLeader` | `Button` | Transfer leader (host only) |

**Invoke bởi:** `PartyMemberListView.OnAvatarSlotClicked()` và `FriendPanelView`.

---

#### 7d. `GameModalWindow`

**Script:** `GameModalWindow.cs`  
**Vị trí:** Sibling của `Main Panels`.

| Field | Ghi chú |
|---|---|
| `modalRoot` | Root panel GO |
| `titleText` | TMP_Text |
| `messageText` | TMP_Text |
| `confirmButton` | Button |
| `cancelButton` | Button |

**API:**
```csharp
GameModalWindow.Instance?.ShowConfirm("Title", "Message", onConfirm, onCancel);
GameModalWindow.Instance?.ShowNotice("Title", "Message", onClose);
```

---

### Step 8: Kiểm tra Connection Flow trong Inspector

**UINavigator.OnGoHome UnityEvent PHẢI wire:**
```
Target: HomePanel (GameObject)
Function: GameObject.SetActive (true)   ← bật panel

Target: HomeView
Function: HomeView.OnShow()             ← trigger init, data refresh
```

**LoadingManager flow (tự động, không wire thêm):**
```
Start() → InitFlow coroutine:
  1. WaitForGameManager (5s timeout)
  2. WaitForPersistentUICanvas (3s timeout)
  3. Warm-up delay 0.1s
  4. WaitForInternet (polling)
  5. WaitForBackendHealth (polling GET /api/actuator/health)
  6. CheckAutoLoginFlow:
       - có refresh token + AutoLogin OK → _targetPanel = Home
       - AutoLogin fail → xóa token → _targetPanel = Login
       - không có token → _targetPanel = Login
  7. FetchGameConfigFlow (nếu Home)
  8. minLoadingTime guarantee (1.2s)
  9. Navigate() → Hide() → UINavigator.ShowPanel(_targetPanel)
```

---

## SCENE 3: `02_Map_01.unity`

> **Mục đích:** Gameplay scene — SHARED giữa DS build và Client build.  
> DS tải bằng `FishNet.SceneManager.LoadGlobalScenes(ReplaceScenes.All)`.  
> Client tải bằng `SceneLoader.LoadGame(SceneId.GameMap_01)`.

### Phân biệt DS vs Client tại runtime

```
FishNet: IsServerStarted → true  = DS side (server authority)
FishNet: IsClientStarted → true  = Client side (player)
FishNet: IsHostStarted   → true  = Custom_Relay host (cả server lẫn client)
```

Dùng `#if UNITY_SERVER` (compile time) cho code chỉ chạy trên DS build,  
hoặc `InstanceFinder.IsServerStarted` (runtime) cho shared scenes.

---

### NetworkBehaviour GameObjects (FishNet — spawned by server)

> Các NetworkObject bên dưới phải được **spawn bởi server** sau khi clients kết nối.  
> Đặt prefab trong `DefaultPrefabObjects.asset`.

---

### Static Scene Objects (không phải NetworkObject)

#### GameObject: `NetworkManager`

> **Quan trọng:** Trong 02_Map_01, NetworkManager dùng cho **client** (không startOnHeadless).  
> DS dùng NetworkManager từ 00_DS_Boot (DDOL).  
> FishNet có cơ chế `DestroyNewest` để xử lý trùng lặp khi DS load map scene.

| Component | Field | Giá trị |
|---|---|---|
| `FishNet.NetworkManager` | `_startOnHeadless` | `☐` (false — DS dùng cái của 00_DS_Boot) |
| | `_dontDestroyOnLoad` | `☐` (false — scene-scoped NM cho client) |
| Tugboat | `_clientAddress` | `"localhost"` (override bởi NetworkGameManager khi kết nối DS) |
| | `_port` | `7777` (override tại runtime) |

> Khi DS load map scene: FishNet phát hiện duplicate NM → DestroyNewest → giữ NM từ 00_DS_Boot.  
> Khi Client load map scene: NM này là NM duy nhất → dùng bình thường.

#### GameObject: `ServerGameManager`

| Component | Field | Ghi chú |
|---|---|---|
| `ServerGameManager` | `networkManager` | Drag FishNet NM |
| | `_matchEndManager` | Drag MatchEndManager GO |
| | `_spawnManager` | Drag PlayerSpawnManager GO |
| | `_phaseManager` | Drag MatchPhaseManager GO |
| | `_expectedPlayerCount` | `4` (override bởi `BootstrappedExpectedPlayers` nếu > 0) |

**Server flow:**
```
OnStartServer() → WaitForAllPlayers() → BeginPhase1() → ...
OnAllPlayersReady() → StartMatch() → StartCoroutine(GameLoop())
```

#### GameObject: `MatchEndManager`

| Component | Field | Ghi chú |
|---|---|---|
| `MatchEndManager` | `_phaseManager` | Drag MatchPhaseManager |
| | `_scoringSystem` | Drag ScoringSystem |
| | `_teamIds` | `[0, 1]` (2-team match) |

**Events fired:**
```
OnMatchEnded (C# event) → ServerBootstrap.OnMatchEnded → ReportMatchEndAndShutdown
RpcNotifyMatchEnd (ObserversRpc) → clients' GameplayEventBus.Publish(MatchEndedEvent)
GameplayEventBus.Publish (server-direct) → ResultsView.OnMatchEnded (on server if Custom_Relay host)
```

> **Bug fix đã áp dụng (session này):** `ResultsView._matchEndProcessed` guard ngăn double-fire  
> trên Custom_Relay host (ObserversRpc fires trên host-client, tạo event lần 2).

#### GameObject: `SpawnPoints_TeamA`

```
SpawnPoints_TeamA
  ├── SpawnPoint_A1   [empty Transform]
  ├── SpawnPoint_A2
  ├── SpawnPoint_A3
  └── SpawnPoint_A4
```

**Dùng bởi:** `PlayerSpawnManager.GetSpawnPoint(teamId)`

#### GameObject: `SpawnPoints_TeamB`

```
SpawnPoints_TeamB
  ├── SpawnPoint_B1
  ├── SpawnPoint_B2
  ├── SpawnPoint_B3
  └── SpawnPoint_B4
```

#### GameObject: `ResultsView` (Client only)

> **Chỉ hiển thị trên Client.** DS không cần (ServerUISuppressor sẽ ignore GO không có Canvas).

| Component | Field | Ghi chú |
|---|---|---|
| `ResultsView` | `_panel` | Root panel GO (inactive khi start) |
| | `_resultHeaderText` | TMP_Text "VICTORY/DEFEAT/DRAW" |
| | `_reasonText` | TMP_Text lý do kết thúc |
| | `_countdownText` | TMP_Text đếm ngược |
| | `_scoreboardContainer` | Transform chứa result rows |
| | `_resultRowPrefab` | Prefab ResultRowView |
| | `_eloPanel` | GO panel ELO change (chỉ show khi Ranked) |
| | `_eloChangeText` | TMP_Text "+25 ELO" |
| | `_continueButton` | Button "Continue" |
| | `_postMatchCountdown` | `10` (giây trước khi tự về Home) |

**Flow:**
```
MatchEndedEvent (từ GameplayEventBus) → OnMatchEnded → ShowResults + CountdownCoroutine
CountdownCoroutine hết → NavigatePostMatch():
  1. PostMatchResultAsync() ← Custom_Relay host gửi /api/match/end/custom
  2. ProfileManager.FetchProfile() ← refresh ELO/coins cho Home screen
  3. NetworkGameManager.Disconnect() ← stop FishNet TRƯỚC khi load scene
  4. RoomState.ClearRoom() ← xóa room state + reset connection flags
  5. SceneLoader.LoadHome() ← về 01_Home
```

#### HUD Canvas (Client only)

> Tag canvas này là `"ServerCanvas"` để `ServerUISuppressor` tự động destroy trên DS.

```
HUDCanvas [tag = "ServerCanvas"]
  ├── HUDController
  ├── HP_Bar
  ├── Ammo_Counter
  ├── Killboard
  ├── Phase_Indicator
  └── ...
```

---

## Thêm Map Mới (02_Map_02, 02_Map_03...)

**Checklist khi thêm map:**

1. **Tạo scene:** `02_Map_02.unity` — copy hierarchy từ `02_Map_01`

2. **`SceneConfig.cs`:** Enum `SceneId` đã có `GameMap_02 = 101` ✓  
   (Nếu cần thêm: thêm enum value `GameMap_03 = 102`, etc.)

3. **`SceneConfig.asset` (Inspector):**  
   Thêm entry: `id = GameMap_02`, `sceneName = "02_Map_02"`

4. **`MapConfig.asset` (Inspector):**  
   Thêm `MapEntry`: `mapId = "map_02"`, `displayName = "Arctic Base"`, `sceneId = GameMap_02`, `supportedModes = ["2v2", "4v4"]`

5. **`BuildScript.cs`:** Uncomment dòng trong cả `DsScenes` và `ClientScenes`:
   ```csharp
   "Assets/_Night_Hunt/Scenes/02_Map_02.unity",
   ```

6. **File → Build Settings:**  
   Thêm `02_Map_02.unity` vào danh sách — cho cả DS build và Client build.

7. **ServerBootstrap:** Không cần sửa (đã dùng `MapConfig.TryGetById()` sau fix session này).

---

## Cấu hình BackendConfig (ScriptableObject)

**Path:** `Assets/_Night_Hunt/Data/Configs/CoreSystem Config/Resources/BackendConfig.asset`

```
Inspector fields:
  Dev Server URL:    https://localhost:8443
  Prod Server URL:   https://vawnwuyest.me
  Dev WS URL:        wss://localhost:8443/ws
  Prod WS URL:       wss://vawnwuyest.me/ws
  Use Production:    ☐ (dev) / ✓ (production build)
  allowSelfSignedCert: ✓ (dev only — bỏ khi production)
  wsPath:            /api/ws/game
```

> **LUÔN bật `Use Production` khi build phát hành.** Dev mode bypass SSL certificate.

---

## Checklist cuối — trước khi Build

### DS Build
```
☐ 00_DS_Boot: NetworkManager._startOnHeadless = true
☐ 00_DS_Boot: NetworkManager._dontDestroyOnLoad = true
☐ 00_DS_Boot: ServerBootstrap.networkManager assigned
☐ 00_DS_Boot: Tugboat._port = 7777
☐ DefaultPrefabObjects.asset có đầy đủ spawnable prefabs
☐ Build Settings (Dedicated Server): scenes = [00_DS_Boot, 02_Map_01, ...]
☐ Build target: Dedicated Server (Linux x64, Server subtarget)
☐ BackendConfig.UseProd = true (production build)
```

### Client Build
```
☐ 01_Home: GameManager đủ tất cả service references
☐ 01_Home: PersistentUICanvas đủ tất cả child component references
☐ 01_Home: LoadingManager._backendConfig trỏ BackendConfig.asset
☐ 01_Home: UINavigator.OnGoHome wired tới HomeView.OnShow()
☐ 01_Home: UINavigator.OnGoLogin wired tới LoginPanel.SetActive(true)
☐ 01_Home: UINavigator.OnGoLobby wired tới CustomLobbyView.OnShow()
☐ 01_Home: PlayerProfilePanel tất cả fields (root, backdrop, txt_*, btn_Close) assigned
☐ 01_Home: SharedPartyContextMenu.btn_TransferLeader assigned
☐ 01_Home: PartyMemberItemView prefab có transferLeaderButton field
☐ 02_Map_01: ResultsView tất cả fields assigned
☐ 02_Map_01: MatchEndManager._phaseManager + _scoringSystem assigned
☐ 02_Map_01: HUDCanvas.tag = "ServerCanvas"
☐ Build Settings (Client): scenes = [01_Home, 02_Map_01, ...]
☐ BackendConfig.UseProd = true (production build)
☐ BackendConfig.allowSelfSignedCert = false (production)
```

---

## Bugs đã fix trong session này (liên quan đến scene flow)

| File | Bug | Fix |
|---|---|---|
| `ResultsView.cs` | Double `OnMatchEnded` fire trên Custom_Relay host (ObserversRpc fires trên host-client + direct server publish) | Thêm `_matchEndProcessed` guard |
| `ResultsView.cs` | FishNet server/client không disconnect trước LoadHome → FishNet NM (DDOL) còn active trong 01_Home | Thêm `NetworkGameManager.Instance?.Disconnect()` trước `ClearRoom()` |
| `ServerBootstrap.cs` | Hardcoded map name switch (`"map_01" → "02_Map_01"`) không dùng MapConfig → thêm map mới cần sửa thủ công | Dùng `MapConfig.TryGetById()` + `SceneConfig.GetSceneName()` |
