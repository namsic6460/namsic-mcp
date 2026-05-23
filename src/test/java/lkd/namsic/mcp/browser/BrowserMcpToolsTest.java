package lkd.namsic.mcp.browser;

import lkd.namsic.mcp.config.BrowserProperties;
import lkd.namsic.mcp.session.ProjectSessionRegistry;
import lkd.namsic.mcp.session.ProjectSessionRegistry.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BrowserMcpToolsTest {

    @TempDir
    Path tempScreenshotBase;

    private ProjectSessionRegistry registry;
    private BrowserMcpTools tools;

    @BeforeEach
    void setUp() {
        BrowserProperties props = new BrowserProperties(
            "node", "chromium", List.of(), Duration.ofSeconds(5),
            null, null, null, null, null, this.tempScreenshotBase
        );
        this.registry = new ProjectSessionRegistry(props);
        this.tools = new BrowserMcpTools(
            mock(BrowserServerService.class),
            this.registry,
            props
        );
    }

    @Test
    void closeSessionReturnsNoSessionWhenUnknown() {
        String result = this.tools.browserCloseSession("00000000-0000-0000-0000-000000000000");
        assertEquals("No session to close", result);
    }

    @Test
    void destroyShouldBeIdempotent() {
        assertDoesNotThrow(this.tools::destroy);
        assertTrue(this.tools.getSessions().isEmpty());
    }

    @Test
    void clickWithTimelineRejectsTotalDurationOver110sBeforeSessionCheck() {
        String result = this.tools.browserClickWithTimeline(
            "00000000-0000-0000-0000-000000000000", 100, 200, "left", 1, 1000, 200);
        assertTrue(result.startsWith("Error: total timeline duration would exceed 110s"),
            "Expected guard error but got: " + result);
    }

    @Test
    void screenshotFailsWithoutInit() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> this.tools.browserScreenshot("00000000-0000-0000-0000-000000000000"));
        assertTrue(ex.getMessage().contains("project_init"), ex.getMessage());
    }

    @Test
    void screenshotFailsWithBlankSessionId() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> this.tools.browserScreenshot(""));
        assertTrue(ex.getMessage().contains("sessionId is required"), ex.getMessage());
    }

    @Test
    void navigateReturnsErrorWithoutInit() {
        String result = this.tools.browserNavigate(
            "00000000-0000-0000-0000-000000000000", "https://example.com", "networkidle", 5000);
        assertTrue(result.startsWith("Error: Unknown sessionId"), result);
    }

    @Test
    void closeSessionRemovesFromRegistry() {
        Project p = this.registry.init("closeable");
        assertTrue(this.registry.getProjects().containsKey(p.sessionId()));

        String result = this.tools.browserCloseSession(p.sessionId());
        assertEquals("Session closed", result);
        assertTrue(this.registry.getProjects().isEmpty());
    }

    @Test
    void browserPropertiesAppliesDefaults() {
        BrowserProperties p = new BrowserProperties(
            null, null, null, null, null, null, null, null, null, null);
        assertEquals("node", p.nodePath());
        assertEquals("chromium", p.browserType());
        assertEquals(List.of(), p.launchArgs());
        assertEquals(Duration.ofSeconds(30), p.startupTimeout());
        assertEquals(false, p.headless());
        assertEquals(1920, p.viewportWidth());
        assertEquals(1080, p.viewportHeight());
        assertEquals(1.0, p.deviceScaleFactor());
        assertEquals(Duration.ofSeconds(60), p.navigationTimeout());
        assertTrue(p.screenshotBaseDir().toString().contains(".namsic-mcp"));
    }
}
