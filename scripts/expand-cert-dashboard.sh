#!/bin/bash
# Expand existing Let's Encrypt cert to include dashboard.vawnwuyest.me
# Run AFTER adding DNS A record: dashboard.vawnwuyest.me → 20.2.235.140
set -e

DOMAIN="vawnwuyest.me"
COMPOSE="docker compose --env-file .env.production"

echo "Expanding cert to include dashboard.${DOMAIN}..."
$COMPOSE run --rm --entrypoint "certbot certonly --webroot \
    -w /var/www/certbot \
    -d ${DOMAIN} \
    -d www.${DOMAIN} \
    -d dashboard.${DOMAIN} \
    --expand \
    --non-interactive \
    --agree-tos" certbot

echo "Reloading nginx with expanded cert..."
$COMPOSE exec nginx nginx -s reload

echo "Done! Dashboard available at: https://dashboard.${DOMAIN}"
