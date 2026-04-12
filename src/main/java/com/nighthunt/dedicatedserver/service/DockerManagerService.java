package com.nighthunt.dedicatedserver.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Quản lý Docker containers cho Dedicated Server.
 *
 * Dùng Docker CLI thông qua ProcessBuilder (không cần thư viện Docker Java SDK).
 * Backend chạy trong container với volume: /var/run/docker.sock:/var/run/docker.sock
 * → Có thể gọi docker command để quản lý containers anh em trên cùng VPS.
 *
 * docker-compose.yml backend service phải có:
 *   volumes:
 *     - /var/run/docker.sock:/var/run/docker.sock
 */
@Service
@Slf4j
public class DockerManagerService {

    @Value("${ds.docker.image-ref:ghcr.io/nvanquyet/nighthunt-ds:latest}")
    private String defaultImageRef;

    @Value("${ds.docker.backend-internal-url:http://backend:8080}")
    private String backendInternalUrl;

    @Value("${ds.docker.max-memory-mb:512}")
    private int maxMemoryMb;

    /**
     * false = skip docker run (dùng khi test local, không có DS image).
     * DS flow vẫn tạo DB record bình thường.
     * Developer tự gọi POST /api/ds/register để simulate DS boot.
     */
    @Value("${ds.docker.enabled:true}")
    private boolean dockerEnabled;

    @Value("${ds.docker.ghcr-token:}")
    private String ghcrToken;

    @Value("${ds.docker.ghcr-owner:}")
    private String ghcrOwner;

    // Đây là imageRef hiện tại - được cập nhật khi CI/CD push image mới
    private volatile String currentImageRef;

    // ──────────────────────────────────────────────────────────────────────────

    public void setCurrentImageRef(String imageRef) {
        this.currentImageRef = imageRef;
        log.info("[DockerManager] Image ref updated → {}", imageRef);
    }

    public String getCurrentImageRef() {
        return currentImageRef != null ? currentImageRef : defaultImageRef;
    }

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Start một Docker container mới cho DS.
     *
     * Tương đương lệnh:
     *   docker run -d \
     *     --name nighthunt-ds-<serverId[:8]> \
     *     -p <port>:<port>/udp \
     *     -e SERVER_ID=... \
     *     -e GAME_PORT=... \
     *     -e BACKEND_URL=... \
     *     -e SERVER_SECRET=... \
     *     -e MAX_PLAYERS=... \
     *     --memory=512m \
     *     --cpus=0.5 \
     *     --log-opt max-size=10m \
     *     --log-opt max-file=3 \
     *     --rm \
     *     <image>
     *
     * @return Docker container ID (short)
     */
    public String startContainer(String serverId, int port, String serverSecret, int maxPlayers, String mapId, int expectedPlayers) {
        String containerName = "nighthunt-ds-" + serverId.substring(0, 8);
        String imageRef      = getCurrentImageRef();

        // ── Fallback mode: skip actual docker run ─────────────────────────────
        if (!dockerEnabled) {
            log.warn("[DockerManager] docker.enabled=false → skipping container start");
            log.warn("[DockerManager] Simulate DS boot: POST /api/ds/register  serverId={} port={} mapId={} expectedPlayers={}", serverId, port, mapId, expectedPlayers);
            return "local-dev-no-container";
        }
        // ─────────────────────────────────────────────────────────────────────

        List<String> cmd = new ArrayList<>(List.of(
            "docker", "run", "-d",
            "--name",     containerName,
            "-p",         port + ":" + port + "/udp",
            "-e", "SERVER_ID="        + serverId,
            "-e", "GAME_PORT="        + port,
            "-e", "BACKEND_URL="      + backendInternalUrl,
            "-e", "SERVER_SECRET="    + serverSecret,
            "-e", "MAX_PLAYERS="      + maxPlayers,
            "-e", "EXPECTED_PLAYERS=" + expectedPlayers,
            "-e", "MAP_ID="           + (mapId != null ? mapId : ""),
            "--memory",  maxMemoryMb + "m",
            "--cpus",    "0.5",
            "--log-opt", "max-size=10m",
            "--log-opt", "max-file=3",
            "--rm",                         // Tự xóa container khi stop
            "--network", "nighthunt_game-network",   // Cùng Docker network với backend
            imageRef
        ));

        log.info("[DockerManager] Starting container: {} on port {} expectedPlayers={}", containerName, port, expectedPlayers);
        log.debug("[DockerManager] Command: {}", String.join(" ", cmd));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode  = process.waitFor();

            if (exitCode != 0) {
                log.error("[DockerManager] docker run failed (exit {}):\n{}", exitCode, output);
                throw new RuntimeException("docker run failed: " + output);
            }

            // Output là container ID (short hash)
            String containerId = output.length() > 12 ? output.substring(0, 12) : output;
            log.info("[DockerManager] Container started: {}", containerId);
            return containerId;

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to start Docker container: " + e.getMessage(), e);
        }
    }

    /**
     * Stop và remove container (--rm tự xóa, nhưng stop vẫn cần thiết)
     */
    public void stopContainer(String containerId) {
        if (containerId == null || containerId.isBlank()) return;

        log.info("[DockerManager] Stopping container: {}", containerId);
        runDockerCommand("docker", "stop", containerId);
    }

    /**
     * Pull image từ registry về VPS cache (nếu chưa có hoặc có version mới).
     * Tự động docker login ghcr.io nếu GHCR_TOKEN được cấu hình.
     */
    public void pullImage(String imageRef) {
        if (ghcrToken != null && !ghcrToken.isBlank()
                && ghcrOwner != null && !ghcrOwner.isBlank()) {
            log.info("[DockerManager] Logging in to ghcr.io as {}", ghcrOwner);
            loginGhcr();
        }
        log.info("[DockerManager] Pulling image: {}", imageRef);
        runDockerCommand("docker", "pull", imageRef);
        log.info("[DockerManager] Image pull done: {}", imageRef);
    }

    /**
     * docker login ghcr.io dùng Personal Access Token (ghcrToken).
     */
    private void loginGhcr() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "docker", "login", "ghcr.io",
                "-u", ghcrOwner.toLowerCase(),
                "--password-stdin"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getOutputStream().write((ghcrToken + "\n").getBytes());
            process.getOutputStream().close();
            String output  = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode   = process.waitFor();
            if (exitCode != 0) {
                log.warn("[DockerManager] ghcr.io login failed (exit {}):\n{}", exitCode, output);
            } else {
                log.info("[DockerManager] ghcr.io login OK");
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[DockerManager] ghcr.io login error: {}", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────

    private void runDockerCommand(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output   = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode    = process.waitFor();

            if (exitCode != 0) {
                log.warn("[DockerManager] Command failed (exit {}):\n{}", exitCode, output);
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[DockerManager] Command error: {}", e.getMessage());
        }
    }
}
