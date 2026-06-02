FROM gradle:8.14-jdk17 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY realtime-gateway/build.gradle realtime-gateway/build.gradle
COPY realtime-gateway/src realtime-gateway/src
RUN gradle :realtime-gateway:installDist --no-daemon

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/realtime-gateway/build/install/realtime-gateway ./
EXPOSE 8090 9091
ENTRYPOINT ["/app/bin/realtime-gateway"]
