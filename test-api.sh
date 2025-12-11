#!/bin/bash
# Script để test các API endpoints

BASE_URL="http://localhost:8080"
TOKEN=""

echo "=== Testing NightHunt Backend API ==="
echo ""

# Test 1: Health Check
echo "1. Testing Health Check..."
curl -s "$BASE_URL/actuator/health" | jq .
echo ""
echo ""

# Test 2: Register
echo "2. Testing Register..."
REGISTER_RESPONSE=$(curl -s -X POST "$BASE_URL/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser'$(date +%s)'",
    "email": "test'$(date +%s)'@example.com",
    "password": "password123",
    "confirmPassword": "password123"
  }')

echo "$REGISTER_RESPONSE" | jq .

# Extract token
TOKEN=$(echo "$REGISTER_RESPONSE" | jq -r '.data.accessToken // empty')
if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
    echo "Failed to get token from register. Trying login..."
    # Try login instead
    LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/auth/login" \
      -H "Content-Type: application/json" \
      -d '{
        "identifier": "testuser",
        "password": "password123"
      }')
    TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.data.accessToken // empty')
fi

if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
    echo "ERROR: Could not get authentication token!"
    exit 1
fi

echo "Token obtained: ${TOKEN:0:50}..."
echo ""
echo ""

# Test 3: Auto Login
echo "3. Testing Auto Login..."
SESSION_ID=$(echo "$REGISTER_RESPONSE" | jq -r '.data.sessionId // empty')
if [ ! -z "$SESSION_ID" ] && [ "$SESSION_ID" != "null" ]; then
    curl -s -X POST "$BASE_URL/auth/auto-login" \
      -H "Content-Type: application/json" \
      -d "{
        \"accessToken\": \"$TOKEN\",
        \"sessionId\": \"$SESSION_ID\"
      }" | jq .
    echo ""
    echo ""
fi

# Test 4: Get Room (should fail without room)
echo "4. Testing Get Room (should fail)..."
curl -s -X GET "$BASE_URL/rooms/999" \
  -H "Authorization: Bearer $TOKEN" | jq .
echo ""
echo ""

# Test 5: Create Room (will fail without headless server, but should show proper error)
echo "5. Testing Create Room..."
curl -s -X POST "$BASE_URL/rooms/create" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "mode": "2v2",
    "isPublic": true,
    "isLocked": false
  }' | jq .
echo ""
echo ""

echo "=== Testing Complete ==="
echo "Note: Create Room will fail without headless server setup"

