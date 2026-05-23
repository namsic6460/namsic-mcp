package lkd.namsic.mcp.devserver;

import lkd.namsic.mcp.config.BrowserProperties;
import lkd.namsic.mcp.config.DevServerProperties;
import lkd.namsic.mcp.config.DevServerProperties.ServerConfig;
import lkd.namsic.mcp.session.ProjectSessionRegistry;
import lkd.namsic.mcp.session.ProjectSessionRegistry.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DevServerMcpToolsTest {

    @TempDir
    Path tempScreenshotBase;

    private ProjectSessionRegistry registry;
    private DevServerService devServerService;
    private DevServerMcpTools tools;

    @BeforeEach
    void setUp() {
        BrowserProperties browserProps = new BrowserProperties(
            null, null, null, null, null, null, null, null, null, this.tempScreenshotBase);
        this.registry = new ProjectSessionRegistry(browserProps);

        ServerConfig backend = new ServerConfig(
            Paths.get("/tmp/my-app"), "alpine:3.20", "echo backend", 3000,
            null, null, "/", null, false);
        DevServerProperties devProps = new DevServerProperties(
            Map.of("backend", backend),
            null, null, Duration.ofSeconds(2)
        );

        this.devServerService = mock(DevServerService.class);
        this.tools = new DevServerMcpTools(this.registry, devProps, this.devServerService);
    }

    @Test
    void listReturnsErrorForUnknownSession() {
        String result = this.tools.devServerList("00000000-0000-0000-0000-000000000000");
        assertTrue(result.startsWith("Error: Unknown sessionId"), result);
    }

    @Test
    void listShowsRegisteredServersForKnownSession() {
        Project p = this.registry.init("any");
        when(this.devServerService.isRunning(anyString(), anyString())).thenReturn(false);

        String result = this.tools.devServerList(p.sessionId());
        assertTrue(result.contains("Registered dev servers"), result);
        assertTrue(result.contains("backend"), result);
        assertTrue(result.contains("echo backend"), result);
        assertTrue(result.contains("alpine:3.20"), result);
    }

    @Test
    void listShowsEmptyMessageWhenNoServersConfigured() {
        DevServerProperties empty = new DevServerProperties(Map.of(), null, null, Duration.ofSeconds(1));
        DevServerMcpTools t = new DevServerMcpTools(this.registry, empty, this.devServerService);
        Project p = this.registry.init("any");
        String result = t.devServerList(p.sessionId());
        assertEquals("No servers configured under app.dev-servers.", result);
    }

    @Test
    void startReturnsErrorForUnknownSession() {
        String result = this.tools.devServerStart(
            "00000000-0000-0000-0000-000000000000", "backend", null);
        assertTrue(result.startsWith("Error: Unknown sessionId"), result);
    }

    @Test
    void startRequiresServerNames() {
        Project p = this.registry.init("any");
        String result = this.tools.devServerStart(p.sessionId(), null, null);
        assertTrue(result.startsWith("Error: serverNames is required"), result);
    }

    @Test
    void startReturnsErrorForUnknownServerName() {
        Project p = this.registry.init("any");
        String result = this.tools.devServerStart(p.sessionId(), "nonexistent", null);
        assertTrue(result.startsWith("Error: unknown server 'nonexistent'"), result);
    }

    @Test
    void stopReturnsNoRunningMessageWhenNothingStarted() {
        Project p = this.registry.init("any");
        when(this.devServerService.isRunning(anyString(), anyString())).thenReturn(false);
        String result = this.tools.devServerStop(p.sessionId(), "backend");
        assertEquals("No running server named 'backend' in this session.", result);
    }

    @Test
    void statusReturnsIdleMessageWhenNothingRunning() {
        Project p = this.registry.init("any");
        when(this.devServerService.getAllProcesses(any())).thenReturn(Map.of());
        String result = this.tools.devServerStatus(p.sessionId());
        assertEquals("No dev servers running for this session.", result);
    }

    @Test
    void logsReturnsErrorForBlankServerName() {
        Project p = this.registry.init("any");
        String result = this.tools.devServerLogs(p.sessionId(), "", 100);
        assertEquals("Error: serverName is required", result);
    }

    @Test
    void restartReturnsErrorForUnknownSession() {
        String result = this.tools.devServerRestart(
            "00000000-0000-0000-0000-000000000000", "backend", null);
        assertTrue(result.startsWith("Error: Unknown sessionId"), result);
    }

    @Test
    void restartReturnsErrorForUnknownServerName() {
        Project p = this.registry.init("any");
        String result = this.tools.devServerRestart(p.sessionId(), "nonexistent", null);
        assertTrue(result.startsWith("Error: unknown server 'nonexistent'"), result);
    }

    @Test
    void restartDelegatesToServiceWithParsedNames() {
        Project p = this.registry.init("any");
        DevServerProcess dsp = new DevServerProcess(
            "dev-server-x-backend", "backend", 10042, 3000, "vol");
        when(this.devServerService.restartServers(
            eq(p.sessionId()), any(), anyList(), any())).thenReturn(List.of(dsp));

        String result = this.tools.devServerRestart(p.sessionId(), "backend", null);

        assertTrue(result.startsWith("Restarted dev servers"), result);
        assertTrue(result.contains("[backend]"), result);
        assertTrue(result.contains("http://localhost:10042"), result);
    }

    @Test
    void restartWithBlankNamesPassesEmptyListMeaningAll() {
        Project p = this.registry.init("any");
        when(this.devServerService.restartServers(
            eq(p.sessionId()), any(), eq(List.of()), any())).thenReturn(List.of());

        String result = this.tools.devServerRestart(p.sessionId(), "", null);

        assertTrue(result.startsWith("Restarted dev servers"), result);
    }

    @Test
    void restartSurfacesServiceErrorMessage() {
        Project p = this.registry.init("any");
        when(this.devServerService.restartServers(
            eq(p.sessionId()), any(), anyList(), any()))
            .thenThrow(new IllegalStateException("No dev servers running in this session"));

        String result = this.tools.devServerRestart(p.sessionId(), "backend", null);

        assertTrue(result.startsWith("Error restarting dev servers"), result);
        assertTrue(result.contains("No dev servers running"), result);
    }

    @Test
    void closeSessionReturnsConfirmation() {
        Project p = this.registry.init("any");
        String result = this.tools.devServerCloseSession(p.sessionId());
        assertTrue(result.contains("Dev session closed"), result);
    }
}
