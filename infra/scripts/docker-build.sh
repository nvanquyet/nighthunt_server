#!/bin/bash
# Docker build script

set -e

echo "Building Docker image for NightHunt Backend..."
docker-compose build backend

echo "Docker build completed successfully!"

