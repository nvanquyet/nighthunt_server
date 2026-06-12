package com.nighthunt.dedicatedserver.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    @Value("${DS_IMAGE_REF:ghcr.io/nvanquyet/nighthunt-ds:latest}")
    private String defaultImageRef;

    @Value("${DS_BACKEND_INTERNAL_URL:http://nighthunt-backend:8080}")
    private String backendInternalUrl;

    @Value("${DS_MAX_MEMORY_MB:1024}")
    private int maxMemoryMb;

    /**
     * false = skip docker run (dùng khi test local, không có DS image).
     * DS flow vẫn tạo DB record bình thường.
     * Developer tự gọi POST /api/ds/register để simulate DS boot.
     */
    @Value("${DS_DOCKER_ENABLED:true}")
    private boolean dockerEnabled;

    @Value("${GHCR_TOKEN:}")
    private String ghcrToken;

    @Value("${GHCR_OWNER:}")
    private String ghcrOwner;

    // Đây là imageRef hiện tại - được cập nhật khi CI/CD push image mới
    private volatile String currentImageRef;

    // ──────────────────────────────────────────────────────────────────────────

    public void setCurrentImageRef(String imageRef) {
        this.currentImageRef = imageRef;
        log.info("[DockerManager] Image ref updated → {}", imageRef);
    }

    public String getCurrentImageRef() {
        if (currentImageRef != null) return currentImageRef;
        // Fallback: đọc trực tiếp từ env var runtime để tránh @Value resolution issues với `:` trong default
        String envRef = System.getenv("DS_IMAGE_REF");
        return (envRef != null && !envRef.isBlank()) ? envRef : defaultImageRef;
    }

    public boolean isDockerEnabled() {
        return dockerEnabled;
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
     *     -e MATCH_ID=... \
     *     --memory=512m \
     *     --cpus=0.5 \
     *     --log-opt max-size=10m \
     *     --log-opt max-file=3 \
     *     --rm \
     *     <image>
     *
     * @param matchId matchId của ranked match (có thể null nếu không phải ranked)
     * @return Docker container ID (short)
     */
    public String startContainer(String serverId, int port, String serverSecret, int maxPlayers, String mapId, int expectedPlayers, String matchId) {
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
            "-e", "MAP_ID="           + (mapId   != null ? mapId   : ""),
            "-e", "MATCH_ID="         + (matchId != null ? matchId : ""),
            "--memory",  maxMemoryMb + "m",
            "--cpus",    "0.5",
            "--log-opt", "max-size=10m",
            "--log-opt", "max-file=3",
            // Force IPv6-only sockets to NOT handle IPv4 (IPV6_V6ONLY=1 globally).
            // Without this, LiteNetLib creates an IPv6 dual-stack socket at [::]:port
            // (IPV6_V6ONLY=0 is Linux default), which also claims port for IPv4. When
            // LiteNetLib then tries to bind a separate IPv4 socket at 0.0.0.0:port, it
            // fails with EADDRINUSE. FishNet briefly reports Started=true then sets it
            // to false → HeartbeatLoop exits → LoadGlobalScenes silently ignored →
            // no game-ready → ds_ready never broadcast to clients.
            // net.ipv6.conf.all.disable_ipv6=1 does NOT fix this — it disables IPv6
            // routing but the kernel still allows binding [::]:port on dual-stack sockets.
            // net.ipv6.bindv6only=1 is the correct fix: IPv6 sockets only handle IPv6,
            // so [::]:port and 0.0.0.0:port can coexist on the same port number.
            "--sysctl",  "net.ipv6.bindv6only=1",
            // NOTE: --rm intentionally omitted so containers persist on exit for log inspection.
            // Use `docker ps -a` + `docker logs <id>` to debug crashes, then
            // `docker rm <id>` to clean up manually (or cleanupStaleStartingServers handles it).
            "--network", "nighthunt_game-network",   // Cùng Docker network với backend
            imageRef
        ));

        log.info("[DockerManager] Starting container: {} image={} port={} expectedPlayers={}",
                containerName, imageRef, port, expectedPlayers);
        log.debug("[DockerManager] Command: {}", String.join(" ", cmd));

        // ── Authenticate + pre-pull image so docker run doesn't fail with 'denied' ──
        if (ghcrToken != null && !ghcrToken.isBlank()) {
            try {
                log.info("[DockerManager] Logging in to ghcr.io and pulling {} …", imageRef);
                loginGhcr();
                runDockerCommand("docker", "pull", imageRef);
                log.info("[DockerManager] Image pull succeeded: {}", imageRef);
            } catch (Exception pullEx) {
                log.error("[DockerManager] Pre-pull failed for {}: {} — will attempt docker run anyway",
                        imageRef, pullEx.getMessage());
            }
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode  = process.waitFor();

            // Find a line that is exactly 64 hex characters (container ID)
            String fullContainerId = null;
            for (String line : output.split("\n")) {
                String trimmedLine = line.trim();
                if (trimmedLine.matches("^[0-9a-fA-F]{64}$")) {
                    fullContainerId = trimmedLine;
                    break;
                }
            }

            if (exitCode != 0 && fullContainerId == null) {
                log.error("[DockerManager] docker run failed (exit {}):\n{}", exitCode, output);
                throw new RuntimeException("docker run failed: " + output);
            } else if (exitCode != 0) {
                log.warn("[DockerManager] docker run returned exit code {} but successfully started container: {}", exitCode, fullContainerId);
            }

            // Dùng fullContainerId nếu tìm thấy, ngược lại fallback dùng output
            String targetId = fullContainerId != null ? fullContainerId : output;
            String containerId = targetId.length() > 12 ? targetId.substring(0, 12) : targetId;
            log.info("[DockerManager] Container started: {}", containerId);
            return containerId;

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to start Docker container: " + e.getMessage(), e);
        }
    }

    /**
     * Stop và force-remove container.
     * Dùng 'docker rm -f' để vừa stop vừa remove luôn trong 1 lệnh,
     * đảm bảo không còn lại stopped container chiếm disk/port.
     * (Containers start với --rm tự xóa, nhưng fallback stale cleanup cần rm -f)
     */
    public void stopContainer(String containerId) {
        if (containerId == null || containerId.isBlank()) return;

        log.info("[DockerManager] Force-removing container: {}", containerId);
        runDockerCommand("docker", "rm", "-f", containerId);
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
        runDockerCommand(true, "docker", "pull", imageRef);   // throws on failure
        log.info("[DockerManager] Image pull done: {}", imageRef);
    }

    /**
     * Kiểm tra xem image có sẵn trên local Docker daemon không.
     * Dùng để updateImage() có thể switch ngay với local-only image (e.g. :patched)
     * mà không cần pull từ registry trước.
     */
    public boolean isImageLocal(String imageRef) {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "image", "inspect", "--format", "{{.Id}}", imageRef);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().readAllBytes(); // consume output
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
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

    /**
     * Returns the set of host ports currently bound by any running nighthunt-ds-* container.
     *
     * Uses {@code docker ps --filter name=nighthunt-ds- --format "{{.Ports}}"} and parses
     * entries like {@code 0.0.0.0:7777->7777/udp} to extract the host port number.
     * Falls back to an empty set on any error so callers degrade gracefully.
     */
    public Set<Integer> getOccupiedPorts() {
        Set<Integer> occupied = new HashSet<>();
        if (!dockerEnabled) return occupied;
        try {
            // Use --filter status=running so only live containers are checked for port bindings.
            // Stopped containers don't hold ports but DO hold container names (see cleanupOrphanedContainers).
            ProcessBuilder pb = new ProcessBuilder(
                "docker", "ps",
                "--filter", "name=nighthunt-ds-",
                "--filter", "status=running",
                "--format", "{{.Ports}}"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();

            if (!output.isBlank()) {
                for (String line : output.split("\n")) {
                    // Each line may contain multiple port mappings separated by commas,
                    // e.g. "0.0.0.0:7777->7777/udp, 0.0.0.0:7778->7778/udp"
                    for (String mapping : line.split(",")) {
                        mapping = mapping.trim();
                        // Format: 0.0.0.0:7777->7777/udp  OR  :::7777->7777/udp
                        int colonIdx = mapping.lastIndexOf(':');
                        int arrowIdx = mapping.indexOf("->");
                        if (colonIdx >= 0 && arrowIdx > colonIdx) {
                            String portStr = mapping.substring(colonIdx + 1, arrowIdx);
                            try {
                                occupied.add(Integer.parseInt(portStr.trim()));
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
            log.debug("[DockerManager] getOccupiedPorts → {}", occupied);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[DockerManager] getOccupiedPorts failed: {}", e.getMessage());
        }
        return occupied;
    }

    /**
     * Force-removes any running {@code nighthunt-ds-*} container whose full container ID
     * is NOT present in {@code knownContainerIds}.
     *
     * Called by the scheduled cleanup task in {@link DedicatedServerService} to eliminate
     * zombie containers that hold ports but have no corresponding DB record.
     *
     * @param knownContainerIds short (12-char) or long container IDs that are still valid
     * @return number of containers forcibly removed
     */
    public int cleanupOrphanedContainers(List<String> knownContainerIds) {
        if (!dockerEnabled) return 0;
        int removed = 0;
        try {
            // Use `docker ps -a` (not just running) to also catch STOPPED containers.
            // Stopped containers don't hold ports but DO hold their --name, blocking
            // new `docker run --name nighthunt-ds-XXXXXXXX` with "Conflict" error.
            // This was the actual root cause: previous crashed DS left a stopped container
            // with the same name prefix, causing every subsequent allocation to fail.
            ProcessBuilder pb = new ProcessBuilder(
                "docker", "ps", "-a",
                "--filter", "name=nighthunt-ds-",
                "--format", "{{.ID}}\t{{.Names}}\t{{.Status}}"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();

            if (output.isBlank()) return 0;

            // Normalise known IDs to short form (first 12 chars)
            Set<String> knownShort = new HashSet<>();
            for (String id : knownContainerIds) {
                if (id != null && !id.isBlank()) {
                    knownShort.add(id.length() > 12 ? id.substring(0, 12) : id);
                }
            }

            for (String line : output.split("\n")) {
                String[] parts = line.trim().split("\t");
                if (parts.length < 1) continue;
                String shortId = parts[0].trim();
                String name    = parts.length > 1 ? parts[1].trim() : shortId;
                String status  = parts.length > 2 ? parts[2].trim() : "unknown";
                if (knownShort.contains(shortId)) {
                    log.debug("[DockerManager] orphan-check: container {} ({}) status={} is known — skipping", shortId, name, status);
                    continue;
                }
                log.warn("[DockerManager] Orphaned DS container detected — force-removing: {} ({}) status={}", shortId, name, status);
                try {
                    runDockerCommand("docker", "rm", "-f", shortId);
                    removed++;
                    log.warn("[DockerManager] Orphaned container removed: {} ({}) status={}", shortId, name, status);
                } catch (Exception rmEx) {
                    log.error("[DockerManager] Failed to remove orphaned container {}: {}", shortId, rmEx.getMessage());
                }
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[DockerManager] cleanupOrphanedContainers failed: {}", e.getMessage());
        }
        return removed;
    }

    private void runDockerCommand(String... cmd) {
        runDockerCommand(false, cmd);
    }

    /**
     * Run docker command và trả về stdout output (dùng cho commands cần kết quả).
     */
    public String runDockerCommandWithOutput(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output   = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            return output;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return "error: " + e.getMessage();
        }
    }

    /**
     * Lấy logs của DS container (docker logs --tail N <containerId>).
     */
    public String getContainerLogs(String containerId, int tail) {
        return runDockerCommandWithOutput("docker", "logs", "--tail", String.valueOf(tail), containerId);
    }

    /**
     * @param throwOnError nếu true, ném RuntimeException khi exit code != 0.
     *                     Dùng cho pullImage để pullImage caller biết pull thất bại.
     */
    private void runDockerCommand(boolean throwOnError, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output   = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode    = process.waitFor();

            if (exitCode != 0) {
                log.warn("[DockerManager] Command failed (exit {}):\n{}", exitCode, output);
                if (throwOnError) {
                    throw new RuntimeException("Docker command failed (exit " + exitCode + "): " + output);
                }
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[DockerManager] Command error: {}", e.getMessage());
            if (throwOnError) {
                throw new RuntimeException("Docker command error: " + e.getMessage(), e);
            }
        }
    }
}
