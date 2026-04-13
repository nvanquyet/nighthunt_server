#!/usr/bin/env bash
# =============================================================================
# setup-vps.sh — NightHunt VPS Bootstrap (chạy 1 lần trên VPS mới)
#
# Cách dùng (SSH vào VPS rồi chạy):
#   curl -fsSL https://raw.githubusercontent.com/nvanquyet/nighthunt_server/main/infra/scripts/setup-vps.sh | bash
# Hoặc copy lên VPS rồi:
#   chmod +x setup-vps.sh && sudo ./setup-vps.sh
# =============================================================================
set -euo pipefail

DEPLOY_DIR="/opt/nighthunt"
GITHUB_OWNER="nvanquyet"
REPO="nighthunt_server"
RAW_URL="https://raw.githubusercontent.com/${GITHUB_OWNER}/${REPO}/main"

echo ""
echo "╔══════════════════════════════════════════════╗"
echo "║   NightHunt VPS Bootstrap                   ║"
echo "╚══════════════════════════════════════════════╝"
echo ""

# ── 1. Cài Docker ────────────────────────────────────────────────────────────
if ! command -v docker &>/dev/null; then
  echo "==> Cài Docker..."
  curl -fsSL https://get.docker.com | sh
  systemctl enable docker
  systemctl start docker
  echo "    [OK] Docker installed"
else
  echo "==> Docker đã có: $(docker --version)"
fi

# ── 2. Cài Docker Compose plugin ─────────────────────────────────────────────
if ! docker compose version &>/dev/null; then
  echo "==> Cài Docker Compose plugin..."
  apt-get update -qq && apt-get install -y docker-compose-plugin
  echo "    [OK] Docker Compose installed"
else
  echo "==> Docker Compose đã có: $(docker compose version)"
fi

# ── 3. Tạo thư mục deploy ────────────────────────────────────────────────────
echo "==> Tạo thư mục $DEPLOY_DIR..."
mkdir -p "$DEPLOY_DIR/certs"
echo "    [OK] $DEPLOY_DIR"

# ── 4. Copy docker-compose.yml từ repo ──────────────────────────────────────
echo "==> Download docker-compose.yml..."
curl -fsSL "${RAW_URL}/docker-compose.yml" -o "${DEPLOY_DIR}/docker-compose.yml"
echo "    [OK] docker-compose.yml"

# ── 5. Tạo .env.production nếu chưa có ──────────────────────────────────────
ENV_FILE="${DEPLOY_DIR}/.env.production"
if [ ! -f "$ENV_FILE" ]; then
  echo "==> Tạo .env.production từ template..."
  curl -fsSL "${RAW_URL}/.env.example" -o "$ENV_FILE"
  # Tự điền ENV_FILE=.env.production
  sed -i 's|ENV_FILE=.env.local|ENV_FILE=.env.production|g' "$ENV_FILE"
  echo ""
  echo "    ┌─────────────────────────────────────────────────────┐"
  echo "    │  ⚠️  QUAN TRỌNG: Chỉnh sửa file trước khi chạy!   │"
  echo "    │  nano ${ENV_FILE}                         │"
  echo "    │                                                     │"
  echo "    │  Các giá trị BẮT BUỘC thay đổi:                   │"
  echo "    │    MYSQL_PASSWORD, MYSQL_ROOT_PASSWORD              │"
  echo "    │    REDIS_PASSWORD                                   │"
  echo "    │    JWT_SECRET  (>= 32 ký tự)                       │"
  echo "    │    DS_ADMIN_SECRET                                  │"
  echo "    │    VPS_PUBLIC_IP  (IP của VPS này)                  │"
  echo "    │    DS_IMAGE_REF=ghcr.io/nvanquyet/nighthunt-ds:latest │"
  echo "    └─────────────────────────────────────────────────────┘"
  echo ""
else
  echo "==> .env.production đã tồn tại — bỏ qua"
fi

# ── 6. Tạo SSH key cho GitHub Actions deploy ─────────────────────────────────
SSH_KEY_FILE="/root/.ssh/nighthunt_deploy"
if [ ! -f "$SSH_KEY_FILE" ]; then
  echo "==> Tạo SSH deploy key..."
  ssh-keygen -t ed25519 -C "github-actions-deploy" -f "$SSH_KEY_FILE" -N ""
  cat "${SSH_KEY_FILE}.pub" >> /root/.ssh/authorized_keys
  chmod 600 /root/.ssh/authorized_keys
  echo ""
  echo "    ┌─────────────────────────────────────────────────────┐"
  echo "    │  Copy PRIVATE KEY này vào GitHub Secret VPS_SSH_KEY │"
  echo "    └─────────────────────────────────────────────────────┘"
  echo ""
  cat "$SSH_KEY_FILE"
  echo ""
  echo "    [OK] SSH key tạo xong"
else
  echo "==> SSH deploy key đã tồn tại"
  echo "    Private key: $SSH_KEY_FILE"
fi

# ── 7. Tạo SSL cert (self-signed cho dev/testing) ───────────────────────────
CERT_DIR="${DEPLOY_DIR}/certs"
if [ ! -f "${CERT_DIR}/keystore.p12" ]; then
  echo "==> Tạo self-signed SSL cert..."
  keytool -genkeypair \
    -alias nighthunt \
    -keyalg RSA \
    -keysize 2048 \
    -validity 3650 \
    -keystore "${CERT_DIR}/keystore.p12" \
    -storetype PKCS12 \
    -storepass nighthunt-dev \
    -dname "CN=nighthunt,OU=nighthunt,O=nighthunt,L=HCM,S=HCM,C=VN" \
    2>/dev/null && echo "    [OK] SSL cert created" || echo "    [SKIP] keytool không có, tự copy cert vào ${CERT_DIR}/"
else
  echo "==> SSL cert đã tồn tại"
fi

# ── Tóm tắt ─────────────────────────────────────────────────────────────────
echo ""
echo "╔══════════════════════════════════════════════╗"
echo "║   Setup hoàn tất!                           ║"
echo "╚══════════════════════════════════════════════╝"
echo ""
echo "Việc cần làm tiếp theo:"
echo ""
echo "  1. Chỉnh sửa .env.production:"
echo "       nano ${ENV_FILE}"
echo ""
echo "  2. Thêm vào GitHub Secrets (repo nighthunt_server):"
echo "       VPS_HOST     = $(curl -s ifconfig.me 2>/dev/null || echo '<IP VPS>')"
echo "       VPS_USER     = root"
echo "       VPS_SSH_KEY  = nội dung file ${SSH_KEY_FILE}"
echo "       GHCR_TOKEN   = GitHub PAT (scope: read:packages)"
echo ""
echo "  3. (Lần đầu) Kéo image và khởi động stack:"
echo "       cd ${DEPLOY_DIR}"
echo "       echo '<GHCR_TOKEN>' | docker login ghcr.io -u nvanquyet --password-stdin"
echo "       ENV_FILE=.env.production docker compose pull"
echo "       ENV_FILE=.env.production docker compose up -d"
echo ""
