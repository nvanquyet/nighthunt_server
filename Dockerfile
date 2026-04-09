# ══════════════════════════════════════════════════════════════
# Dockerfile — NightHunt Backend
#
# LOCAL DEV:   Spring Boot chạy HTTPS:8443 (SSL với mkcert cert)
# PRODUCTION:  Spring Boot chạy HTTP:8080 (Nginx/reverse-proxy handle SSL)
#              → Set SERVER_PORT=8080, SSL_ENABLED=false trong .env.production
# ══════════════════════════════════════════════════════════════

# ── Build stage ────────────────────────────────────────────────
FROM gradle:8.5-jdk17 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN gradle build -x test --no-daemon

# ── Runtime stage ──────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine

# Install: Docker CLI (spawn DS containers) + wget (healthcheck)
RUN apk add --no-cache docker-cli wget

WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

# Expose cả hai port:
#   8443 = HTTPS (local dev với mkcert cert)
#   8080 = HTTP  (production behind Nginx)
EXPOSE 8443 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
