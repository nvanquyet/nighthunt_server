#!/bin/bash
# Docker compose up with HTTPS script

set -e

echo "Starting NightHunt services with HTTPS..."

# Check if SSL certificate exists
if [ ! -f "src/main/resources/certs/keystore.p12" ]; then
    echo "SSL certificate not found. Generating..."
    ./infra/scripts/generate-ssl-cert.sh
fi

# Set SSL environment variables
export SSL_ENABLED=true
export SSL_KEYSTORE_PASSWORD=${SSL_KEYSTORE_PASSWORD:-changeit}
export SSL_KEY_ALIAS=${SSL_KEY_ALIAS:-nighthunt}
export SERVER_PORT=8443
export USE_HTTPS=true
export API_BASE_URL=https://localhost:8443

# Start services
docker-compose -f docker-compose.yml -f docker-compose.https.yml up -d

echo "Waiting for services to be ready..."
sleep 10

echo "Services are starting with HTTPS..."
echo "Backend API: https://localhost:8443"
echo "PostgreSQL: localhost:5432"
echo "Redis: localhost:6379"
echo ""
echo "Note: You may need to accept the self-signed certificate in your browser/client"
echo ""
echo "To view logs: docker-compose logs -f"
echo "To stop services: docker-compose down"

