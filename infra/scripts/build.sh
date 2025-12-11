#!/bin/bash
# Build script for NightHunt Backend

set -e

echo "Building NightHunt Backend..."
./gradlew clean build -x test

echo "Build completed successfully!"

