#!/bin/bash
# ============================================================================
# init-letsencrypt.sh — First-time Let's Encrypt certificate setup
# ============================================================================
# Run this ONCE on the VPS before starting the full production stack.
# It will:
#   1. Create a temporary self-signed cert so nginx can start
#   2. Start nginx (for ACME HTTP-01 challenge)
#   3. Request the real Let's Encrypt certificate
#   4. Reload nginx with the real cert
#
# Prerequisites:
#   - DNS: vawnwuyest.me A record → 20.2.235.140  (already propagated)
#   - Firewall: port 80 and 443 open
#   - docker + docker compose installed on the VPS
#
# Usage:
#   export LE_EMAIL="your@email.com"   # required
#   export LE_STAGING=1                # use 1 to test without rate-limit
#   bash scripts/init-letsencrypt.sh
# ============================================================================

set -e

DOMAIN="vawnwuyest.me"
EMAIL="${LE_EMAIL:?Please set LE_EMAIL env var, e.g.  export LE_EMAIL=your@email.com}"
STAGING="${LE_STAGING:-0}"

ENV_FILE=".env.production"
COMPOSE="docker compose --env-file $ENV_FILE"

# Colours
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}>>>${NC} $*"; }
warn()  { echo -e "${YELLOW}!!! $*${NC}"; }
error() { echo -e "${RED}ERR $*${NC}"; exit 1; }

echo ""
echo -e "${GREEN}=====================================================${NC}"
echo -e "${GREEN}  NightHunt — Let's Encrypt Setup (${DOMAIN})${NC}"
echo -e "${GREEN}=====================================================${NC}"
echo "  Email  : $EMAIL"
echo "  Staging: $STAGING"
echo ""

# ── 1. Create dummy self-signed cert so nginx can start ─────────────────────
info "Creating temporary self-signed cert for '${DOMAIN}' so nginx can start..."
$COMPOSE run --rm --entrypoint "sh -c \"\
    mkdir -p /etc/letsencrypt/live/${DOMAIN} && \
    openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
        -keyout /etc/letsencrypt/live/${DOMAIN}/privkey.pem \
        -out    /etc/letsencrypt/live/${DOMAIN}/fullchain.pem \
        -subj   '/CN=${DOMAIN}'\"" certbot

# ── 2. Start nginx with the dummy cert (standalone — no backend needed) ─────
info "Starting nginx with temporary cert..."
$COMPOSE up --force-recreate -d nginx certbot
echo "   Waiting 3 s for nginx to be ready..."
sleep 3

# Verify nginx is up
if ! $COMPOSE ps nginx | grep -qE "running|Up"; then
    $COMPOSE logs nginx --tail=20
    error "nginx failed to start. Check logs above."
fi
info "nginx is running."

# ── 3. Delete dummy cert, request real Let's Encrypt cert ───────────────────
info "Removing dummy cert..."
$COMPOSE run --rm --entrypoint "sh -c \"\
    rm -rf /etc/letsencrypt/live/${DOMAIN} && \
    rm -rf /etc/letsencrypt/archive/${DOMAIN} && \
    rm -rf /etc/letsencrypt/renewal/${DOMAIN}.conf\"" certbot

STAGING_FLAG=""
if [ "$STAGING" = "1" ]; then
    STAGING_FLAG="--staging"
    warn "STAGING mode — certificate won't be trusted by browsers (for testing only)"
fi

info "Requesting Let's Encrypt certificate for ${DOMAIN}, www.${DOMAIN} and dashboard.${DOMAIN}..."
$COMPOSE run --rm --entrypoint "certbot certonly --webroot \
    -w /var/www/certbot \
    $STAGING_FLAG \
    -d ${DOMAIN} \
    -d www.${DOMAIN} \
    -d dashboard.${DOMAIN} \
    -d db.${DOMAIN} \
    --email ${EMAIL} \
    --rsa-key-size 4096 \
    --agree-tos \
    --force-renewal \
    --non-interactive" certbot

# ── 4. Reload nginx with real cert ──────────────────────────────────────────
info "Reloading nginx with real certificate..."
$COMPOSE exec nginx nginx -s reload

# ── 5. Start full stack ──────────────────────────────────────────────────────
info "Starting full production stack..."
$COMPOSE up -d

echo ""
echo -e "${GREEN}=====================================================${NC}"
echo -e "${GREEN}  Setup complete!${NC}"
echo -e "${GREEN}=====================================================${NC}"
echo ""
echo "  Backend API : https://${DOMAIN}/api/actuator/health"
echo "  WebSocket   : wss://${DOMAIN}/api/ws/game"
echo "  Dashboard   : http://20.2.235.140:3000  (or add a separate vhost)"
echo ""
echo -e "${YELLOW}Cert renewal is handled automatically by the certbot container.${NC}"
echo -e "${YELLOW}To force-renew now: $COMPOSE run --rm certbot renew --force-renewal${NC}"
echo ""
warn "Firewall tip: you may now close port 8080 externally — all traffic goes via nginx 443/80."
echo ""
