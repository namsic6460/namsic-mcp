package lkd.namsic.mcp.devserver;

import lkd.namsic.mcp.config.DevServerProperties;
import lkd.namsic.mcp.config.DevServerProperties.ServerConfig;
import lkd.namsic.mcp.config.DevServerProperties.ServerConfig.DependsOnRef;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevServerServiceTest {

    private static ServerConfig cfg(String startCmd, List<String> dependsOnNames) {
        List<DependsOnRef> refs = dependsOnNames.stream()
            .map(n -> new DependsOnRef(n, true))
            .toList();
        return new ServerConfig(
            Paths.get("/tmp"), "alpine:3", startCmd, 3000,
            refs, null, "/", null, false);
    }

    private static ServerConfig cfgRefs(String startCmd, List<DependsOnRef> dependsOn) {
        return new ServerConfig(
            Paths.get("/tmp"), "alpine:3", startCmd, 3000,
            dependsOn, null, "/", null, false);
    }

    @SafeVarargs
    private static Map<String, ServerConfig> map(Map.Entry<String, ServerConfig>... entries) {
        Map<String, ServerConfig> m = new LinkedHashMap<>();
        for (Map.Entry<String, ServerConfig> e : entries) m.put(e.getKey(), e.getValue());
        return m;
    }

    @Test
    void topoWavesGroupsIndependentServersIntoOneWave() {
        // a → {b, c}, b and c are independent → wave 0 = [b, c], wave 1 = [a]
        Map<String, ServerConfig> all = map(
            Map.entry("a", cfg("echo a", List.of("b", "c"))),
            Map.entry("b", cfg("echo b", List.of())),
            Map.entry("c", cfg("echo c", List.of()))
        );
        List<List<String>> waves = DevServerService.topoWaves(all, List.of("a"));
        assertEquals(2, waves.size(), "expected two waves: deps + a");
        assertTrue(waves.get(0).containsAll(List.of("b", "c")));
        assertEquals(2, waves.get(0).size());
        assertEquals(List.of("a"), waves.get(1));
    }

    @Test
    void topoWavesSerializesChainAcrossMultipleWaves() {
        // a → b → c : each level a separate wave
        Map<String, ServerConfig> all = map(
            Map.entry("a", cfg("echo a", List.of("b"))),
            Map.entry("b", cfg("echo b", List.of("c"))),
            Map.entry("c", cfg("echo c", List.of()))
        );
        List<List<String>> waves = DevServerService.topoWaves(all, List.of("a"));
        assertEquals(List.of(List.of("c"), List.of("b"), List.of("a")), waves);
    }

    @Test
    void topoWavesHandlesDiamond() {
        // a → {b, c}, b → c : waves = [[c], [b], [a]]
        Map<String, ServerConfig> all = map(
            Map.entry("a", cfg("echo a", List.of("b", "c"))),
            Map.entry("b", cfg("echo b", List.of("c"))),
            Map.entry("c", cfg("echo c", List.of()))
        );
        List<List<String>> waves = DevServerService.topoWaves(all, List.of("a"));
        assertEquals(List.of(List.of("c"), List.of("b"), List.of("a")), waves);
    }

    @Test
    void topoWavesIncludesTransitiveDependenciesEvenIfNotRequested() {
        // request only frontend, backend dep is pulled in
        Map<String, ServerConfig> all = map(
            Map.entry("backend", cfg("echo b", List.of())),
            Map.entry("frontend", cfg("echo f", List.of("backend")))
        );
        List<List<String>> waves = DevServerService.topoWaves(all, List.of("frontend"));
        assertEquals(List.of(List.of("backend"), List.of("frontend")), waves);
    }

    @Test
    void topoWavesPlacesNoWaitDepInSameWave() {
        // frontend depends on backend with wait=false → both go in wave 0 (parallel start)
        Map<String, ServerConfig> all = map(
            Map.entry("frontend", cfgRefs("echo f", List.of(new DependsOnRef("backend", false)))),
            Map.entry("backend", cfg("echo b", List.of()))
        );
        List<List<String>> waves = DevServerService.topoWaves(all, List.of("frontend"));
        assertEquals(1, waves.size(), "no-wait dep should not split waves");
        assertTrue(waves.getFirst().containsAll(List.of("backend", "frontend")));
        assertEquals(2, waves.getFirst().size());
    }

    @Test
    void topoWavesIncludesNoWaitDepInTransitiveClosure() {
        // even though frontend doesn't wait for backend, backend is still pulled into the start set
        Map<String, ServerConfig> all = map(
            Map.entry("frontend", cfgRefs("echo f", List.of(new DependsOnRef("backend", false)))),
            Map.entry("backend", cfg("echo b", List.of()))
        );
        List<List<String>> waves = DevServerService.topoWaves(all, List.of("frontend"));
        long total = waves.stream().mapToLong(List::size).sum();
        assertEquals(2, total, "backend should be auto-included");
    }

    @Test
    void topoWavesMixedWaitAndNoWait() {
        // frontend: wait=true on db, wait=false on backend
        // → wave 0 = [db, backend], wave 1 = [frontend]
        Map<String, ServerConfig> all = map(
            Map.entry("frontend", cfgRefs("echo f", List.of(
                new DependsOnRef("db", true),
                new DependsOnRef("backend", false)))),
            Map.entry("db", cfg("echo d", List.of())),
            Map.entry("backend", cfg("echo b", List.of()))
        );
        List<List<String>> waves = DevServerService.topoWaves(all, List.of("frontend"));
        assertEquals(2, waves.size());
        assertTrue(waves.get(0).containsAll(List.of("db", "backend")));
        assertEquals(2, waves.get(0).size());
        assertEquals(List.of("frontend"), waves.get(1));
    }

    @Test
    void topoWavesIgnoresCycleAmongNoWaitDeps() {
        // a ↔ b, both wait=false → no ordering cycle, both start together
        Map<String, ServerConfig> all = map(
            Map.entry("a", cfgRefs("echo a", List.of(new DependsOnRef("b", false)))),
            Map.entry("b", cfgRefs("echo b", List.of(new DependsOnRef("a", false))))
        );
        List<List<String>> waves = DevServerService.topoWaves(all, List.of("a"));
        assertEquals(1, waves.size());
        assertTrue(waves.getFirst().containsAll(List.of("a", "b")));
    }

    @Test
    void dependsOnRefRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> new DependsOnRef("", true));
        assertThrows(IllegalArgumentException.class, () -> new DependsOnRef(null, true));
    }

    @Test
    void topoWavesThrowsOnCycle() {
        Map<String, ServerConfig> all = map(
            Map.entry("a", cfg("echo a", List.of("b"))),
            Map.entry("b", cfg("echo b", List.of("a")))
        );
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> DevServerService.topoWaves(all, List.of("a")));
        assertTrue(ex.getMessage().toLowerCase().contains("circular"), ex.getMessage());
    }

    @Test
    void topoWavesThrowsOnUnknownReference() {
        Map<String, ServerConfig> all = map(
            Map.entry("a", cfg("echo a", List.of("missing")))
        );
        assertThrows(IllegalStateException.class,
            () -> DevServerService.topoWaves(all, List.of("a")));
    }

    @Test
    void sanitizeServerNameReplacesUnsafeChars() {
        assertEquals("backend", DevServerService.sanitizeServerName("backend"));
        assertEquals("a-b-c", DevServerService.sanitizeServerName("a/b c"));
        assertEquals("x_y", DevServerService.sanitizeServerName("x_y"));
    }

    @Test
    void resolveEnvironmentSubstitutesEnvPlaceholders() {
        DevServerProperties props = new DevServerProperties(Map.of(), null, null, Duration.ofSeconds(1));
        DevServerService svc = new DevServerService(
            props,
            mock(DockerVolumeService.class),
            mock(PortAllocationService.class),
            mock(DockerEnvironment.class)
        );
        String known = System.getenv().keySet().stream().findFirst().orElse(null);
        if (known == null) return;
        Map<String, String> template = Map.of("X", "{env:" + known + "}");
        Map<String, String> resolved = svc.resolveEnvironment("any-sid", template);
        assertEquals(System.getenv(known), resolved.get("X"));
    }

    @Test
    void resolveEnvironmentSubstitutesServerPortPlaceholders() throws Exception {
        DevServerProperties props = new DevServerProperties(Map.of(), null, null, Duration.ofSeconds(1));
        DevServerService svc = new DevServerService(
            props,
            mock(DockerVolumeService.class),
            mock(PortAllocationService.class),
            mock(DockerEnvironment.class)
        );
        injectProcess(svc, "sid-1", "backend",
            new DevServerProcess("dev-server-sid-1-backend", "backend", 10042, 3000, "vol"));

        Map<String, String> template = new LinkedHashMap<>();
        template.put("APP_PORT", "{backend:port}");
        template.put("HOST_PORT", "{backend:hostPort}");
        template.put("HOST_URL", "http://host.docker.internal:{backend:hostPort}/api");
        template.put("CONTAINER_NAME", "{backend}");

        Map<String, String> resolved = svc.resolveEnvironment("sid-1", template);

        assertEquals("3000", resolved.get("APP_PORT"));
        assertEquals("10042", resolved.get("HOST_PORT"));
        assertEquals("http://host.docker.internal:10042/api", resolved.get("HOST_URL"));
        assertEquals("dev-server-sid-1-backend", resolved.get("CONTAINER_NAME"));
    }

    @SuppressWarnings("unchecked")
    private static void injectProcess(DevServerService svc, String sessionId, String serverName, DevServerProcess dsp)
        throws Exception {
        Field f = DevServerService.class.getDeclaredField("processes");
        f.setAccessible(true);
        Map<String, Map<String, DevServerProcess>> processes =
            (Map<String, Map<String, DevServerProcess>>) f.get(svc);
        processes.computeIfAbsent(sessionId, _ -> new ConcurrentHashMap<>()).put(serverName, dsp);
    }

    @Test
    void resolveEnvironmentSeesWaveMatesRegisteredInPhase1() throws Exception {
        // Regression: when wave-mates start in parallel (waitForReady=false), each launch's
        // resolveEnvironment must see every other wave-mate's placeholder. We simulate phase-1
        // having registered both servers' placeholders before either launch begins, then verify
        // that env templates for one server resolve the *other*.
        DevServerProperties props = new DevServerProperties(Map.of(), null, null, Duration.ofSeconds(1));
        DevServerService svc = new DevServerService(
            props,
            mock(DockerVolumeService.class),
            mock(PortAllocationService.class),
            mock(DockerEnvironment.class)
        );
        injectProcess(svc, "sid-1", "backend",
            new DevServerProcess("dev-server-sid-1-backend", "backend", 10001, 8080, "vol-b"));
        injectProcess(svc, "sid-1", "frontend",
            new DevServerProcess("dev-server-sid-1-frontend", "frontend", 10002, 5173, "vol-f"));

        Map<String, String> frontendTemplate = new LinkedHashMap<>();
        frontendTemplate.put("API_URL", "http://{backend}:{backend:port}");
        Map<String, String> resolved = svc.resolveEnvironment("sid-1", frontendTemplate);
        assertEquals("http://dev-server-sid-1-backend:8080", resolved.get("API_URL"));
    }

    @Test
    void restartServersThrowsWhenSessionHasNothingRunning() {
        DevServerProperties props = new DevServerProperties(Map.of(), null, null, Duration.ofSeconds(1));
        DevServerService svc = new DevServerService(
            props,
            mock(DockerVolumeService.class),
            mock(PortAllocationService.class),
            mock(DockerEnvironment.class)
        );
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> svc.restartServers("missing-sid", Map.of(), List.of("backend"), Map.of()));
        assertTrue(ex.getMessage().contains("No dev servers running"), ex.getMessage());
    }

    @Test
    void restartServersThrowsWhenTargetNotRunning() throws Exception {
        DevServerProperties props = new DevServerProperties(Map.of(), null, null, Duration.ofSeconds(1));
        DevServerService svc = new DevServerService(
            props,
            mock(DockerVolumeService.class),
            mock(PortAllocationService.class),
            mock(DockerEnvironment.class)
        );
        injectProcess(svc, "sid-1", "backend",
            new DevServerProcess("dev-server-sid-1-backend", "backend", 10001, 3000, "vol"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> svc.restartServers("sid-1", Map.of(), List.of("frontend"), Map.of()));
        assertTrue(ex.getMessage().contains("not running"), ex.getMessage());
    }

    private static ServerConfig hostNetCfg() {
        return new ServerConfig(
            Paths.get("/tmp"), "alpine:3", "echo go", 8081,
            List.of(), null, "/", null, true);
    }

    private static ServerConfig bridgeCfg() {
        return new ServerConfig(
            Paths.get("/tmp"), "alpine:3", "echo go", 3000,
            List.of(), null, "/", null, false);
    }

    private static DevServerService newServiceWith(DockerEnvironment env) {
        DevServerProperties props = new DevServerProperties(Map.of(), null, null, Duration.ofSeconds(1));
        DockerVolumeService volSvc = mock(DockerVolumeService.class);
        when(volSvc.resolveNetworkName("sid")).thenReturn("namsic-net-sid");
        return new DevServerService(props, volSvc, mock(PortAllocationService.class), env);
    }

    @Test
    void buildDockerCommandUsesHostNetworkOnNativeLinux() {
        DockerEnvironment env = mock(DockerEnvironment.class);
        when(env.isDockerDesktop()).thenReturn(false);
        DevServerService svc = newServiceWith(env);

        List<String> cmd = svc.buildDockerCommand("sid", "ctr", "vol", 8081, 8081, hostNetCfg(), Map.of());

        assertTrue(cmd.contains("host"), "expected --network host on native Linux: " + cmd);
        int netIdx = cmd.indexOf("--network");
        assertEquals("host", cmd.get(netIdx + 1));
        assertFalse(cmd.contains("-p"), "host-network mode must not use -p mapping: " + cmd);
        assertFalse(cmd.contains("--add-host"), "host-network mode must not add host.docker.internal: " + cmd);
    }

    @Test
    void buildDockerCommandRemapsHostNetworkToBridgeOnDockerDesktop() {
        // Regression: on Docker Desktop, --network host containers can't be reached from the OS
        // host, so the readiness probe hangs forever. The fix downgrades host-network to bridge
        // with an explicit -p hostPort:containerPort mapping. The hostPort comes from the
        // dynamic 10000+ range (prepareSingle decides) while the container still listens on
        // its configured containerPort for intra-network peer talk.
        DockerEnvironment env = mock(DockerEnvironment.class);
        when(env.isDockerDesktop()).thenReturn(true);
        DevServerService svc = newServiceWith(env);

        List<String> cmd = svc.buildDockerCommand("sid", "ctr", "vol", 8081, 10042, hostNetCfg(), Map.of());

        assertFalse(cmd.contains("host"), "must NOT use --network host on Docker Desktop: " + cmd);
        int netIdx = cmd.indexOf("--network");
        assertEquals("namsic-net-sid", cmd.get(netIdx + 1));
        int pIdx = cmd.indexOf("-p");
        assertTrue(pIdx >= 0, "Docker Desktop fallback must add -p mapping: " + cmd);
        assertEquals("10042:8081", cmd.get(pIdx + 1));
        assertTrue(cmd.contains("--add-host"), "should add host.docker.internal so the container can still reach the host: " + cmd);
    }

    @Test
    void prepareSingleReservesContainerPortAsHostPortOnNativeLinux() {
        // Real --network host: the host port IS the container port (they share a namespace),
        // so we must reservePort the fixed containerPort rather than dynamically allocate.
        DevServerProperties props = new DevServerProperties(Map.of(), null, null, Duration.ofSeconds(1));
        DockerVolumeService volSvc = mock(DockerVolumeService.class);
        PortAllocationService ports = mock(PortAllocationService.class);
        DevServerService svc = new DevServerService(props, volSvc, ports, mockEnv(false));

        DevServerService.Prepared p = svc.prepareSingle("sid", "backend", hostNetCfg());

        verify(ports).reservePort("sid", "backend", 8081);
        verify(ports, never()).allocatePort("sid", "backend");
        assertEquals(8081, p.placeholder().hostPort());
        assertEquals(8081, p.placeholder().containerPort());
    }

    @Test
    void prepareSingleAllocatesDynamicHostPortOnDockerDesktopFallback() {
        // Regression: on Docker Desktop, useHostNetwork=true is downgraded to bridge+port-mapping,
        // and the host port must come from the dynamic 10000+ range so dev containers do NOT
        // bind well-known ports on the user's actual machine. The container still listens on
        // containerPort (so peers reach it by container name) but the published host port differs.
        DevServerProperties props = new DevServerProperties(Map.of(), null, null, Duration.ofSeconds(1));
        DockerVolumeService volSvc = mock(DockerVolumeService.class);
        PortAllocationService ports = mock(PortAllocationService.class);
        when(ports.allocatePort("sid", "backend")).thenReturn(10042);
        DevServerService svc = new DevServerService(props, volSvc, ports, mockEnv(true));

        DevServerService.Prepared p = svc.prepareSingle("sid", "backend", hostNetCfg());

        verify(ports).allocatePort("sid", "backend");
        verify(ports, never()).reservePort("sid", "backend", 8081);
        assertEquals(10042, p.placeholder().hostPort());
        assertEquals(8081, p.placeholder().containerPort(), "container must still listen on its fixed port");
    }

    @Test
    void buildDockerCommandBridgeModeIsUnaffectedByDockerDesktopFlag() {
        // useHostNetwork=false should produce the same command on either engine.
        DevServerService desktop = newServiceWith(mockEnv(true));
        DevServerService linux = newServiceWith(mockEnv(false));

        List<String> cDesktop = desktop.buildDockerCommand("sid", "ctr", "vol", 3000, 10500, bridgeCfg(), Map.of());
        List<String> cLinux = linux.buildDockerCommand("sid", "ctr", "vol", 3000, 10500, bridgeCfg(), Map.of());

        assertEquals(cLinux, cDesktop);
    }

    private static DockerEnvironment mockEnv(boolean isDesktop) {
        DockerEnvironment env = mock(DockerEnvironment.class);
        when(env.isDockerDesktop()).thenReturn(isDesktop);
        return env;
    }

    @Test
    void resolveEnvironmentLeavesUnknownEnvAsEmpty() {
        DevServerProperties props = new DevServerProperties(Map.of(), null, null, Duration.ofSeconds(1));
        DevServerService svc = new DevServerService(
            props,
            mock(DockerVolumeService.class),
            mock(PortAllocationService.class),
            mock(DockerEnvironment.class)
        );
        Map<String, String> template = Map.of("API", "{env:DEFINITELY_NOT_SET_12345}/v1");
        Map<String, String> resolved = svc.resolveEnvironment("any-sid", template);
        assertEquals("/v1", resolved.get("API"));
    }
}
