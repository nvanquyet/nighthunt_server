# NightHunt Backend - Setup Guide

Hướng dẫn setup backend server khi chuyển project sang máy khác.

---

## ✅ Đảm bảo đầy đủ chức năng

Khi chuyển project sang máy khác và chạy `docker-compose up`, **TẤT CẢ** các chức năng sẽ tự động được setup:

### 1. Database Migrations (Flyway)
- ✅ **Tự động chạy** khi backend start
- ✅ Tạo tất cả tables cần thiết
- ✅ Insert default configurations (ban configs, rate limit rules)
- ✅ **Không cần** setup database thủ công

**Files:**
- `src/main/resources/db/migration/V1__create_ban_and_rate_limit_tables.sql`
- Flyway config trong `application.yml`

### 2. Dependencies
- ✅ Tất cả dependencies được build vào JAR
- ✅ **Không cần** install Java/Gradle trên máy mới
- ✅ Docker build từ source code

**Files:**
- `build.gradle` - Dependencies list
- `Dockerfile` - Multi-stage build (build + runtime)

### 3. Services
- ✅ MySQL 8.0 - Database
- ✅ Redis 7 - Cache/Session storage
- ✅ Backend (Spring Boot) - API server
- ✅ phpMyAdmin - Database management UI
- ✅ Dashboard - Monitoring UI

**File:**
- `docker-compose.yml` - Tất cả services

### 4. Configuration
- ✅ Environment variables có **default values**
- ✅ **Không cần** file `.env` (optional)
- ✅ Application config trong `application.yml`

---

## 🚀 Setup trên máy mới

### Bước 1: Copy Project
```bash
# Copy toàn bộ thư mục NightHuntServer sang máy mới
```

### Bước 2: Kiểm tra Prerequisites
- ✅ Docker Desktop đang chạy
- ✅ Ports 8080, 3306, 6379, 8081, 3000 chưa bị chiếm

### Bước 3: Chạy Docker
```bash
cd NightHuntServer
docker-compose up -d
```

**Lần đầu tiên sẽ:**
1. Build backend image (từ source code)
2. Tạo MySQL database `nighthunt`
3. Tạo Redis instance
4. **Tự động chạy Flyway migrations** (tạo tables + insert configs)
5. Start tất cả services

### Bước 4: Kiểm tra
```bash
# Check services đang chạy
docker-compose ps

# Check backend logs (đợi ~30s để Spring Boot start)
docker-compose logs backend --tail 50

# Kiểm tra Flyway đã chạy migrations
docker-compose exec mysql mysql -u nighthunt -pnighthunt nighthunt -e "SELECT * FROM flyway_schema_history;"

# Kiểm tra tables đã được tạo
docker-compose exec mysql mysql -u nighthunt -pnighthunt nighthunt -e "SHOW TABLES;"
```

**Expected output:**
- `flyway_schema_history` table có 2 records (baseline + V1)
- Có các tables: `bans`, `ban_config`, `rate_limit_rules`, `rate_limit_tracking`, `rate_limit_token_buckets`, `failed_login_attempts`, `concurrent_login_attempts`, `users`, `sessions`, `rooms`, `room_players`, `swap_requests`, `matches`, `headless_servers`, `headless_server_config`

---

## 📋 Checklist khi chuyển project

### Files cần có:
- ✅ `docker-compose.yml` - Docker services config
- ✅ `Dockerfile` - Backend build config
- ✅ `build.gradle` - Dependencies
- ✅ `src/` - Source code
- ✅ `src/main/resources/application.yml` - Application config
- ✅ `src/main/resources/db/migration/V1__create_ban_and_rate_limit_tables.sql` - Database migration

### Files không cần:
- ❌ `build/` - Sẽ được build lại
- ❌ `.env` - Optional, có default values
- ❌ Pre-built JAR files - Sẽ được build từ source

### Environment Variables (Optional)
Nếu muốn override default values, tạo file `.env`:
```env
JWT_SECRET=your-secret-key-here
SERVER_PORT=8080
DB_PASSWORD=your-password
```

---

## 🔍 Verify Setup

### 1. Check Backend Started
```bash
docker-compose logs backend | grep "Started"
# Expected: "Tomcat started on port 8080"
```

### 2. Check Database Migrations
```bash
docker-compose exec mysql mysql -u nighthunt -pnighthunt nighthunt -e "SELECT * FROM flyway_schema_history;"
# Expected: 2 rows (baseline + V1)
```

### 3. Check Tables Created
```bash
docker-compose exec mysql mysql -u nighthunt -pnighthunt nighthunt -e "SHOW TABLES;"
# Expected: ~15 tables including bans, rate_limit_rules, etc.
```

### 4. Check Default Configs
```bash
# Check ban configs
docker-compose exec mysql mysql -u nighthunt -pnighthunt nighthunt -e "SELECT * FROM ban_config;"
# Expected: 3 rows (failed_login, concurrent_login, device_ban)

# Check rate limit rules
docker-compose exec mysql mysql -u nighthunt -pnighthunt nighthunt -e "SELECT * FROM rate_limit_rules;"
# Expected: Multiple rows (login, register, etc.)
```

### 5. Test API
```bash
# Health check
curl http://localhost:8080/actuator/health

# Register test user
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"test","email":"test@test.com","password":"test123","confirmPassword":"test123"}'
```

---

## 🎯 Tính năng tự động setup

### Database
- ✅ Tự động tạo database `nighthunt`
- ✅ Tự động chạy Flyway migrations
- ✅ Tự động tạo tất cả tables
- ✅ Tự động insert default configurations

### Ban/Block System
- ✅ Tables: `bans`, `ban_config`, `failed_login_attempts`, `concurrent_login_attempts`
- ✅ Default configs: Failed login ban (5 attempts → 15min), Concurrent login ban (2 devices → 30min)

### Rate Limiting
- ✅ Tables: `rate_limit_rules`, `rate_limit_tracking`, `rate_limit_token_buckets`
- ✅ Default rules: Login (10/min), Register (5/min), API (100/min)

### Room Management
- ✅ Tables: `rooms`, `room_players`, `swap_requests`
- ✅ WebSocket support cho real-time updates

### Authentication
- ✅ Tables: `users`, `sessions`
- ✅ JWT + Redis session management

---

## ⚠️ Lưu ý

### 1. Database Data
- **Lần đầu chạy**: Database mới, không có data
- **Nếu muốn giữ data**: Copy MySQL volume `mysql_data` từ máy cũ

### 2. Redis Data
- **Lần đầu chạy**: Redis mới, không có sessions
- **Nếu muốn giữ data**: Copy Redis volume `redis_data` từ máy cũ

### 3. JWT Secret
- Default: `change-this-secret-key-in-production-min-256-bits`
- **Production**: Nên set `JWT_SECRET` trong `.env` hoặc environment variable

### 4. Ports
- Đảm bảo ports 8080, 3306, 6379, 8081, 3000 chưa bị sử dụng

---

## 🔄 Rebuild sau khi thay đổi code

Nếu thay đổi source code, cần rebuild:
```bash
# Stop services
docker-compose stop backend

# Rebuild backend
docker-compose build backend

# Start lại
docker-compose up -d backend
```

**Lưu ý:**
- Database migrations chỉ chạy lần đầu hoặc khi có migration mới
- Data trong database sẽ được giữ lại (trừ khi xóa volumes)

---

## 📝 Tóm tắt

**Khi chuyển project sang máy mới:**
1. ✅ Copy toàn bộ thư mục `NightHuntServer`
2. ✅ Chạy `docker-compose up -d`
3. ✅ Đợi ~30-60s để services start
4. ✅ **TẤT CẢ** chức năng sẽ tự động được setup:
   - Database tables
   - Default configurations
   - Ban/Block system
   - Rate limiting
   - WebSocket support
   - Authentication
   - Room management

**Không cần:**
- ❌ Setup database thủ công
- ❌ Chạy SQL scripts thủ công
- ❌ Install Java/Gradle
- ❌ Config environment variables (có defaults)

---

*Last Updated: 2025-12-14*

