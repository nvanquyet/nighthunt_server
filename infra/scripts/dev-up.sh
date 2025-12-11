#!/bin/bash
# Development environment startup script

set -e

echo "Starting NightHunt development environment..."

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo "Error: Docker is not running. Please start Docker and try again."
    exit 1
fi

# Start services
docker-compose up -d postgres redis

echo "Waiting for services to be ready..."
sleep 5

# Check if services are healthy
echo "Checking service health..."
docker-compose ps

echo "Development environment is ready!"
echo "PostgreSQL: localhost:5432"
echo "Redis: localhost:6379"
echo ""
echo "To start the backend application, run: ./gradlew bootRun"

