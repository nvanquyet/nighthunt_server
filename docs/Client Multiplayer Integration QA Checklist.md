# Client Multiplayer Integration QA Checklist

Date: 2026-06-03

This checklist tracks the client/server runtime paths that must be verified after the Netty realtime gateway migration and the latest client fixes.

## Current Contract

- [x] Business commands stay RESTful: auth, profile, friends, party, room, matchmaking.
- [x] Realtime events use `POST /api/realtime/tickets` then `wss://<host>/api/ws/game?ticket=<one-time-ticket>`.
- [x] Unity client requests a fresh realtime ticket before WebSocket connect/reconnect.
- [x] Gateway owns `/api/ws/game`; business API does not serve legacy Servlet/WebFlux WebSocket.
- [x] Friend/party events invalidate client state and refresh snapshots from REST.

## Friend, Party, Profile

- [ ] Login fresh install and confirm realtime socket reaches `connected`.
- [ ] Open friend panel, add friend, accept request, remove friend; verify `friend_*` events refresh UI.
- [ ] View profile from friend context menu; verify public profile loads via `GET /api/profile/{userId}`.
- [ ] Create party, invite friend, accept invite; verify `party_member_joined` updates `PartyState`.
- [ ] Transfer leader, kick, leave, disband; verify `party_*` event handlers refresh or clear state.
- [ ] Queue 4v4 with `allowFill=false`; verify only original party members stay grouped.
- [ ] Queue 4v4 with `allowFill=true`; verify partial parties/solos can fill the team.
- [ ] End match; verify server clears match grouping but original pre-match party remains.

## Match Start

- [ ] Custom relay solo: start room, load map, hide overlay before timeout once FishNet client and local player exist.
- [ ] Custom relay party: host starts, clients receive `game_starting`, `RoomState` has correct player count, host/client connect.
- [ ] Dedicated server ranked: `match_ready` shows overlay, `ds_ready` carries IP/port, clients connect to DS.
- [ ] `match_ended` returns clients to post-match/home flow and clears match-specific room state.
- [ ] Reconnect test: disconnect socket, reconnect with new ticket, refresh friend/party/room snapshots.

## Gameplay Input And Animation

- [x] Keyboard `Space` is default `Player/Roll`; `leftCtrl` is default `Player/Jump`.
- [x] Legacy saved binding overrides are versioned out so old `Space=Jump` defaults do not override the new mapping.
- [x] Reload end clears reload animator state and returns upper-body to the current weapon idle state.
- [x] Ranged shot event prepares upper-body before setting `Shoot`, avoiding shots visually stuck in reload.
- [ ] In play mode: reload pistol/rifle/shotgun/sniper, fire immediately after progress completes, verify shoot animation plays.
- [ ] Draw/holster/swap weapon: verify `Draw`, `Holster`, and `WeaponChanged*` transitions for every weapon class.

## Verification Commands

```powershell
# Client compile
cd W:\Unity\Shotter\NightHuntClient
dotnet build "Assembly-CSharp.csproj" -nologo -m:1 /p:BuildInParallel=false --no-restore

# Backend targeted tests
cd W:\Unity\Shotter\NightHuntServer
.\gradlew.bat :test --tests com.nighthunt.party.PartyModeGuardTest --tests com.nighthunt.matchmaking.MatchmakingQueueServiceTest --tests com.nighthunt.realtime.RealtimeTicketServiceTest

# Gateway tests
cd W:\Unity\Shotter\NightHuntServer
.\gradlew.bat :realtime-gateway:test
```
