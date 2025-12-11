#!/bin/bash
# Docker compose up script

set -e

echo "Starting all services with Docker Compose..."
docker-compose up -d

echo "Waiting for services to be ready..."
sleep 10

echo "Services are starting..."
echo "Backend API: http://localhost:8080"
echo "PostgreSQL: localhost:5432"
echo "Redis: localhost:6379"
echo ""
echo "To view logs: docker-compose logs -f"
echo "To stop services: docker-compose down"

