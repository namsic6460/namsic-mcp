package lkd.namsic.mcp.devserver;

import lkd.namsic.mcp.session.ProjectSessionRegistry;
import lkd.namsic.mcp.util.ProcessBuilders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
@Slf4j
public class DockerVolumeService {

    private static final int TIMEOUT_SECONDS = 120;
    private static final Pattern SAFE_SUBPATH = Pattern.compile("^[A-Za-z0-9._][A-Za-z0-9._/-]*$");

    private static final String VOLUME_PREFIX = "namsic-workspace-";
    private static final String NETWORK_PREFIX = "namsic-net-";

    /**
     * Patterns excluded from the host→volume copy. Categories:
     * <ul>
     *   <li>Version control &amp; IDE metadata (.git, .idea, etc.) — large / machine-specific</li>
     *   <li>Build outputs that must be regenerated inside the container</li>
     *   <li>Dependency caches (node_modules, .venv, .gradle, .m2/repository, ...) — often large
     *       and frequently contain files exclusively locked by running daemons / IDEs on the host,
     *       which would otherwise crash the {@code tar} stream mid-copy</li>
     *   <li>Runtime artifacts (logs, pids, coverage) that leak host state into the container</li>
     *   <li>OS / editor debris (.DS_Store, *.swp, ...)</li>
     * </ul>
     * Patterns are applied without leading {@code ./}, so sub-directories in monorepos also match
     * (e.g. {@code packages/foo/node_modules} is excluded).
     */
    private static final List<String> COPY_EXCLUDES = List.of(
        // Version control
        ".git", ".hg", ".svn",
        // IDE / editor metadata & swap files
        ".idea", ".vscode", ".fleet", ".vs",
        "*.swp", "*.swo", "*~",
        // OS debris
        ".DS_Store", "Thumbs.db", "desktop.ini",
        // JVM build tools
        ".gradle", "build", "out", "target", ".mvn/wrapper/dists",
        // JS / TS ecosystem
        "node_modules", "dist",
        ".next", ".nuxt", ".turbo", ".cache", ".parcel-cache", ".svelte-kit", ".astro", ".output",
        // Python
        ".venv", "venv", "__pycache__", "*.pyc", "*.pyo",
        ".pytest_cache", ".mypy_cache", ".ruff_cache", ".tox",
        // Ruby
        "vendor/bundle",
        // Test / coverage
        "coverage", ".nyc_output", "htmlcov",
        // Runtime artifacts
        "logs", "*.log", "*.pid", "*.lock~"
    );

    public void createVolume(String volumeName) {
        int exitCode = this.runDockerCommand("docker", "volume", "create", volumeName);
        if (exitCode != 0) {
            log.warn("docker volume create failed: volume={}", volumeName);
        } else {
            log.info("Docker volume created: {}", volumeName);
        }
    }

    public void removeVolume(String volumeName) {
        int exitCode = this.runDockerCommand("docker", "volume", "rm", "-f", volumeName);
        if (exitCode >= 0) {
            log.info("Docker volume removed: {}", volumeName);
        }
    }

    /**
     * Copy files from a host directory into a named volume's sub-path using a helper container.
     * hostPath is mounted read-only; /vol is writable. `cp -a` preserves permissions.
     */
    public void syncHostToVolume(String volumeName, String hostPath, String containerSubPath, String dockerImage) {
        assertSafeSubPath(containerSubPath);
        String normalizedHostPath = normalizeHostPath(hostPath);

        String destPath = "/vol/" + containerSubPath;
        StringBuilder excludes = new StringBuilder();
        for (String e : COPY_EXCLUDES) {
            excludes.append(" --exclude='").append(e).append("'");
        }
        // Stream with tar instead of cp -a so we can skip host build/cache dirs that
        // may hold OS-locked files (e.g. Gradle daemon's *.lock). tar streams the
        // selected tree to stdout, and the second tar extracts it in the volume.
        String command = "mkdir -p " + destPath
            + " && (cd /src && tar cf -" + excludes + " .)"
            + " | (cd " + destPath + " && tar xf -)";

        try {
            Process process = new ProcessBuilder(
                "docker", "run", "--rm",
                "--entrypoint", "sh",
                "-v", volumeName + ":/vol",
                "-v", normalizedHostPath + ":/src:ro",
                dockerImage,
                "-c", command
            ).redirectErrorStream(true).start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException(
                    "syncHostToVolume timed out after " + TIMEOUT_SECONDS + "s: volume=" + volumeName + ", hostPath=" + hostPath);
            } else if (process.exitValue() != 0) {
                throw new IllegalStateException("syncHostToVolume failed (exit=" + process.exitValue()
                    + "): volume=" + volumeName + ", hostPath=" + hostPath + ", output=" + output);
            }
            log.info("syncHostToVolume complete: volume={}, subPath={}, hostPath={}",
                volumeName, containerSubPath, normalizedHostPath);
        } catch (IOException ex) {
            throw new IllegalStateException("syncHostToVolume IO error: volume=" + volumeName, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("syncHostToVolume interrupted", ex);
        }
    }

    public void createNetwork(String networkName) {
        try {
            int exitCode = this.runDockerCommand("docker", "network", "create", networkName);
            if (exitCode == 0) {
                log.info("Docker network created: {}", networkName);
            }
        } catch (Exception ex) {
            log.debug("docker network create {} failed (may already exist): {}", networkName, ex.getMessage());
        }
    }

    public void removeNetwork(String networkName) {
        try {
            this.runDockerCommand("docker", "network", "rm", networkName);
        } catch (Exception ex) {
            log.debug("docker network rm {} failed: {}", networkName, ex.getMessage());
        }
    }

    public String resolveVolumeName(String sessionId) {
        return VOLUME_PREFIX + ProjectSessionRegistry.sanitize(sessionId);
    }

    public String resolveNetworkName(String sessionId) {
        return NETWORK_PREFIX + ProjectSessionRegistry.sanitize(sessionId);
    }

    static void assertSafeSubPath(String containerSubPath) {
        if (containerSubPath == null || containerSubPath.isBlank()) {
            throw new IllegalArgumentException("containerSubPath is required");
        }
        if (containerSubPath.contains("..") || containerSubPath.startsWith("/")) {
            throw new IllegalArgumentException("containerSubPath must be a relative safe path: " + containerSubPath);
        }
        if (!SAFE_SUBPATH.matcher(containerSubPath).matches()) {
            throw new IllegalArgumentException(
                "containerSubPath contains unsafe characters: " + containerSubPath);
        }
    }

    static String normalizeHostPath(String hostPath) {
        if (hostPath == null || hostPath.isBlank()) {
            throw new IllegalArgumentException("hostPath is required");
        }
        Path normalized = Paths.get(hostPath).toAbsolutePath().normalize();
        return normalized.toString();
    }

    private int runDockerCommand(String... args) {
        ProcessBuilders.ProcessResult result = ProcessBuilders.runWithTimeout(List.of(args), Duration.ofSeconds(10));
        return result.exitCode();
    }
}
