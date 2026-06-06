package lkd.namsic.mcp.browser;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import lkd.namsic.mcp.config.BrowserProperties;
import lkd.namsic.mcp.session.ProjectSessionRegistry;
import lkd.namsic.mcp.session.ProjectSessionRegistry.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private static String firstText(CallToolResult result) {
        TextContent text = assertInstanceOf(TextContent.class, result.content().getFirst());
        return text.text();
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
        CallToolResult result = this.tools.browserClickWithTimeline(
            "00000000-0000-0000-0000-000000000000", 100, 200, "left", 1, 1000, 200);
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(firstText(result).startsWith("Error: total timeline duration would exceed 110s"),
            "Expected guard error but got: " + firstText(result));
    }

    @Test
    void screenshotFailsWithoutInit() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> this.tools.browserScreenshot("00000000-0000-0000-0000-000000000000", null));
        assertTrue(ex.getMessage().contains("project_init"), ex.getMessage());
    }

    @Test
    void screenshotFailsWithBlankSessionId() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> this.tools.browserScreenshot("", null));
        assertTrue(ex.getMessage().contains("sessionId is required"), ex.getMessage());
    }

    @Test
    void clickWithScreenshotAfterMsFailsWithoutInit() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> this.tools.browserClick("00000000-0000-0000-0000-000000000000", 10, 20, null, null, 500));
        assertTrue(ex.getMessage().contains("project_init"), ex.getMessage());
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
    void screenshotBudgetErrorGuardsUpperBoundOnly() {
        assertNull(BrowserMcpTools.screenshotBudgetError(0));
        assertNull(BrowserMcpTools.screenshotBudgetError(110_000));
        String error = BrowserMcpTools.screenshotBudgetError(110_001);
        assertTrue(error != null && error.startsWith("Error: screenshotAfterMs"), String.valueOf(error));
    }

    @Test
    void wantsJpegDefaultsToJpeg() {
        assertTrue(BrowserMcpTools.wantsJpeg(null));
        assertTrue(BrowserMcpTools.wantsJpeg("jpeg"));
        assertTrue(BrowserMcpTools.wantsJpeg("jpg"));
        assertTrue(BrowserMcpTools.wantsJpeg("anything-else"));
        assertFalse(BrowserMcpTools.wantsJpeg("png"));
        assertFalse(BrowserMcpTools.wantsJpeg("PNG"));
        assertFalse(BrowserMcpTools.wantsJpeg(" png "));
    }

    @Test
    void extMatchesFormat() {
        assertEquals("jpg", BrowserMcpTools.ext(true));
        assertEquals("png", BrowserMcpTools.ext(false));
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
        assertEquals(1280, p.viewportWidth());
        assertEquals(720, p.viewportHeight());
        assertEquals(1.0, p.deviceScaleFactor());
        assertEquals(Duration.ofSeconds(60), p.navigationTimeout());
        assertTrue(p.screenshotBaseDir().toString().contains(".namsic-mcp"));
    }

    // ===== 멀티탭 도구 가드 =====

    @Test
    void newContextReturnsErrorWithoutInit() {
        String result = this.tools.browserNewContext("00000000-0000-0000-0000-000000000000", null);
        assertTrue(result.startsWith("Error:"), result);
    }

    @Test
    void newTabRejectsBlockedUrlBeforeSessionCheck() {
        String result = this.tools.browserNewTab(
            "00000000-0000-0000-0000-000000000000", "http://169.254.169.254/latest/meta-data/");
        assertTrue(result.startsWith("Error: URL blocked by security policy"), result);
    }

    @Test
    void switchTabFailsWithoutInit() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> this.tools.browserSwitchTab("00000000-0000-0000-0000-000000000000", "tab-1"));
        assertTrue(ex.getMessage().contains("project_init"), ex.getMessage());
    }

    @Test
    void closeTabFailsWithoutInit() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> this.tools.browserCloseTab("00000000-0000-0000-0000-000000000000", "tab-1"));
        assertTrue(ex.getMessage().contains("project_init"), ex.getMessage());
    }

    // ===== route 도구 가드 =====

    @Test
    void routeRequiresPattern() {
        String result = this.tools.browserRoute("00000000-0000-0000-0000-000000000000",
            " ", null, null, null, null, null, null, null);
        assertEquals("Error: urlPattern is required", result);
    }

    @Test
    void routeRejectsUnknownActionBeforeSessionCheck() {
        String result = this.tools.browserRoute("00000000-0000-0000-0000-000000000000",
            "**/api/**", "explode", null, null, null, null, null, null);
        assertTrue(result.startsWith("Error: unknown route action"), result);
    }

    @Test
    void routeRejectsExcessiveDelayBeforeSessionCheck() {
        String result = this.tools.browserRoute("00000000-0000-0000-0000-000000000000",
            "**/api/**", "continue", null, null, null, null, null, 30_001);
        assertTrue(result.startsWith("Error: delayMs"), result);
    }

    @Test
    void routeRejectsMalformedHeaderLine() {
        String result = this.tools.browserRoute("00000000-0000-0000-0000-000000000000",
            "**/api/**", "fulfill", 500, "{}", null, "no-colon-here", null, null);
        assertTrue(result.startsWith("Error: invalid header line"), result);
    }

    @Test
    void unrouteFailsWithoutInit() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> this.tools.browserUnroute("00000000-0000-0000-0000-000000000000", null));
        assertTrue(ex.getMessage().contains("project_init"), ex.getMessage());
    }

    // ===== CDP / 네트워크 도구 가드 =====

    @Test
    void networkConditionToolsRejectNonChromium() {
        BrowserProperties firefoxProps = new BrowserProperties(
            "node", "firefox", List.of(), Duration.ofSeconds(5),
            null, null, null, null, null, this.tempScreenshotBase
        );
        BrowserMcpTools firefoxTools = new BrowserMcpTools(
            mock(BrowserServerService.class), new ProjectSessionRegistry(firefoxProps), firefoxProps);

        String result = firefoxTools.browserSetNetworkConditions(
            "00000000-0000-0000-0000-000000000000", 100, null, null, null);
        assertTrue(result.startsWith("Error: this tool requires browserType=chromium"), result);

        CallToolResult video = firefoxTools.browserRecordVideo(
            "00000000-0000-0000-0000-000000000000", 1000, null, null, null);
        assertEquals(Boolean.TRUE, video.isError());
        assertTrue(firstText(video).contains("browserType=chromium"), firstText(video));
    }

    @Test
    void recordVideoRequiresDurationBeforeSessionCheck() {
        CallToolResult result = this.tools.browserRecordVideo(
            "00000000-0000-0000-0000-000000000000", null, null, null, null);
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(firstText(result).startsWith("Error: durationMs is required"), firstText(result));
    }

    @Test
    void setOfflineFailsWithoutInit() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> this.tools.browserSetOffline("00000000-0000-0000-0000-000000000000", true));
        assertTrue(ex.getMessage().contains("project_init"), ex.getMessage());
    }

    // ===== 캡처/입력 파일/픽셀 도구 가드 =====

    @Test
    void captureTimelineRejectsTotalDurationOver110sBeforeSessionCheck() {
        CallToolResult result = this.tools.browserCaptureTimeline(
            "00000000-0000-0000-0000-000000000000", 1000, 200, null);
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(firstText(result).startsWith("Error: total timeline duration would exceed 110s"),
            firstText(result));
    }

    @Test
    void setInputFilesRequiresSelectorBeforeSessionCheck() {
        String result = this.tools.browserSetInputFiles(
            "00000000-0000-0000-0000-000000000000", " ", null, null, null, null, null);
        assertEquals("Error: selector is required", result);
    }

    @Test
    void setInputFilesRejectsAmbiguousPayloadSource() {
        String result = this.tools.browserSetInputFiles(
            "00000000-0000-0000-0000-000000000000", "input[type=file]", null,
            "fake.png", "image/png", null, null);
        assertTrue(result.startsWith("Error: provide exactly one of payloadFilePath or payloadBase64"), result);
    }

    @Test
    void samplePixelsRejectsMissingAndMalformedInput() {
        String missing = this.tools.browserSamplePixels(
            "00000000-0000-0000-0000-000000000000", null, null);
        assertTrue(missing.startsWith("Error: provide points"), missing);

        String malformed = this.tools.browserSamplePixels(
            "00000000-0000-0000-0000-000000000000", "10;20", null);
        assertTrue(malformed.startsWith("Error: invalid point"), malformed);
    }

    // ===== 정적 헬퍼 =====

    @Test
    void chromiumGuardOnlyPassesChromium() {
        assertNull(BrowserMcpTools.chromiumGuard("chromium"));
        assertNull(BrowserMcpTools.chromiumGuard("Chromium"));
        assertNull(BrowserMcpTools.chromiumGuard(null));
        String error = BrowserMcpTools.chromiumGuard("firefox");
        assertTrue(error != null && error.contains("browserType=chromium"), String.valueOf(error));
    }

    @Test
    void parseRouteActionParsesAndRejects() {
        assertEquals(BrowserMcpTools.RouteAction.FULFILL, BrowserMcpTools.parseRouteAction(null));
        assertEquals(BrowserMcpTools.RouteAction.FULFILL, BrowserMcpTools.parseRouteAction("fulfill"));
        assertEquals(BrowserMcpTools.RouteAction.ABORT, BrowserMcpTools.parseRouteAction(" Abort "));
        assertEquals(BrowserMcpTools.RouteAction.CONTINUE, BrowserMcpTools.parseRouteAction("CONTINUE"));
        assertThrows(IllegalArgumentException.class, () -> BrowserMcpTools.parseRouteAction("drop"));
    }

    @Test
    void parseHeaderLinesParsesAndRejects() {
        assertTrue(BrowserMcpTools.parseHeaderLines(null).isEmpty());
        Map<String, String> parsed = BrowserMcpTools.parseHeaderLines(
            "X-Request-Id: abc-123\nRetry-After: 30\n");
        assertEquals("abc-123", parsed.get("X-Request-Id"));
        assertEquals("30", parsed.get("Retry-After"));
        assertThrows(IllegalArgumentException.class, () -> BrowserMcpTools.parseHeaderLines("no colon"));
    }

    @Test
    void resolvePayloadBytesRequiresExactlyOneSource() {
        assertThrows(IllegalArgumentException.class,
            () -> BrowserMcpTools.resolvePayloadBytes(null, null));
        assertThrows(IllegalArgumentException.class,
            () -> BrowserMcpTools.resolvePayloadBytes("C:/somewhere.png", "AAAA"));
        assertThrows(IllegalArgumentException.class,
            () -> BrowserMcpTools.resolvePayloadBytes(
                this.tempScreenshotBase.resolve("missing.bin").toString(), null));
        assertThrows(IllegalArgumentException.class,
            () -> BrowserMcpTools.resolvePayloadBytes(null, "!!not-base64!!"));
        byte[] decoded = BrowserMcpTools.resolvePayloadBytes(null, "aGVsbG8=");
        assertEquals("hello", new String(decoded, StandardCharsets.UTF_8));
    }

    @Test
    void grantRouteDelayClampsCumulativeSleepToBudget() {
        AtomicLong budget = new AtomicLong(25_000L);
        assertEquals(10_000L, BrowserMcpTools.grantRouteDelay(budget, 10_000));
        assertEquals(10_000L, BrowserMcpTools.grantRouteDelay(budget, 10_000));
        // 잔여 5000만 허용 — 누적이 예산을 넘지 않는다
        assertEquals(5_000L, BrowserMcpTools.grantRouteDelay(budget, 10_000));
        // 예산 소진 후에는 sleep 없이 통과
        assertEquals(0L, BrowserMcpTools.grantRouteDelay(budget, 10_000));
        assertEquals(0L, BrowserMcpTools.grantRouteDelay(new AtomicLong(30_000L), 0));
    }

    @Test
    void sampleEvenlyKeepsEndpointsAndSize() {
        List<Integer> items = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertEquals(items, BrowserMcpTools.sampleEvenly(items, 10));
        assertEquals(items, BrowserMcpTools.sampleEvenly(items, 20));
        List<Integer> sampled = BrowserMcpTools.sampleEvenly(items, 3);
        assertEquals(3, sampled.size());
        assertEquals(0, sampled.getFirst());
        assertEquals(9, sampled.getLast());
        assertEquals(List.of(0), BrowserMcpTools.sampleEvenly(items, 1));
    }
}
