#!/bin/bash
# Generate self-signed SSL certificate for local development

set -e

CERT_DIR="src/main/resources/certs"
KEYSTORE_FILE="$CERT_DIR/keystore.p12"
KEYSTORE_PASSWORD="changeit"
KEY_ALIAS="nighthunt"

echo "Generating self-signed SSL certificate for local development..."

# Create certs directory if it doesn't exist
mkdir -p "$CERT_DIR"

# Generate keystore with self-signed certificate
keytool -genkeypair \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -storetype PKCS12 \
    -keystore "$KEYSTORE_FILE" \
    -validity 365 \
    -storepass "$KEYSTORE_PASSWORD" \
    -keypass "$KEYSTORE_PASSWORD" \
    -dname "CN=localhost, OU=Development, O=NightHunt, L=City, ST=State, C=US"

echo "SSL certificate generated successfully!"
echo "Keystore location: $KEYSTORE_FILE"
echo "Password: $KEYSTORE_PASSWORD"
echo ""
echo "To use this certificate, set these environment variables:"
echo "  SSL_ENABLED=true"
echo "  SSL_KEYSTORE=$KEYSTORE_FILE"
echo "  SSL_KEYSTORE_PASSWORD=$KEYSTORE_PASSWORD"
echo "  SSL_KEY_ALIAS=$KEY_ALIAS"

