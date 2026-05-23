package lkd.namsic.mcp.devserver;

import jakarta.annotation.PreDestroy;
import lkd.namsic.mcp.config.DevServerProperties;
import lkd.namsic.mcp.config.DevServerProperties.ServerConfig;
import lkd.namsic.mcp.config.DevServerProperties.ServerConfig.DependsOnRef;
import lkd.namsic.mcp.session.ProjectSessionRegistry;
import lkd.namsic.mcp.util.ProcessBuilders;
import lkd.namsic.mcp.util.ProcessBuilders.ProcessResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class DevServerService {

    private static final Duration PROBE_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DOCKER_RUN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOCKER_INSPECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DOCKER_LOGS_TIMEOUT = Duration.ofSeconds(30);
    private static final String WORKDIR_IN_CONTAINER = "/workspace/project";
    private static final String VOLUME_SUBPATH = "project";
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\{env:([A-Za-z_][A-Za-z0-9_]*)}");

    private static final HttpClient PROBE_CLIENT = HttpClient.newBuilder()
        .connectTimeout(PROBE_CONNECT_TIMEOUT)
        .version(HttpClient.Version.HTTP_1_1)
        .build();

    private final DevServerProperties properties;
    private final DockerVolumeService dockerVolumeService;
    private final PortAllocationService portAllocationService;
    private final DockerEnvironment dockerEnvironment;

    private final Map<String, Map<String, DevServerProcess>> processes = new ConcurrentHashMap<>();
    /** sessionIds whose network has already been created this JVM run. */
    private final Set<String> networkReady = ConcurrentHashMap.newKeySet();
    /** volumes already synced once, keyed by "sessionId|volumeName". */
    private final Set<String> preparedVolumes = ConcurrentHashMap.newKeySet();
    /** all volume names used per session, for cleanup. */
    private final Map<String, Set<String>> sessionVolumes = new ConcurrentHashMap<>();
    /** per-volume lock so two threads targeting the same volume serialize, but different volumes don't block each other. */
    private final Map<String, Object> volumePrepLocks = new ConcurrentHashMap<>();

    /** Virtual-thread executor used to kick off each wave's servers in parallel. */
    private final ExecutorService waveExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public DevServerService(
        DevServerProperties properties,
        DockerVolumeService dockerVolumeService,
        PortAllocationService portAllocationService,
        DockerEnvironment dockerEnvironment
    ) {
        this.properties = properties;
        this.dockerVolumeService = dockerVolumeService;
        this.portAllocationService = portAllocationService;
        this.dockerEnvironment = dockerEnvironment;
    }

    /**
     * True iff this server's {@code useHostNetwork=true} request should actually be honored at the
     * Docker layer. On native Linux, host-network works as configured; on Docker Desktop it must be
     * downgraded to bridge+port-mapping (see {@link DockerEnvironment}) so that the host can still
     * reach the bound port. Callers use this both to decide network flags in the docker command and
     * to decide whether the session bridge network needs to exist.
     */
    private boolean useHostNetworkEffective(ServerConfig cfg) {
        return cfg.useHostNetwork() && !this.dockerEnvironment.isDockerDesktop();
    }

    /**
     * Start requested servers (plus transitive dependsOn) using wave-based parallelism:
     * every server in the same indegree-0 wave is started concurrently on virtual threads,
     * and the next wave only begins after all servers in the current wave finish (their
     * readiness probe returns or times out).
     * <p>
     * Servers that share the same local path share one named volume (prepared exactly once
     * per session thanks to per-volume locks), and the per-session Docker network is created
     * exactly once.
     */
    public List<DevServerProcess> startServers(
        String sessionId,
        Map<String, ServerConfig> allConfigs,
        List<String> requestedNames,
        Map<String, String> runtimeEnv
    ) {
        List<List<String>> waves = topoWaves(allConfigs, requestedNames);
        List<DevServerProcess> started = new ArrayList<>();
        for (List<String> wave : waves) {
            started.addAll(this.runWave(sessionId, allConfigs, wave, runtimeEnv));
        }
        return started;
    }

    private List<DevServerProcess> runWave(
        String sessionId,
        Map<String, ServerConfig> allConfigs,
        List<String> wave,
        Map<String, String> runtimeEnv
    ) {
        List<DevServerProcess> results = new ArrayList<>();
        List<Prepared> prepared = new ArrayList<>();

        // Phase 1 (sequential, fast): allocate ports + register placeholders for every new server in
        // this wave BEFORE any docker run starts. This is what makes {server:port} cross-references
        // resolve correctly when wave-mates start in parallel (e.g. waitForReady=false): each
        // launch's resolveEnvironment can see every other wave-mate's host/container port.
        for (String name : wave) {
            if (this.isRegistered(sessionId, name)) {
                DevServerProcess existing = this.getAllProcesses(sessionId).get(name);
                if (existing != null) results.add(existing);
                continue;
            }
            ServerConfig cfg = allConfigs.get(name);
            prepared.add(this.prepareSingle(sessionId, name, cfg));
        }

        // Phase 2 (parallel, slow): per server, ensure volume + docker run + readiness probe.
        List<CompletableFuture<DevServerProcess>> futures = new ArrayList<>();
        for (Prepared p : prepared) {
            futures.add(CompletableFuture.supplyAsync(
                () -> this.launchPrepared(sessionId, p, runtimeEnv),
                this.waveExecutor));
        }

        // Wait for every future (even if some failed) before looking at results,
        // so errors in one server don't orphan others that are still starting.
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .exceptionally(_ -> null)
            .join();

        List<Throwable> errors = new ArrayList<>();
        for (CompletableFuture<DevServerProcess> f : futures) {
            try {
                results.add(f.join());
            } catch (CompletionException ex) {
                errors.add(ex.getCause() != null ? ex.getCause() : ex);
            }
        }

        if (!errors.isEmpty()) {
            RuntimeException primary = errors.getFirst() instanceof RuntimeException re
                ? re
                : new RuntimeException(errors.getFirst());
            for (int i = 1; i < errors.size(); i++) {
                primary.addSuppressed(errors.get(i));
            }
            throw primary;
        }

        return results;
    }

    /** Holds the deterministic facts about a server that we can compute before docker runs. */
    record Prepared(String serverName, ServerConfig cfg, DevServerProcess placeholder) {}

    Prepared prepareSingle(String sessionId, String serverName, ServerConfig cfg) {
        if (cfg.path() == null) {
            throw new IllegalStateException("server '" + serverName + "' has no path configured");
        }
        if (cfg.dockerImage() == null || cfg.dockerImage().isBlank()) {
            throw new IllegalStateException("server '" + serverName + "' has no docker-image configured");
        }

        int hostPort;
        int containerPort;
        if (cfg.useHostNetwork()) {
            // host-network mode requires the app to listen on a fixed (and known) port, regardless
            // of whether we honor --network host or fall back to bridge on Docker Desktop.
            if (cfg.containerPort() == null || cfg.containerPort() <= 0) {
                throw new IllegalStateException(
                    "server '" + serverName + "' uses host network but has no containerPort configured "
                        + "(host network has no port mapping; the app must listen on a fixed port)");
            }
            containerPort = cfg.containerPort();
            if (this.useHostNetworkEffective(cfg)) {
                // Real --network host: the container shares the host's namespace, so the host port
                // and container port are necessarily the same.
                hostPort = containerPort;
                this.portAllocationService.reservePort(sessionId, serverName, hostPort);
            } else {
                // Docker Desktop fallback: we publish the container's fixed port through an
                // explicit -p mapping, but the host side comes from the dynamic range so dev
                // containers don't collide with whatever the user already runs on the host
                // (an IDE, a local DB, another dev server, ...). Apps that talk container→container
                // still use containerPort over the session bridge network.
                hostPort = this.portAllocationService.allocatePort(sessionId, serverName);
            }
        } else {
            hostPort = this.portAllocationService.allocatePort(sessionId, serverName);
            containerPort = (cfg.containerPort() != null && cfg.containerPort() > 0) ? cfg.containerPort() : hostPort;
        }

        String sanitizedSid = ProjectSessionRegistry.sanitize(sessionId);
        String sanitizedName = sanitizeServerName(serverName);
        String containerName = "dev-server-" + sanitizedSid + "-" + sanitizedName;

        String volumeName = resolveVolumeName(sessionId, cfg.path());

        DevServerProcess placeholder = new DevServerProcess(
            containerName, serverName, hostPort, containerPort, volumeName);
        this.processes.computeIfAbsent(sessionId, _ -> new ConcurrentHashMap<>())
            .put(serverName, placeholder);
        return new Prepared(serverName, cfg, placeholder);
    }

    private DevServerProcess launchPrepared(String sessionId, Prepared p, Map<String, String> runtimeEnv) {
        ServerConfig cfg = p.cfg();
        DevServerProcess placeholder = p.placeholder();
        String serverName = p.serverName();
        String containerName = placeholder.containerName();
        int hostPort = placeholder.hostPort();
        int containerPort = placeholder.containerPort();

        if (!this.useHostNetworkEffective(cfg)) {
            // bridge network is needed both for ordinary bridge-mode servers and for host-network
            // servers that we've downgraded to bridge on Docker Desktop.
            this.ensureNetwork(sessionId);
        }
        String volumeName = this.ensureVolume(sessionId, cfg.path(), cfg.dockerImage());

        forceRemoveContainer(containerName);

        List<String> cmd = this.buildDockerCommand(
            sessionId, containerName, volumeName, containerPort, hostPort, cfg, runtimeEnv);

        log.info("Starting dev server (detached): sessionId={}, server={}, hostPort={}, containerPort={}, volume={}",
            sessionId, serverName, hostPort, containerPort, volumeName);

        ProcessResult runResult = ProcessBuilders.runWithTimeout(cmd, DOCKER_RUN_TIMEOUT);
        if (!runResult.success()) {
            this.evictPlaceholder(sessionId, serverName);
            throw new IllegalStateException(
                "docker run failed for '" + serverName + "' (exit=" + runResult.exitCode()
                    + (runResult.timedOut() ? ", timed out after " + DOCKER_RUN_TIMEOUT : "")
                    + "): " + runResult.stdout());
        }

        ReadinessResult outcome = this.waitForReady(containerName, hostPort, cfg.readinessPath());
        if (outcome != ReadinessResult.READY) {
            String logsTail = this.fetchLogsTail(containerName, 50);
            this.evictPlaceholder(sessionId, serverName);
            forceRemoveContainer(containerName);
            String reason = outcome == ReadinessResult.CONTAINER_EXITED
                ? "container exited before becoming HTTP-ready"
                : "did not become HTTP-ready within " + this.properties.startupTimeout();
            throw new IllegalStateException(
                "dev server '" + serverName + "' " + reason
                    + " (readinessPath=" + cfg.readinessPath() + ", hostPort=" + hostPort + ")"
                    + (logsTail.isBlank() ? "" : "\n--- docker logs (tail 50) ---\n" + logsTail));
        }
        return placeholder;
    }

    private void evictPlaceholder(String sessionId, String serverName) {
        Map<String, DevServerProcess> sp = this.processes.get(sessionId);
        if (sp != null) {
            sp.remove(serverName);
            if (sp.isEmpty()) {
                this.processes.remove(sessionId);
            }
        }
        this.portAllocationService.releasePort(sessionId, serverName);
    }

    private enum ReadinessResult { READY, CONTAINER_EXITED, TIMEOUT }

    private String fetchLogsTail(String containerName, int lines) {
        ProcessResult r = ProcessBuilders.runWithTimeout(
            List.of("docker", "logs", "--tail", String.valueOf(lines), containerName),
            Duration.ofSeconds(10));
        return r.stdout() == null ? "" : r.stdout();
    }

    /**
     * Create the session's Docker network exactly once, using double-checked locking so that
     * a burst of concurrent first-wave starts doesn't race into multiple {@code docker network create}s.
     */
    private void ensureNetwork(String sessionId) {
        if (this.networkReady.contains(sessionId)) return;
        synchronized (this.networkReady) {
            if (this.networkReady.contains(sessionId)) return;
            this.dockerVolumeService.createNetwork(this.dockerVolumeService.resolveNetworkName(sessionId));
            this.networkReady.add(sessionId);
        }
    }

    /**
     * Prepare a named volume (create + host→volume sync) exactly once per (session, path) pair.
     * Uses a per-volume lock so two threads with the <em>same</em> volume serialize while threads
     * with <em>different</em> volumes can prepare in parallel.
     */
    private String ensureVolume(String sessionId, Path localPath, String dockerImage) {
        String volumeName = resolveVolumeName(sessionId, localPath);
        String key = sessionId + "|" + volumeName;
        if (this.preparedVolumes.contains(key)) return volumeName;

        Object lock = this.volumePrepLocks.computeIfAbsent(key, _ -> new Object());
        synchronized (lock) {
            if (this.preparedVolumes.contains(key)) return volumeName;
            this.dockerVolumeService.createVolume(volumeName);
            try {
                this.dockerVolumeService.syncHostToVolume(
                    volumeName, localPath.toAbsolutePath().toString(), VOLUME_SUBPATH, dockerImage);
                this.sessionVolumes.computeIfAbsent(sessionId, _ -> ConcurrentHashMap.newKeySet()).add(volumeName);
                this.preparedVolumes.add(key);
            } catch (RuntimeException ex) {
                this.dockerVolumeService.removeVolume(volumeName);
                throw ex;
            }
        }
        return volumeName;
    }

    private static String resolveVolumeName(String sessionId, Path path) {
        String normalized = path.toAbsolutePath().normalize().toString();
        String hash = Integer.toHexString(normalized.hashCode() & 0x7FFFFFFF);
        return "namsic-workspace-" + ProjectSessionRegistry.sanitize(sessionId) + "-" + hash;
    }

    public void stopServer(String sessionId, String serverName) {
        Map<String, DevServerProcess> sessionProcesses = this.processes.get(sessionId);
        if (sessionProcesses == null) return;
        DevServerProcess dsp = sessionProcesses.remove(serverName);
        if (dsp == null) return;
        forceRemoveContainer(dsp.containerName());
        this.portAllocationService.releasePort(sessionId, serverName);
        if (sessionProcesses.isEmpty()) {
            this.processes.remove(sessionId);
        }
        log.info("Dev server stopped: sessionId={}, server={}, container={}",
            sessionId, serverName, dsp.containerName());
    }

    /**
     * Restart the requested servers (or every running server in this session if {@code requestedNames}
     * is empty/null). Each target's container is destroyed and its volume's prepared-flag is evicted
     * so {@link #ensureVolume} re-syncs the host source on the next start. The servers are then
     * recreated via {@link #startServers}, which means transitive dependsOn ordering and waves still
     * apply just like a fresh start.
     *
     * <p>Volumes are deduplicated, so several servers sharing the same workspace path trigger only
     * one re-sync. Currently-running non-target servers that share a volume will see the new files
     * appear under their workspace mid-run — that is the intended behavior (the whole point of
     * restart is to push fresh local edits in).
     */
    public List<DevServerProcess> restartServers(
        String sessionId,
        Map<String, ServerConfig> allConfigs,
        List<String> requestedNames,
        Map<String, String> runtimeEnv
    ) {
        Map<String, DevServerProcess> sessionProcesses = this.processes.get(sessionId);
        if (sessionProcesses == null || sessionProcesses.isEmpty()) {
            throw new IllegalStateException("No dev servers running in this session");
        }

        List<String> targets;
        if (requestedNames == null || requestedNames.isEmpty()) {
            targets = new ArrayList<>(sessionProcesses.keySet());
        } else {
            targets = new ArrayList<>(requestedNames);
            for (String n : targets) {
                if (!sessionProcesses.containsKey(n)) {
                    throw new IllegalStateException(
                        "Server '" + n + "' is not running in this session — start it with dev_server_start first");
                }
            }
        }

        Set<String> volumesToResync = new LinkedHashSet<>();
        for (String name : targets) {
            DevServerProcess existing = sessionProcesses.remove(name);
            if (existing == null) continue;
            forceRemoveContainer(existing.containerName());
            this.portAllocationService.releasePort(sessionId, name);
            volumesToResync.add(existing.volumeName());
            log.info("Restarting dev server: sessionId={}, server={}, container={}",
                sessionId, name, existing.containerName());
        }
        if (sessionProcesses.isEmpty()) {
            this.processes.remove(sessionId);
        }
        for (String vol : volumesToResync) {
            this.preparedVolumes.remove(sessionId + "|" + vol);
        }

        return this.startServers(sessionId, allConfigs, targets, runtimeEnv);
    }

    public void stopAllServers(String sessionId) {
        Map<String, DevServerProcess> sessionProcesses = this.processes.remove(sessionId);
        if (sessionProcesses == null) return;
        for (Map.Entry<String, DevServerProcess> e : sessionProcesses.entrySet()) {
            forceRemoveContainer(e.getValue().containerName());
        }
        this.portAllocationService.releaseAllPorts(sessionId);
        log.info("Stopped all dev servers for sessionId={}, count={}", sessionId, sessionProcesses.size());
    }

    public void closeSession(String sessionId) {
        this.stopAllServers(sessionId);
        Set<String> vols = this.sessionVolumes.remove(sessionId);
        if (vols != null) {
            for (String v : vols) {
                this.dockerVolumeService.removeVolume(v);
                this.volumePrepLocks.remove(sessionId + "|" + v);
            }
        }
        this.preparedVolumes.removeIf(k -> k.startsWith(sessionId + "|"));
        this.networkReady.remove(sessionId);
        this.dockerVolumeService.removeNetwork(this.dockerVolumeService.resolveNetworkName(sessionId));
        log.info("Dev session closed: sessionId={}", sessionId);
    }

    public boolean isRegistered(String sessionId, String serverName) {
        return this.getAllProcesses(sessionId).containsKey(serverName);
    }

    public boolean isRunning(String sessionId, String serverName) {
        return this.isRegistered(sessionId, serverName);
    }

    public boolean isContainerAlive(String containerName) {
        ProcessResult r = ProcessBuilders.runWithTimeout(
            List.of("docker", "inspect", "-f", "{{.State.Running}}", containerName),
            DOCKER_INSPECT_TIMEOUT);
        return r.success() && "true".equals(r.stdout().trim());
    }

    public Map<String, DevServerProcess> getAllProcesses(String sessionId) {
        return this.processes.getOrDefault(sessionId, Map.of());
    }

    public String logs(String sessionId, String serverName, int tailLines) {
        DevServerProcess dsp = this.getAllProcesses(sessionId).get(serverName);
        if (dsp == null) {
            return "Error: no server '" + serverName + "' registered in this session.";
        }
        ProcessResult result = ProcessBuilders.runWithTimeout(
            List.of("docker", "logs", "--tail", String.valueOf(Math.max(0, tailLines)), dsp.containerName()),
            DOCKER_LOGS_TIMEOUT);
        if (result.timedOut()) {
            return "Error: docker logs timed out after " + DOCKER_LOGS_TIMEOUT.toSeconds()
                + "s for container " + dsp.containerName();
        }
        return result.stdout();
    }

    @PreDestroy
    public void destroyAll() {
        int total = this.processes.values().stream().mapToInt(Map::size).sum();
        log.info("Destroying all dev server processes (sessions={}, total processes={})",
            this.processes.size(), total);
        for (String sessionId : List.copyOf(this.processes.keySet())) {
            this.closeSession(sessionId);
        }
        this.waveExecutor.shutdown();
        try {
            if (!this.waveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                this.waveExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            this.waveExecutor.shutdownNow();
        }
    }

    // ===== Internals =====

    /**
     * Split {@code requested} (plus transitive dependsOn closure) into dependency waves:
     * wave 0 = servers with no dependencies in the closure, wave 1 = servers whose deps are all in wave 0, etc.
     * All servers in the same wave can be started in parallel. Throws on cycles / unknown refs.
     */
    static List<List<String>> topoWaves(Map<String, ServerConfig> all, List<String> requested) {
        Set<String> needed = collectNeeded(all, requested);

        Map<String, Integer> indeg = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        for (String n : needed) {
            indeg.putIfAbsent(n, 0);
            ServerConfig c = all.get(n);
            for (DependsOnRef ref : c.dependsOn()) {
                if (!needed.contains(ref.name())) continue;
                if (!ref.waitForReady()) continue;
                outgoing.computeIfAbsent(ref.name(), _ -> new ArrayList<>()).add(n);
                indeg.merge(n, 1, Integer::sum);
            }
        }

        List<List<String>> waves = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        while (visited.size() < needed.size()) {
            List<String> wave = new ArrayList<>();
            for (String n : needed) {
                if (!visited.contains(n) && indeg.get(n) == 0) {
                    wave.add(n);
                }
            }
            if (wave.isEmpty()) {
                throw new IllegalStateException("Circular dependency detected among servers: " + needed);
            }
            for (String n : wave) {
                visited.add(n);
                for (String next : outgoing.getOrDefault(n, List.of())) {
                    indeg.merge(next, -1, Integer::sum);
                }
            }
            waves.add(wave);
        }
        return waves;
    }

    private static Set<String> collectNeeded(Map<String, ServerConfig> all, List<String> requested) {
        Set<String> needed = new LinkedHashSet<>();
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String n : requested) stack.push(n);
        while (!stack.isEmpty()) {
            String n = stack.pop();
            if (!needed.add(n)) continue;
            ServerConfig c = all.get(n);
            if (c == null) {
                throw new IllegalStateException("Unknown server referenced: " + n);
            }
            for (DependsOnRef ref : c.dependsOn()) stack.push(ref.name());
        }
        return needed;
    }

    List<String> buildDockerCommand(
        String sessionId,
        String containerName,
        String volumeName,
        int containerPort,
        int hostPort,
        ServerConfig cfg,
        Map<String, String> runtimeEnv
    ) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("-d");
        cmd.add("--name");
        cmd.add(containerName);
        if (this.useHostNetworkEffective(cfg)) {
            cmd.add("--network");
            cmd.add("host");
        } else {
            String networkName = this.dockerVolumeService.resolveNetworkName(sessionId);
            cmd.add("--network");
            cmd.add(networkName);
            cmd.add("--add-host");
            cmd.add("host.docker.internal:host-gateway");
            cmd.add("-p");
            cmd.add(hostPort + ":" + containerPort);
        }
        cmd.add("-v");
        cmd.add(volumeName + ":/workspace");
        cmd.add("-w");
        cmd.add(WORKDIR_IN_CONTAINER);
        cmd.add("-e");
        cmd.add("PORT=" + containerPort);
        cmd.add("-e");
        cmd.add("NPM_CONFIG_CACHE=/workspace/dev-cache/npm");
        cmd.add("-e");
        cmd.add("PIP_CACHE_DIR=/workspace/dev-cache/pip");
        cmd.add("-e");
        cmd.add("GRADLE_USER_HOME=/workspace/dev-cache/gradle");

        Map<String, String> merged = new LinkedHashMap<>(this.resolveEnvironment(sessionId, cfg.environment()));
        if (runtimeEnv != null) {
            merged.putAll(runtimeEnv);
        }
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            cmd.add("-e");
            cmd.add(entry.getKey() + "=" + entry.getValue());
        }

        cmd.add(cfg.dockerImage());

        cmd.add("bash");
        cmd.add("-c");
        StringBuilder bashCmd = new StringBuilder();
        if (cfg.preCommands() != null && !cfg.preCommands().isEmpty()) {
            bashCmd.append(String.join(" && ", cfg.preCommands())).append(" && ");
        }
        bashCmd.append(cfg.startCommand());
        cmd.add(bashCmd.toString());

        return cmd;
    }

    Map<String, String> resolveEnvironment(String sessionId, Map<String, String> templateEnv) {
        if (templateEnv == null || templateEnv.isEmpty()) {
            return Map.of();
        }
        Map<String, DevServerProcess> sessionProcesses = this.getAllProcesses(sessionId);
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : templateEnv.entrySet()) {
            String value = entry.getValue();
            if (value == null) {
                resolved.put(entry.getKey(), "");
                continue;
            }
            for (Map.Entry<String, DevServerProcess> proc : sessionProcesses.entrySet()) {
                DevServerProcess dsp = proc.getValue();
                value = value.replace("{" + proc.getKey() + ":port}", String.valueOf(dsp.containerPort()));
                value = value.replace("{" + proc.getKey() + ":hostPort}", String.valueOf(dsp.hostPort()));
                value = value.replace("{" + proc.getKey() + "}", dsp.containerName());
            }
            value = resolveEnvPlaceholders(value);
            resolved.put(entry.getKey(), value);
        }
        return resolved;
    }

    private static String resolveEnvPlaceholders(String value) {
        if (value == null || !value.contains("{env:")) return value;
        Matcher m = ENV_PLACEHOLDER.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String env = System.getenv(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(env == null ? "" : env));
        }
        m.appendTail(out);
        return out.toString();
    }

    private ReadinessResult waitForReady(String containerName, int hostPort, String path) {
        Duration timeout = this.properties.startupTimeout();
        String probePath = (path == null || path.isBlank()) ? "/"
            : (path.startsWith("/") ? path : "/" + path);
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (isHttpReady(hostPort, probePath)) {
                return ReadinessResult.READY;
            }
            if (!this.isContainerAlive(containerName)) {
                log.warn("Dev server container exited before becoming HTTP-ready: container={}, port={}, path={}",
                    containerName, hostPort, probePath);
                return ReadinessResult.CONTAINER_EXITED;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return ReadinessResult.TIMEOUT;
            }
        }
        log.warn("Dev server did not become HTTP-ready within {}: port={}, path={}", timeout, hostPort, probePath);
        return ReadinessResult.TIMEOUT;
    }

    private static boolean isHttpReady(int port, String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
            HttpResponse<Void> resp = PROBE_CLIENT.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() > 0;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    static String sanitizeServerName(String serverName) {
        return serverName.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private static void forceRemoveContainer(String containerName) {
        ProcessBuilders.runWithTimeout(
            List.of("docker", "rm", "-f", containerName),
            Duration.ofSeconds(10));
    }
}
