package lkd.namsic.mcp.browser;

import lkd.namsic.mcp.config.BrowserProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserServerServiceTest {

    private static BrowserProperties props(String nodePath, String browserType, List<String> launchArgs,
                                           Duration startupTimeout) {
        return new BrowserProperties(
            nodePath, browserType, launchArgs, startupTimeout,
            null, null, null, null, null, null
        );
    }

    @Test
    void stopBrowserServerShouldRemoveProcess() {
        BrowserServerService service = new BrowserServerService(
            props("node", "chromium", List.of(), Duration.ofSeconds(5)));

        assertDoesNotThrow(() -> service.stopBrowserServer("unknown-session"));
        assertTrue(service.getProcesses().isEmpty());
    }

    @Test
    void destroyAllShouldClearAllProcesses() {
        BrowserServerService service = new BrowserServerService(
            props("node", "chromium", List.of(), Duration.ofSeconds(5)));

        assertDoesNotThrow(service::destroyAll);
        assertTrue(service.getProcesses().isEmpty());
    }

    @Test
    void startBrowserServerShouldThrowOnInvalidNode() {
        BrowserServerService service = new BrowserServerService(
            props("__nonexistent_node_binary__", "chromium", List.of(), Duration.ofSeconds(2)));

        assertThrows(IllegalStateException.class,
            () -> service.startBrowserServer("test-session"));
    }

    @Test
    void buildLaunchArgsJsonShouldEscapeSpecialCharacters() {
        BrowserServerService service = new BrowserServerService(
            props("node", "chromium",
                List.of("--flag=normal", "--quotes=\"'\\", "--newline=line1\nline2", "--tab=\t"),
                Duration.ofSeconds(5)));

        String result = service.buildLaunchArgsJson();

        assertTrue(result.startsWith(", args: ["));
        assertTrue(result.endsWith("]"));
        assertTrue(result.contains("\\n"), "newline should be escaped: " + result);
        assertTrue(result.contains("\\t"), "tab should be escaped: " + result);
        assertTrue(result.contains("\\\""), "double quote should be escaped: " + result);
    }

    @Test
    void buildLaunchArgsJsonShouldReturnEmptyStringForNoArgs() {
        BrowserServerService service = new BrowserServerService(
            props("node", "chromium", List.of(), Duration.ofSeconds(5)));

        assertEquals("", service.buildLaunchArgsJson());
    }

    @Test
    void startBrowserServerShouldRejectUnknownBrowserType() {
        BrowserServerService service = new BrowserServerService(
            props("node", "evil-engine; rm -rf /", List.of(), Duration.ofSeconds(2)));

        assertThrows(IllegalStateException.class,
            () -> service.startBrowserServer("test-session"));
    }
}
