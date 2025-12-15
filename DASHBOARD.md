# NightHunt Dashboard

Dashboard monitoring system để theo dõi trạng thái server, rooms, và users trong thời gian thực.

## Tính năng

### Statistics Overview
- **Total Active Rooms**: Tổng số phòng đang active (WAITING + IN_GAME)
- **Total Online Users**: Số lượng user đang online
- **Total Users in Rooms**: Tổng số user đang trong phòng
- **Total Waiting Rooms**: Số phòng đang chờ
- **Total In-Game Rooms**: Số phòng đang chơi

### Room Details
Mỗi phòng hiển thị:
- Room ID & Code
- Status (WAITING/IN_GAME/CLOSED) với color badges
- Mode (2v2, 4v4, etc.)
- Owner username
- Players list với badges:
  - 👑 Owner badge
  - ✓ Ready badge
- Player count (current/max)
- Public/Private & Locked/Open status
- WebSocket connections count (🟢/🔴)
- Created time

### Filters & Search
- Filter theo Status (All/Waiting/In Game)
- Filter theo Mode (All/2v2/4v4)
- Search theo Room Code hoặc Owner username

### Real-time Updates
- Auto-refresh mỗi 5 giây (có thể tắt)
- Trend indicators (↑/↓) cho các statistics
- Manual refresh button

## Truy cập Dashboard

### Web Interface
Mở trình duyệt và truy cập:
```
http://localhost:8080/dashboard.html
```

### API Endpoint
```bash
curl http://localhost:8080/api/dashboard/stats
```

Response format:
```json
{
  "success": true,
  "data": {
    "totalActiveRooms": 5,
    "totalOnlineUsers": 12,
    "totalUsersInRooms": 10,
    "totalWaitingRooms": 4,
    "totalInGameRooms": 1,
    "activeRooms": [
      {
        "roomId": 1,
        "roomCode": "ABC123",
        "status": "WAITING",
        "mode": "2v2",
        "isPublic": true,
        "isLocked": false,
        "ownerId": 1,
        "ownerUsername": "player1",
        "createdAt": "2025-12-14T05:00:00",
        "playerCount": 3,
        "maxPlayers": 4,
        "players": [
          {
            "userId": 1,
            "username": "player1",
            "team": 1,
            "slot": 0,
            "isReady": true,
            "isOwner": true
          }
        ],
        "activeWebSocketConnections": 3
      }
    ]
  }
}
```

## Architecture

### Backend Components

1. **DashboardService** (`com.nighthunt.dashboard.service.DashboardService`)
   - Tính toán statistics từ database
   - Lấy thông tin chi tiết rooms và players
   - Đếm WebSocket connections

2. **DashboardController** (`com.nighthunt.dashboard.controller.DashboardController`)
   - REST API endpoint: `GET /api/dashboard/stats`
   - Public access (không cần authentication)

3. **DTOs**
   - `DashboardStatsDTO`: Tổng quan statistics
   - `RoomDetailDTO`: Chi tiết từng phòng
   - `PlayerDetailDTO`: Thông tin player

4. **RoomWebSocketHandler** (enhanced)
   - `getActiveConnectionCount(roomId)`: Đếm connections cho 1 phòng
   - `getTotalActiveConnections()`: Tổng connections

### Frontend

- **dashboard.html**: Single-page application
- Vanilla JavaScript (no dependencies)
- Auto-refresh với polling
- Responsive design

## Security

Dashboard endpoints được cấu hình trong `SecurityConfig`:
- `/dashboard.html` - Public access
- `/api/dashboard/**` - Public access

Không cần authentication để truy cập dashboard (có thể thêm sau nếu cần).

## Performance

- Statistics được tính toán real-time mỗi lần request
- Sử dụng `@Transactional(readOnly = true)` để optimize database queries
- WebSocket connection count được lấy từ in-memory map (fast)

## Cải thiện có thể thêm

1. **Real-time WebSocket updates**: Thay polling bằng WebSocket để update real-time
2. **Charts/Graphs**: Thêm biểu đồ thống kê theo thời gian
3. **User details**: Click vào user để xem thông tin chi tiết
4. **Room actions**: Admin actions (kick, ban, etc.)
5. **Historical data**: Lưu statistics history để vẽ charts
6. **Authentication**: Thêm admin authentication cho dashboard
7. **Export data**: Export statistics ra CSV/JSON

## Troubleshooting

### Dashboard không load được
- Kiểm tra backend server đã start: `docker-compose ps`
- Kiểm tra logs: `docker-compose logs backend`
- Kiểm tra SecurityConfig có permit `/dashboard.html` và `/api/dashboard/**`

### Statistics không chính xác
- Kiểm tra database connection
- Kiểm tra WebSocket handler có được inject đúng không
- Kiểm tra logs để xem có lỗi gì

### WebSocket count = 0
- Kiểm tra users có connect WebSocket không
- Kiểm tra RoomWebSocketHandler có được inject vào DashboardService không

