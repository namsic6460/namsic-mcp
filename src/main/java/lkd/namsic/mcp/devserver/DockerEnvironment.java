package lkd.namsic.mcp.devserver;

import lkd.namsic.mcp.util.ProcessBuilders;
import lkd.namsic.mcp.util.ProcessBuilders.ProcessResult;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Detects once per JVM run whether the Docker engine in use is Docker Desktop (Mac/Windows) or
 * native Linux. The distinction matters for {@code --network host}: on native Linux the container
 * shares the OS's network namespace and host-mapped ports are reachable from the host, but on
 * Docker Desktop the container joins the embedded Linux VM's host namespace — ports bound there
 * are NOT reachable from the user's macOS/Windows host (no automatic forwarding), which makes the
 * readiness probe time out forever.
 *
 * <p>{@link DevServerService} consults {@link #isDockerDesktop()} and silently remaps host-network
 * requests to a bridge network + explicit {@code -p containerPort:containerPort} mapping on Desktop,
 * so the same {@code useHostNetwork=true} config works on both engines.
 */
@Getter
@Service
@Slf4j
public class DockerEnvironment {

    private final boolean dockerDesktop;

    public DockerEnvironment() {
        this.dockerDesktop = detectDockerDesktop();
        if (this.dockerDesktop) {
            log.info("Docker engine detected as Docker Desktop: useHostNetwork servers will be remapped "
                + "to bridge network + port mapping (containers reach the host via host.docker.internal).");
        } else {
            log.info("Docker engine detected as native Linux: useHostNetwork servers use --network host as-is.");
        }
    }

    private static boolean detectDockerDesktop() {
        ProcessResult r = ProcessBuilders.runWithTimeout(
            List.of("docker", "info", "--format", "{{.OperatingSystem}}"),
            Duration.ofSeconds(10));
        if (!r.success()) {
            log.warn("docker info probe failed (exit={}, timedOut={}); assuming native Linux engine. "
                + "If you are on Docker Desktop and dev_server_start times out on host-network servers, "
                + "this probe must succeed for the auto-fallback to apply.", r.exitCode(), r.timedOut());
            return false;
        }
        String os = r.stdout() == null ? "" : r.stdout().trim();
        // `docker info --format '{{.OperatingSystem}}'` returns "Docker Desktop" on Mac/Windows
        // and the host distro name (e.g. "Ubuntu 22.04 LTS") on native Linux.
        return os.toLowerCase().contains("docker desktop");
    }
}
