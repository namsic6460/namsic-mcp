package lkd.namsic.mcp.devserver;

import lkd.namsic.mcp.config.DevServerProperties;
import lkd.namsic.mcp.config.DevServerProperties.ServerConfig;
import lkd.namsic.mcp.config.DevServerProperties.ServerConfig.DependsOnRef;
import lkd.namsic.mcp.session.ProjectSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DevServerMcpTools {

    private static final String SESSION_PARAM_DESC =
        "Session ID returned by project_init. Required; obtain it by calling project_init first.";

    private final ProjectSessionRegistry registry;
    private final DevServerProperties devServerProperties;
    private final DevServerService devServerService;

    // ========== dev_server_list ==========

    @Tool(name = "dev_server_list", description = "List every dev server registered under app.dev-servers in config/Vault. "
        + "Each server has its own local path, docker image, start command, and dependsOn. "
        + "Also shows which servers are currently running in this session.")
    public String devServerList(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId
    ) {
        try {
            this.registry.require(sessionId);
        } catch (final IllegalStateException ex) {
            return "Error: " + ex.getMessage();
        }
        final Map<String, ServerConfig> all = this.devServerProperties.devServers();
        if (all.isEmpty()) {
            return "No servers configured under app.dev-servers.";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("Registered dev servers (").append(all.size()).append("):\n");
        for (final Map.Entry<String, ServerConfig> e : all.entrySet()) {
            final ServerConfig cfg = e.getValue();
            final boolean running = this.devServerService.isRunning(sessionId, e.getKey());
            sb.append("  - ").append(e.getKey())
                .append(" (path=").append(cfg.path())
                .append(", image=").append(cfg.dockerImage())
                .append(", containerPort=").append(cfg.containerPort())
                .append(", startCommand=\"").append(cfg.startCommand()).append('"');
            if (cfg.useHostNetwork()) {
                sb.append(", useHostNetwork=true");
            }
            final List<DependsOnRef> deps = cfg.dependsOn();
            if (!deps.isEmpty()) {
                sb.append(", dependsOn=[");
                for (int i = 0; i < deps.size(); i++) {
                    if (i > 0) sb.append(", ");
                    final DependsOnRef ref = deps.get(i);
                    sb.append(ref.name());
                    if (!ref.waitForReady()) sb.append("(no-wait)");
                }
                sb.append(']');
            }
            if (!cfg.preCommands().isEmpty()) {
                sb.append(", preCommands=").append(cfg.preCommands());
            }
            sb.append(", readinessPath=").append(cfg.readinessPath());
            if (running) {
                sb.append(" [RUNNING]");
            }
            sb.append(")\n");
        }
        return sb.toString().trim();
    }

    // ========== dev_server_start ==========

    @Tool(name = "dev_server_start", description = "Start one or more dev servers (registered under app.dev-servers) and "
        + "BLOCK until each one is HTTP-ready. For each unique local path, the folder is copied into a Docker named volume "
        + "once per session (servers sharing the same path share the volume); a per-session Docker network is created so "
        + "servers can reach each other by container name. Transitive dependsOn servers are started automatically in "
        + "topological order; each server is HTTP-probed against its readinessPath and the call only returns once the probe "
        + "succeeds. If a server's container exits or the probe times out (app.startup-timeout), the container is removed, "
        + "the port is released, and an Error is returned with a tail of docker logs. Already-running servers are skipped. "
        + "If a server has useHostNetwork=true, on native Linux Docker it joins the host network namespace (host port == containerPort, "
        + "no port mapping, 'localhost' inside the container reaches host services, and other dev servers must use host.docker.internal "
        + "or 127.0.0.1 instead of the container name). On Docker Desktop (Mac/Windows) the host network would be unreachable from your "
        + "OS host, so it is silently remapped to bridge + '-p <allocatedHostPort>:containerPort' — the container still listens on "
        + "containerPort (so peers can reach it by container name on the session bridge network), but the host-side port is allocated "
        + "from the dynamic 10000+ range to avoid colliding with services already running on your machine. Always use the host URL the "
        + "tool reports rather than assuming containerPort, since on Desktop they differ.")
    public String devServerStart(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "Comma-separated server names to start (e.g. 'backend,frontend'). Required; "
            + "call dev_server_list first to see available names.") final String serverNames,
        @ToolParam(description = "Extra environment variables as semicolon-separated key=value pairs "
            + "(e.g. 'FOO=bar;BAZ=qux'). Overrides server-level environment for the matching keys.") final String environmentVars
    ) {
        try {
            this.registry.require(sessionId);
        } catch (final IllegalStateException ex) {
            return "Error: " + ex.getMessage();
        }

        final List<String> requestedNames = parseCommaSeparated(serverNames);
        if (requestedNames.isEmpty()) {
            return "Error: serverNames is required. Call dev_server_list to see available servers.";
        }

        final Map<String, ServerConfig> all = this.devServerProperties.devServers();
        for (String name : requestedNames) {
            if (!all.containsKey(name)) {
                return "Error: unknown server '" + name + "'. Available: " + all.keySet();
            }
        }

        final Map<String, String> runtimeEnv = parseEnvironmentVars(environmentVars);
        final List<DevServerProcess> started;
        try {
            started = this.devServerService.startServers(sessionId, all, requestedNames, runtimeEnv);
        } catch (final RuntimeException ex) {
            log.warn("startServers failed", ex);
            return "Error starting dev servers: " + ex.getMessage();
        }

        final StringBuilder sb = new StringBuilder();
        sb.append("Started dev servers (all HTTP-ready):\n");
        for (final DevServerProcess dsp : started) {
            sb.append("  [").append(dsp.serverName()).append("] ")
                .append("http://localhost:").append(dsp.hostPort())
                .append(" (containerPort=").append(dsp.containerPort())
                .append(", container=").append(dsp.containerName()).append(")\n");
        }
        return sb.toString().trim();
    }

    // ========== dev_server_restart ==========

    @Tool(name = "dev_server_restart", description = "Restart one or more running dev servers, picking up local file edits. "
        + "For each target the container is destroyed and recreated, and the host project directory is re-synced into the "
        + "shared workspace volume before the new container boots — so this is the right tool whenever you've edited code "
        + "locally and want it inside Docker (covers both hot-reload-friendly stacks and ones that need a fresh process). "
        + "Servers sharing the same local path share one volume, which is re-synced exactly once per restart call. "
        + "BLOCKS until each restarted server is HTTP-ready (same readiness probe as dev_server_start). "
        + "Errors if a requested server isn't currently running.")
    public String devServerRestart(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "Comma-separated server names to restart (e.g. 'backend,frontend'). "
            + "Null/blank = restart every server currently running in this session.") final String serverNames,
        @ToolParam(description = "Extra environment variables as semicolon-separated key=value pairs "
            + "(e.g. 'FOO=bar;BAZ=qux'). Overrides server-level environment for the matching keys.") final String environmentVars
    ) {
        try {
            this.registry.require(sessionId);
        } catch (final IllegalStateException ex) {
            return "Error: " + ex.getMessage();
        }

        final List<String> requestedNames = parseCommaSeparated(serverNames);
        final Map<String, ServerConfig> all = this.devServerProperties.devServers();
        for (String name : requestedNames) {
            if (!all.containsKey(name)) {
                return "Error: unknown server '" + name + "'. Available: " + all.keySet();
            }
        }

        final Map<String, String> runtimeEnv = parseEnvironmentVars(environmentVars);
        final List<DevServerProcess> restarted;
        try {
            restarted = this.devServerService.restartServers(sessionId, all, requestedNames, runtimeEnv);
        } catch (final RuntimeException ex) {
            log.warn("restartServers failed", ex);
            return "Error restarting dev servers: " + ex.getMessage();
        }

        final StringBuilder sb = new StringBuilder();
        sb.append("Restarted dev servers (all HTTP-ready):\n");
        for (final DevServerProcess dsp : restarted) {
            sb.append("  [").append(dsp.serverName()).append("] ")
                .append("http://localhost:").append(dsp.hostPort())
                .append(" (containerPort=").append(dsp.containerPort())
                .append(", container=").append(dsp.containerName()).append(")\n");
        }
        return sb.toString().trim();
    }

    // ========== dev_server_stop ==========

    @Tool(name = "dev_server_stop", description = "Stop one or all running dev servers in this session. "
        + "Containers are removed and ports released; volumes and network are kept (use dev_server_close_session to clean those too).")
    public String devServerStop(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "Server name to stop. Null/blank = stop all running servers in this session.") final String serverName
    ) {
        try {
            this.registry.require(sessionId);
        } catch (final IllegalStateException ex) {
            return "Error: " + ex.getMessage();
        }
        if (serverName == null || serverName.isBlank()) {
            int count = this.devServerService.getAllProcesses(sessionId).size();
            this.devServerService.stopAllServers(sessionId);
            return "Stopped " + count + " server(s) in this session.";
        }
        if (!this.devServerService.isRunning(sessionId, serverName)) {
            return "No running server named '" + serverName + "' in this session.";
        }
        this.devServerService.stopServer(sessionId, serverName);
        return "Stopped server '" + serverName + "'.";
    }

    // ========== dev_server_status ==========

    @Tool(name = "dev_server_status", description = "List the dev servers currently running for this session.")
    public String devServerStatus(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId
    ) {
        try {
            this.registry.require(sessionId);
        } catch (final IllegalStateException ex) {
            return "Error: " + ex.getMessage();
        }
        final Map<String, DevServerProcess> all = this.devServerService.getAllProcesses(sessionId);
        if (all.isEmpty()) {
            return "No dev servers running for this session.";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("Running dev servers (").append(all.size()).append("):\n");
        int i = 1;
        for (final DevServerProcess dsp : all.values()) {
            sb.append(i++).append(". [").append(dsp.serverName()).append("] ")
                .append("http://localhost:").append(dsp.hostPort())
                .append(" (containerPort=").append(dsp.containerPort())
                .append(", container=").append(dsp.containerName())
                .append(", volume=").append(dsp.volumeName())
                .append(", alive=").append(this.devServerService.isContainerAlive(dsp.containerName()))
                .append(")\n");
        }
        return sb.toString().trim();
    }

    // ========== dev_server_logs ==========

    @Tool(name = "dev_server_logs", description = "Return the tail of combined stdout/stderr logs for a running dev server "
        + "(via `docker logs --tail N <container>`). Use this to debug startup failures and runtime errors.")
    public String devServerLogs(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "Server name (must match a configured dev-server name).") final String serverName,
        @ToolParam(description = "Number of tail lines to fetch. Default: 200. Use 0 for all.") final Integer tailLines
    ) {
        try {
            this.registry.require(sessionId);
        } catch (final IllegalStateException ex) {
            return "Error: " + ex.getMessage();
        }
        if (serverName == null || serverName.isBlank()) {
            return "Error: serverName is required";
        }
        final int tail = (tailLines != null && tailLines > 0) ? tailLines : 200;
        final String out = this.devServerService.logs(sessionId, serverName, tail);
        return (out == null || out.isBlank()) ? "(no logs)" : out;
    }

    // ========== dev_server_close_session ==========

    @Tool(name = "dev_server_close_session", description = "Stop all dev servers in this session and fully clean up: "
        + "remove every workspace volume and the session network. Use this when you are done with the project "
        + "and want to free Docker resources. The browser session (if any) is NOT affected — call browser_close_session separately.")
    public String devServerCloseSession(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId
    ) {
        try {
            this.registry.require(sessionId);
        } catch (final IllegalStateException ex) {
            return "Error: " + ex.getMessage();
        }
        this.devServerService.closeSession(sessionId);
        return "Dev session closed: containers stopped, volumes and network removed.";
    }

    // ========== helpers ==========

    private static List<String> parseCommaSeparated(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        return Arrays.stream(input.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    private static Map<String, String> parseEnvironmentVars(String environmentVars) {
        if (environmentVars == null || environmentVars.isBlank()) {
            return Map.of();
        }
        Map<String, String> envMap = new LinkedHashMap<>();
        for (String pair : environmentVars.split(";")) {
            String trimmed = pair.trim();
            int eqIdx = trimmed.indexOf('=');
            if (eqIdx > 0) {
                envMap.put(trimmed.substring(0, eqIdx).trim(), trimmed.substring(eqIdx + 1).trim());
            }
        }
        return envMap;
    }
}
