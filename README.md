# NightHunt Backend Server

Spring Boot backend server for NightHunt multiplayer game.

## 🚀 Quick Start

### Prerequisites
- Docker & Docker Compose
- Java 17+ (for local development)
- MySQL 8.0+ (handled by Docker)
- Redis 7+ (handled by Docker)

### Start All Services (Docker)
```bash
cd NightHuntServer
docker-compose up -d
```

### Stop Services
```bash
docker-compose down
```

### Rebuild and Restart
```bash
docker-compose down
docker-compose build backend
docker-compose up -d
```

### View Logs
```bash
docker-compose logs -f backend
```

## 🌐 Access Services

- **Backend API**: `http://localhost:8080`
- **phpMyAdmin**: `http://localhost:8081` (user: `nighthunt`, password: `nighthunt`)
- **Dashboard**: `http://localhost:3000`
- **MySQL**: `localhost:3306` (user: `nighthunt`, password: `nighthunt`, database: `nighthunt`)
- **Redis**: `localhost:6379`

## 📋 Features

### Authentication
- ✅ User Registration
- ✅ Login/Logout
- ✅ Auto-login (Remember me)
- ✅ Multi-device login prevention
- ✅ Session management (JWT + Redis)
- ✅ Force logout on new device login
- ✅ Session expiration handling

### Room Management
- ✅ Create Room (with mode, password, visibility)
- ✅ Join Room (by code with password)
- ✅ Quick Play (find random room or create new)
- ✅ Leave Room
- ✅ Reconnect to Room
- ✅ Room Settings (Owner: mode, password, public/private, lock)
- ✅ Auto Room Cleanup (empty rooms after 5min/30min)

### Lobby System
- ✅ Player Slots (Team 1 & Team 2)
- ✅ Change Team/Slot
- ✅ Set Ready/Unready
- ✅ Swap Request (Request, Accept, Reject)
- ✅ Real-time Updates (Polling support)
- ✅ Owner Transfer (Auto + Manual)
- ✅ Kick Player (Owner only)
- ✅ Disband Room (Owner only)
- ✅ Start Game (Owner only, requires all ready + enough players)

### Background Services
- ✅ Room Owner Transfer Service (Auto transfer when owner disconnects > 30s)
- ✅ Room Cleanup Service (Cleanup empty rooms every 5min/30min)

## 🏗️ Architecture

### Tech Stack
- **Framework**: Spring Boot 3.2.5
- **Database**: MySQL 8.0
- **Cache**: Redis 7
- **Security**: Spring Security + JWT
- **Build**: Gradle
- **Container**: Docker

### Project Structure
```
NightHuntServer/
├── src/main/java/com/nighthunt/
│   ├── auth/          # Authentication & Authorization
│   ├── room/          # Room & Lobby Management
│   ├── match/         # Match Management
│   ├── session/       # Session Management
│   ├── user/          # User Management
│   ├── common/        # Common utilities, exceptions, constants
│   └── security/      # Security configuration
├── docker-compose.yml
├── Dockerfile
└── build.gradle
```

## 🔧 Configuration

### Environment Variables
```env
# Database
DB_URL=jdbc:mysql://mysql:3306/nighthunt
DB_USERNAME=nighthunt
DB_PASSWORD=nighthunt

# Redis
REDIS_HOST=redis
REDIS_PORT=6379

# JWT
JWT_SECRET=change-this-secret-key-in-production-min-256-bits

# Server
SERVER_PORT=8080
API_BASE_URL=http://localhost:8080
```

### Application Properties
See `src/main/resources/application.yml` for detailed configuration.

## 📡 API Endpoints

### Authentication
- `POST /auth/register` - Register new user
- `POST /auth/login` - Login
- `POST /auth/auto-login` - Auto-login with token
- `POST /auth/logout` - Logout
- `GET /auth/check-session` - Check session status

### Rooms
- `POST /rooms/create` - Create room
- `POST /rooms/join-by-code` - Join room by code
- `POST /rooms/quick-play` - Quick play (find or create)
- `POST /rooms/reconnect` - Reconnect to room
- `GET /rooms/{roomId}` - Get room info
- `POST /rooms/{roomId}/ready` - Set ready/unready
- `POST /rooms/{roomId}/change-team` - Change team/slot
- `POST /rooms/{roomId}/leave` - Leave room
- `POST /rooms/{roomId}/start` - Start game (Owner only)
- `POST /rooms/{roomId}/disband` - Disband room (Owner only)
- `POST /rooms/{roomId}/kick/{playerId}` - Kick player (Owner only)
- `POST /rooms/{roomId}/update-settings` - Update settings (Owner only)
- `POST /rooms/{roomId}/transfer-owner` - Transfer ownership (Owner only)

### Swap Requests
- `POST /rooms/{roomId}/swap-request` - Request swap
- `POST /rooms/{roomId}/swap-accept/{requestId}` - Accept swap
- `POST /rooms/{roomId}/swap-reject/{requestId}` - Reject swap
- `GET /rooms/{roomId}/swap-requests` - Get pending requests

## 🧪 Testing

### Health Check
```bash
curl http://localhost:8080/health
```

### Register User
```bash
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "email": "test@example.com",
    "password": "password123",
    "confirmPassword": "password123"
  }'
```

### Login
```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "password123"
  }'
```

## 📊 Monitoring

- **Dashboard**: `http://localhost:3000`
- **Logs**: `docker-compose logs -f backend`
- **Database**: phpMyAdmin at `http://localhost:8081`

## 🔒 Security

- JWT token-based authentication
- Session validation via Redis
- Multi-device login prevention
- Force logout on new device
- Password hashing (BCrypt)
- SQL injection prevention (JPA)
- XSS protection

## 🐛 Troubleshooting

### Port Already in Use
```bash
# Find process using port 8080
netstat -ano | findstr :8080
# Kill process (replace PID)
taskkill /PID <PID> /F
```

### Database Connection Error
- Check MySQL container is running: `docker-compose ps`
- Check MySQL logs: `docker-compose logs mysql`
- Verify credentials in `docker-compose.yml`

### Redis Connection Error
- Check Redis container is running: `docker-compose ps`
- Check Redis logs: `docker-compose logs redis`

## 📝 License

Proprietary - All rights reserved

---

*Last Updated: 2025-12-12*
