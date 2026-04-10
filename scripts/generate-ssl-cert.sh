#!/bin/bash
# ============================================================================
# SSL Certificate Generator for NightHunt Backend
# ============================================================================
# This script automatically generates a self-signed SSL certificate
# for development and testing purposes.
#
# For production, use Let's Encrypt instead.
# ============================================================================

set -e

# Colors for output
RED='\033[0:31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}=====================================${NC}"
echo -e "${GREEN}NightHunt SSL Certificate Generator${NC}"
echo -e "${GREEN}=====================================${NC}"
echo ""

# Configuration
CERTS_DIR="src/main/resources/certs"
KEYSTORE_FILE="$CERTS_DIR/keystore.p12"
KEYSTORE_PASSWORD="nighthunt-dev"   # must match SSL_KEYSTORE_PASSWORD in .env.production
KEY_ALIAS="nighthunt"
VALIDITY_DAYS=365
# Public IP included in SAN so TLS hostname validation works on direct-IP connections
VPS_IP="${VPS_PUBLIC_IP:-20.2.235.140}"

# Check if keytool is available
if ! command -v keytool &> /dev/null; then
    echo -e "${RED}Error: keytool not found${NC}"
    echo "keytool is part of Java JDK. Please install JDK first."
    exit 1
fi

# Create certs directory
echo -e "${YELLOW}Creating certificates directory...${NC}"
mkdir -p "$CERTS_DIR"

# Check if keystore already exists
if [ -f "$KEYSTORE_FILE" ]; then
    echo -e "${YELLOW}Keystore already exists at: $KEYSTORE_FILE${NC}"
    read -p "Do you want to regenerate it? (y/N): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo -e "${GREEN}Using existing keystore.${NC}"
        exit 0
    fi
    echo -e "${YELLOW}Removing existing keystore...${NC}"
    rm -f "$KEYSTORE_FILE"
fi

# Generate self-signed certificate
echo -e "${YELLOW}Generating self-signed SSL certificate...${NC}"
keytool -genkeypair \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -storetype PKCS12 \
    -keystore "$KEYSTORE_FILE" \
    -validity $VALIDITY_DAYS \
    -storepass "$KEYSTORE_PASSWORD" \
    -keypass "$KEYSTORE_PASSWORD" \
    -dname "CN=localhost, OU=NightHunt Development, O=NightHunt, L=Ho Chi Minh, ST=Vietnam, C=VN" \
    -ext "SAN=dns:localhost,ip:127.0.0.1,ip:::1,ip:${VPS_IP}"

echo ""
echo -e "${GREEN}✅ SSL Certificate generated successfully!${NC}"
echo ""
echo -e "${GREEN}Certificate Details:${NC}"
echo "  Location: $KEYSTORE_FILE"
echo "  Alias: $KEY_ALIAS"
echo "  Password: $KEYSTORE_PASSWORD"
echo "  Validity: $VALIDITY_DAYS days"
echo ""
echo -e "${YELLOW}Note: This is a self-signed certificate for development only.${NC}"
echo -e "${YELLOW}For production, use Let's Encrypt or a commercial CA.${NC}"
echo ""

# List certificate details
echo -e "${GREEN}Certificate Information:${NC}"
keytool -list -v -keystore "$KEYSTORE_FILE" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" | head -20

echo ""
echo -e "${GREEN}✅ Setup complete! You can now run:${NC}"
echo "  docker compose up -d --build"
echo ""
