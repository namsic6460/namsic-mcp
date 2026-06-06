package lkd.namsic.mcp.browser;

import com.google.gson.JsonObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.FileChooser;
import com.microsoft.playwright.Keyboard;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.MouseButton;
import com.microsoft.playwright.options.WaitUntilState;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import jakarta.annotation.PreDestroy;
import lkd.namsic.mcp.browser.BrowserSession.Shot;
import lkd.namsic.mcp.browser.BrowserSession.Tab;
import lkd.namsic.mcp.config.BrowserProperties;
import lkd.namsic.mcp.session.ProjectSessionRegistry;
import lkd.namsic.mcp.session.ProjectSessionRegistry.Project;
import lkd.namsic.mcp.util.BrowserUrlValidator;
import lkd.namsic.mcp.util.PixelSampler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class BrowserMcpTools {

    private static final String SESSION_PARAM_DESC =
        "Session ID returned by browser_init. Required; obtain it by calling browser_init with a project name first.";

    private static final long MAX_OPERATION_BUDGET_MS = 110_000L;
    /** route delayMs 상한 — 핸들러 sleep이 세션 펌프 스레드를 점유하므로 짧게 제한한다. */
    private static final long MAX_ROUTE_DELAY_MS = 30_000L;
    /** record_video 길이 상한 — submit await(120s)보다 충분히 짧아야 한다. */
    private static final int MAX_RECORD_DURATION_MS = 105_000;
    /** screencast 프레임 파일 수 상한 (디스크/매니페스트 폭주 방지). 초과분은 ack만 하고 버린다. */
    private static final int MAX_RECORD_FRAMES = 600;
    private static final int MAX_INLINE_FRAMES = 12;

    private static final String SCREENSHOT_AFTER_DESC =
        "Optional. If set (>=0, max 110000), waits this many ms after the action, then captures a JPEG screenshot "
            + "returned inline plus its saved file path. Prefer this over a separate browser_screenshot "
            + "call to verify the action result in a single round trip. Omit for no screenshot.";

    private static final String FORMAT_PARAM_DESC =
        "Image format: 'jpeg' (default, quality 80, much smaller/faster) or 'png' (lossless).";

    private final BrowserServerService browserServerService;
    private final ProjectSessionRegistry projectSessionRegistry;
    private final BrowserProperties properties;

    private final Map<String, BrowserSession> sessions = new ConcurrentHashMap<>();

    private BrowserSession getOrCreateSession(final String sid) {
        return this.sessions.computeIfAbsent(sid, this::createSession);
    }

    private BrowserSession createSession(final String sid) {
        final Project project = this.projectSessionRegistry.require(sid);
        final String wsEndpoint = this.browserServerService.startBrowserServer(sid);
        final BrowserSession session = new BrowserSession(sid, project.screenshotDir());
        final int viewportW = this.properties.viewportWidth();
        final int viewportH = this.properties.viewportHeight();
        final double dpr = this.properties.deviceScaleFactor();
        final Duration navTimeout = this.properties.navigationTimeout();

        session.submit(() -> {
            session.playwright = Playwright.create();
            session.browser = session.playwright.chromium().connect(wsEndpoint);
            final BrowserContext initialContext = session.browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(viewportW, viewportH)
                .setDeviceScaleFactor(dpr));
            initialContext.setDefaultNavigationTimeout(navTimeout.toMillis());
            session.initialContext = initialContext;
            final Tab first = session.registerTab(initialContext.newPage(), initialContext);
            session.attachPageListeners(first);
            session.setActive(first);
            return null;
        });
        log.info("Browser session created for sessionId={} project={} viewport={}x{} dpr={}",
            sid, project.projectName(), viewportW, viewportH, dpr);
        return session;
    }

    private BrowserSession requireSession(final String sid) {
        this.projectSessionRegistry.require(sid);
        final BrowserSession s = this.sessions.get(sid);
        if (s == null) {
            throw new IllegalStateException("No browser session for sessionId=" + sid + ". Call browser_navigate first.");
        }
        return s;
    }

    @PreDestroy
    public void destroy() {
        log.info("Closing all browser sessions (count={})", this.sessions.size());
        this.sessions.forEach((id, s) -> {
            try {
                s.close();
            } catch (final Exception e) {
                log.warn("Failed to close session for {}", id, e);
            }
        });
        this.sessions.clear();
    }

    // ===== Navigation / inspection =====

    @McpTool(name = "browser_navigate", description = "Navigate the headed browser tab to a URL. "
        + "Coordinates for all input tools are viewport CSS pixels with top-left origin; "
        + "the effective viewport size is included in this tool's response. "
        + "For LibGDX/teavm canvas-based apps use waitUntil=networkidle so WASM bundles fully load.")
    public String browserNavigate(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "URL to navigate to (e.g. http://localhost:8080)") final String url,
        @McpToolParam(description = "Wait condition: 'load', 'domcontentloaded', 'networkidle', or 'commit'. Default: networkidle", required = false) final String waitUntil,
        @McpToolParam(description = "Navigation timeout in ms. Default: 60000", required = false) final Integer timeoutMs
    ) {
        log.info("MCP tool invoked: browser_navigate url={}, sessionId={}", url, sessionId);

        final BrowserUrlValidator.ValidationResult check = BrowserUrlValidator.validate(url);
        if (!check.allowed()) {
            log.warn("Rejected browser_navigate due to URL policy: url={}, reason={}", url, check.reason());
            return "Error: URL blocked by security policy (" + check.reason() + "): " + url;
        }

        try {
            this.projectSessionRegistry.require(sessionId);
        } catch (final IllegalStateException ex) {
            return "Error: " + ex.getMessage();
        }

        final BrowserSession s = this.getOrCreateSession(sessionId);
        final WaitUntilState state = parseWaitUntil(waitUntil);
        final int timeout = timeoutMs != null && timeoutMs > 0 ? timeoutMs : 60_000;
        try {
            s.submit(() -> {
                s.page.navigate(url, new Page.NavigateOptions().setWaitUntil(state).setTimeout(timeout));
                return null;
            });
            return "Navigated to " + url + " (viewport "
                + this.properties.viewportWidth() + "x" + this.properties.viewportHeight() + ")";
        } catch (final RuntimeException ex) {
            log.warn("Navigation failed: {}", url, ex);
            return "Error navigating to " + url + ": " + ex.getMessage();
        }
    }

    @McpTool(name = "browser_screenshot", description = "Capture the current viewport and save it under the project screenshot dir. "
        + "Returns the image inline (no separate file read needed) plus the saved file path. "
        + "Defaults to JPEG (quality 80); pass format=png for lossless. "
        + "To verify the result of an action, prefer the screenshotAfterMs parameter on the action tool itself "
        + "(act + wait + capture in a single call) over calling this tool separately.")
    public CallToolResult browserScreenshot(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = FORMAT_PARAM_DESC, required = false) final String format
    ) {
        log.info("MCP tool invoked: browser_screenshot sessionId={} format={}", sessionId, format);
        final BrowserSession s = this.requireSession(sessionId);
        final boolean jpeg = wantsJpeg(format);
        final String filename = String.format("%04d.%s", s.seq.incrementAndGet(), ext(jpeg));
        try {
            final Shot shot = s.submit(() -> s.captureInThread(filename, jpeg));
            return textAndImage(s.returnPathFor(filename), shot);
        } catch (final RuntimeException ex) {
            log.warn("Screenshot failed", ex);
            return errText("Error taking screenshot: " + ex.getMessage());
        }
    }

    @McpTool(name = "browser_screenshot_with_grid", description = "Capture a screenshot with a coordinate grid overlay, "
        + "returned inline plus the saved file path. "
        + "Useful for canvas-based UIs to estimate click coordinates more accurately. "
        + "Defaults to JPEG (quality 80); pass format=png if the grid labels look too blurry.")
    public CallToolResult browserScreenshotWithGrid(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Grid spacing in pixels. Default: 100", required = false) final Integer gridPx,
        @McpToolParam(description = FORMAT_PARAM_DESC, required = false) final String format
    ) {
        log.info("MCP tool invoked: browser_screenshot_with_grid sessionId={} grid={}", sessionId, gridPx);
        final BrowserSession s = this.requireSession(sessionId);
        final int grid = gridPx != null && gridPx > 0 ? gridPx : 100;
        final boolean jpeg = wantsJpeg(format);
        final String filename = String.format("%04d_grid.%s", s.seq.incrementAndGet(), ext(jpeg));
        final String overlayJs = buildGridOverlayJs(grid);
        try {
            final Shot shot = s.submit(() -> {
                s.page.evaluate(overlayJs);
                final Shot captured = s.captureInThread(filename, jpeg);
                s.page.evaluate("document.getElementById('__browser_mcp_grid__')?.remove();");
                return captured;
            });
            return textAndImage(s.returnPathFor(filename), shot);
        } catch (final RuntimeException ex) {
            log.warn("Grid screenshot failed", ex);
            return errText("Error taking grid screenshot: " + ex.getMessage());
        }
    }

    @McpTool(name = "browser_evaluate", description = "Evaluate a JavaScript expression in the page context and return the serialized result. "
        + "For complex objects, wrap your expression with JSON.stringify(...) for clean output.")
    public String browserEvaluate(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "JavaScript expression to evaluate") final String jsExpression
    ) {
        log.info("MCP tool invoked: browser_evaluate sessionId={}", sessionId);
        final BrowserSession s = this.requireSession(sessionId);
        try {
            final Object result = s.submit(() -> s.page.evaluate(jsExpression));
            return result == null ? "null" : result.toString();
        } catch (final RuntimeException ex) {
            return "Error evaluating JS: " + ex.getMessage();
        }
    }

    @McpTool(name = "browser_wait", description = "Sleep for the specified milliseconds. Useful for waiting on animations or initial WASM/JS loading.")
    public String browserWait(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Milliseconds to wait", required = false) final Integer ms
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        final int waitMs = ms != null && ms > 0 ? ms : 0;
        try {
            s.submit(() -> {
                s.page.waitForTimeout(waitMs);
                return null;
            });
            return "Waited " + waitMs + " ms";
        } catch (final RuntimeException ex) {
            return "Error waiting: " + ex.getMessage();
        }
    }

    @McpTool(name = "browser_wait_for_function", description = "Wait until a JavaScript expression returns truthy.")
    public String browserWaitForFunction(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "JS expression that should eventually return truthy") final String jsExpression,
        @McpToolParam(description = "Timeout in ms. Default: 10000", required = false) final Integer timeoutMs
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        final int timeout = timeoutMs != null && timeoutMs > 0 ? timeoutMs : 10_000;
        try {
            s.submit(() -> {
                s.page.waitForFunction(jsExpression, null,
                    new Page.WaitForFunctionOptions().setTimeout(timeout));
                return null;
            });
            return "Function condition met";
        } catch (final RuntimeException ex) {
            return "Error waiting for function: " + ex.getMessage();
        }
    }

    @McpTool(name = "browser_wait_for_frames", description = "Wait for N animation frames to elapse (requestAnimationFrame). "
        + "Useful for canvas games where state updates over multiple frames after an input.")
    public String browserWaitForFrames(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Number of frames to wait", required = false) final Integer n
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        final int frames = n != null && n > 0 ? n : 1;
        final String js = "() => new Promise(r => { let i = " + frames
            + "; const tick = () => --i <= 0 ? r(true) : requestAnimationFrame(tick); requestAnimationFrame(tick); })";
        try {
            s.submit(() -> {
                s.page.waitForFunction(js);
                return null;
            });
            return "Waited " + frames + " frames";
        } catch (final RuntimeException ex) {
            return "Error waiting for frames: " + ex.getMessage();
        }
    }

    // ===== Mouse =====

    @McpTool(name = "browser_click", description = "Click at viewport coordinates (x, y). "
        + "Coordinate system: viewport CSS pixels with top-left origin at (0,0); "
        + "the effective size is reported by browser_navigate. "
        + "For canvas-based apps (LibGDX/teavm) this is the primary interaction method - DOM selectors are not usable. "
        + "Click on the canvas first to focus it before keyboard input. "
        + "To verify the result, prefer screenshotAfterMs (click + wait + capture in one call). "
        + "If you need to observe transient UI state that appears briefly after the click "
        + "(toasts, loading indicators, short animations), use browser_click_with_timeline "
        + "instead — it clicks and captures a sequence of frames in one call.")
    public CallToolResult browserClick(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "X coordinate in viewport pixels") final Integer x,
        @McpToolParam(description = "Y coordinate in viewport pixels") final Integer y,
        @McpToolParam(description = "Mouse button: 'left', 'right', 'middle'. Default: left", required = false) final String button,
        @McpToolParam(description = "Click count (1=single, 2=double). Default: 1", required = false) final Integer clickCount,
        @McpToolParam(description = SCREENSHOT_AFTER_DESC, required = false) final Integer screenshotAfterMs
    ) {
        log.info("MCP tool invoked: browser_click ({},{}) sessionId={}", x, y, sessionId);
        final BrowserSession s = this.requireSession(sessionId);
        final MouseButton mb = parseButton(button);
        final int cc = clickCount != null && clickCount > 0 ? clickCount : 1;
        try {
            return this.actAndMaybeCapture(s, screenshotAfterMs,
                String.format("Clicked at (%d,%d) button=%s count=%d", x, y, mb, cc),
                () -> s.page.mouse().click(x, y, new Mouse.ClickOptions().setButton(mb).setClickCount(cc)));
        } catch (final RuntimeException ex) {
            return errText("Error clicking: " + ex.getMessage());
        }
    }

    @McpTool(name = "browser_click_with_timeline", description = "Click at viewport coordinates "
        + "and capture a timeline of screenshots: one frame just before the click (t0), then "
        + "`captureCount` frames each approximately `intervalMs` apart after the click "
        + "(t1..tN). All frames are returned inline (plus their saved file paths) — no separate "
        + "file reads needed. Use this instead of browser_click + browser_screenshot when you "
        + "need to observe transient UI (toasts, loading indicators, short animations) that a "
        + "single after-screenshot would miss. Actual capture times drift slightly beyond "
        + "nominal because each screenshot itself takes ~100-300ms. "
        + "Coordinate system: viewport CSS pixels with top-left origin.")
    public CallToolResult browserClickWithTimeline(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "X coordinate in viewport pixels") final Integer x,
        @McpToolParam(description = "Y coordinate in viewport pixels") final Integer y,
        @McpToolParam(description = "Mouse button: 'left', 'right', 'middle'. Default: left", required = false) final String button,
        @McpToolParam(description = "Click count (1=single, 2=double). Default: 1", required = false) final Integer clickCount,
        @McpToolParam(description = "Capture interval in ms. Default: 1000", required = false) final Integer intervalMs,
        @McpToolParam(description = "Number of captures after the click. Default: 5", required = false) final Integer captureCount
    ) {
        final int interval = intervalMs != null && intervalMs > 0 ? intervalMs : 1000;
        final int captures = captureCount != null && captureCount > 0 ? captureCount : 5;
        if ((long) interval * captures > MAX_OPERATION_BUDGET_MS) {
            return errText("Error: total timeline duration would exceed 110s (intervalMs=" + interval
                + " * captureCount=" + captures + "). Reduce either parameter.");
        }
        log.info("MCP tool invoked: browser_click_with_timeline ({},{}) interval={} captures={} sessionId={}",
            x, y, interval, captures, sessionId);
        final BrowserSession s = this.requireSession(sessionId);
        final MouseButton mb = parseButton(button);
        final int cc = clickCount != null && clickCount > 0 ? clickCount : 1;
        final int seq = s.seq.incrementAndGet();
        try {
            final List<Shot> shots = s.submit(() -> {
                final List<Shot> collected = new ArrayList<>(captures + 1);
                collected.add(s.captureInThread(String.format("%04d_t0.jpg", seq), true));
                s.page.mouse().click(x, y, new Mouse.ClickOptions().setButton(mb).setClickCount(cc));
                for (int i = 1; i <= captures; i++) {
                    s.page.waitForTimeout(interval);
                    collected.add(s.captureInThread(String.format("%04d_t%d.jpg", seq, i), true));
                }
                return collected;
            });
            return buildTimelineResult(s,
                String.format("Clicked at (%d,%d) button=%s count=%d. %d frames (t0=before, t1..t%d≈+%dms each):",
                    x, y, mb, cc, shots.size(), captures, interval),
                shots);
        } catch (final RuntimeException ex) {
            log.warn("Click with timeline failed", ex);
            return errText("Error click with timeline: " + ex.getMessage());
        }
    }

    @McpTool(name = "browser_double_click", description = "Double-click at viewport coordinates.")
    public CallToolResult browserDoubleClick(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "X coordinate in viewport pixels") final Integer x,
        @McpToolParam(description = "Y coordinate in viewport pixels") final Integer y,
        @McpToolParam(description = SCREENSHOT_AFTER_DESC, required = false) final Integer screenshotAfterMs
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        try {
            return this.actAndMaybeCapture(s, screenshotAfterMs,
                String.format("Double-clicked at (%d,%d)", x, y),
                () -> s.page.mouse().dblclick(x, y));
        } catch (final RuntimeException ex) {
            return errText("Error double-clicking: " + ex.getMessage());
        }
    }

    @McpTool(name = "browser_drag", description = "Drag from (x1,y1) to (x2,y2) in viewport pixels. "
        + "Sends N intermediate move events so canvas drag listeners (e.g. LibGDX) react properly.")
    public CallToolResult browserDrag(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Start X") final Integer x1,
        @McpToolParam(description = "Start Y") final Integer y1,
        @McpToolParam(description = "End X") final Integer x2,
        @McpToolParam(description = "End Y") final Integer y2,
        @McpToolParam(description = "Number of intermediate move events. Default: 20", required = false) final Integer steps,
        @McpToolParam(description = SCREENSHOT_AFTER_DESC, required = false) final Integer screenshotAfterMs
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        final int st = steps != null && steps > 0 ? steps : 20;
        try {
            return this.actAndMaybeCapture(s, screenshotAfterMs,
                String.format("Dragged (%d,%d) -> (%d,%d) steps=%d", x1, y1, x2, y2, st),
                () -> {
                    s.page.mouse().move(x1, y1);
                    s.page.mouse().down();
                    s.page.mouse().move(x2, y2, new Mouse.MoveOptions().setSteps(st));
                    s.page.mouse().up();
                });
        } catch (final RuntimeException ex) {
            return errText("Error dragging: " + ex.getMessage());
        }
    }

    @McpTool(name = "browser_mouse_move", description = "Move the mouse to (x,y) in viewport pixels over N steps. "
        + "Useful for hover or drag mid-motion.")
    public String browserMouseMove(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "X coordinate in viewport pixels") final Integer x,
        @McpToolParam(description = "Y coordinate in viewport pixels") final Integer y,
        @McpToolParam(description = "Number of intermediate steps. Default: 1", required = false) final Integer steps
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        final int st = steps != null && steps > 0 ? steps : 1;
        try {
            s.submit(() -> {
                s.page.mouse().move(x, y, new Mouse.MoveOptions().setSteps(st));
                return null;
            });
            return String.format("Mouse moved to (%d,%d)", x, y);
        } catch (final RuntimeException ex) {
            return "Error moving mouse: " + ex.getMessage();
        }
    }

    @McpTool(name = "browser_mouse_down", description = "Press a mouse button at the current position without releasing. Pair with browser_mouse_up.")
    public String browserMouseDown(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Mouse button: 'left', 'right', 'middle'. Default: left", required = false) final String button
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        final MouseButton mb = parseButton(button);
        try {
            s.submit(() -> {
                s.page.mouse().down(new Mouse.DownOptions().setButton(mb));
                return null;
            });
            return "Mouse down: " + mb;
        } catch (final RuntimeException ex) {
            return "Error mouse down: " + ex.getMessage();
        }
    }

    @McpTool(name = "browser_mouse_up", description = "Release a mouse button.")
    public String browserMouseUp(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Mouse button: 'left', 'right', 'middle'. Default: left", required = false) final String button
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        final MouseButton mb = parseButton(button);
        try {
            s.submit(() -> {
                s.page.mouse().up(new Mouse.UpOptions().setButton(mb));
                return null;
            });
            return "Mouse up: " + mb;
        } catch (final RuntimeException ex) {
            return "Error mouse up: " + ex.getMessage();
        }
    }

    @McpTool(name = "browser_wheel", description = "Send a wheel/scroll event at the current mouse position.")
    public CallToolResult browserWheel(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Horizontal scroll delta in pixels", required = false) final Integer deltaX,
        @McpToolParam(description = "Vertical scroll delta in pixels (positive=down)", required = false) final Integer deltaY,
        @McpToolParam(description = SCREENSHOT_AFTER_DESC, required = false) final Integer screenshotAfterMs
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        final int dx = deltaX != null ? deltaX : 0;
        final int dy = deltaY != null ? deltaY : 0;
        try {
            return this.actAndMaybeCapture(s, screenshotAfterMs,
                String.format("Scrolled (%d,%d)", dx, dy),
                () -> s.page.mouse().wheel(dx, dy));
        } catch (final RuntimeException ex) {
            return errText("Error scrolling: " + ex.getMessage());
        }
    }

    // ===== Keyboard =====

    @McpTool(name = "browser_type", description = "Type text into the focused element. "
        + "For canvas apps, call browser_click on the canvas first to give it focus.")
    public CallToolResult browserType(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Text to type") final String text,
        @McpToolParam(description = "Delay between keystrokes in ms. Default: 10", required = false) final Integer delayMs,
        @McpToolParam(description = SCREENSHOT_AFTER_DESC, required = false) final Integer screenshotAfterMs
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        final int d = delayMs != null && delayMs >= 0 ? delayMs : 10;
        try {
            return this.actAndMaybeCapture(s, screenshotAfterMs,
                "Typed " + text.length() + " chars",
                () -> s.page.keyboard().type(text, new Keyboard.TypeOptions().setDelay(d)));
        } catch (final RuntimeException ex) {
            return errText("Error typing: " + ex.getMessage());
        }
    }

    @McpTool(name = "browser_press_key", description = "Press and release a key. Examples: 'Enter', 'Escape', 'ArrowLeft', 'Space', 'Tab', 'Control+a'.")
    public CallToolResult browserPressKey(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Key name (Playwright key syntax)") final String key,
        @McpToolParam(description = SCREENSHOT_AFTER_DESC, required = false) final Integer screenshotAfterMs
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        try {
            return this.actAndMaybeCapture(s, screenshotAfterMs,
                "Pressed " + key,
                () -> s.page.keyboard().press(key));
        } catch (final RuntimeException ex) {
            return errText("Error pressing key: " + ex.getMessage());
        }
    }

    @McpTool(name = "browser_key_down", description = "Hold a key down without releasing. Pair with browser_key_up. "
        + "Useful for game movement keys (e.g. holding ArrowRight to move).")
    public String browserKeyDown(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Key name") final String key
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        try {
            s.submit(() -> {
                s.page.keyboard().down(key);
                return null;
            });
            return "Key down: " + key;
        } catch (final RuntimeException ex) {
            return "Error key down: " + ex.getMessage();
        }
    }

    @McpTool(name = "browser_key_up", description = "Release a held key.")
    public String browserKeyUp(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Key name") final String key
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        try {
            s.submit(() -> {
                s.page.keyboard().up(key);
                return null;
            });
            return "Key up: " + key;
        } catch (final RuntimeException ex) {
            return "Error key up: " + ex.getMessage();
        }
    }

    // ===== File chooser =====

    @McpTool(name = "browser_expect_file_chooser", description = "Arm the next file chooser dialog to auto-respond with the given files. "
        + "PREFER browser_set_input_files when the page has an <input type=file>: it injects files directly "
        + "without depending on dialog interception, which is more reliable. "
        + "The picker must be triggered AFTER this call by another tool (e.g. browser_click on the upload button). "
        + "filePaths is a newline-separated list of absolute host paths; an empty string sets an empty selection (cancel-like). "
        + "The handler binds to the tab that is active at call time and self-removes when it fires or when timeoutMs "
        + "elapses (default 30000). "
        + "Calling this twice replaces the prior armed handler (the previous one is silently dropped). "
        + "The handler persists across navigations until it fires or times out. "
        + "If the underlying input is single-file but multiple paths are provided, the call records an error retrievable "
        + "via browser_get_page_errors. "
        + "Because Playwright Java pumps events on the same thread that issues calls, the handler does NOT fire on its own — "
        + "a subsequent tool call (browser_click etc.) is required to drive the message pump and deliver the chooser event.")
    public String browserExpectFileChooser(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Newline-separated absolute host paths. Empty string = empty selection.") final String filePaths,
        @McpToolParam(description = "Handler timeout in ms. Default: 30000", required = false) final Integer timeoutMs
    ) {
        log.info("MCP tool invoked: browser_expect_file_chooser sessionId={}", sessionId);
        final BrowserSession s = this.requireSession(sessionId);
        final int timeout = timeoutMs != null && timeoutMs > 0 ? timeoutMs : 30_000;

        final Path[] paths;
        try {
            paths = parseAndValidatePaths(filePaths);
        } catch (final IllegalArgumentException ex) {
            return "Error: " + ex.getMessage();
        }

        try {
            final boolean replaced = s.submit(() -> {
                final Tab tab = s.activeTab;
                final Consumer<FileChooser> previous = tab.armedFileChooser;
                final boolean replacedPrev = previous != null;
                if (replacedPrev) {
                    try {
                        tab.page.offFileChooser(previous);
                    } catch (final RuntimeException ex) {
                        log.warn("Failed to remove previous file chooser handler", ex);
                    }
                    tab.armedFileChooser = null;
                }

                final long deadline = System.currentTimeMillis() + timeout;
                @SuppressWarnings("unchecked")
                final Consumer<FileChooser>[] holder = new Consumer[1];
                holder[0] = chooser -> {
                    try {
                        tab.page.offFileChooser(holder[0]);
                    } catch (final RuntimeException ex) {
                        log.warn("Failed to self-remove file chooser handler", ex);
                    }
                    tab.armedFileChooser = null;
                    if (System.currentTimeMillis() > deadline) {
                        s.appendError("file chooser handler expired before trigger; skipping setFiles");
                        return;
                    }
                    try {
                        if (!chooser.isMultiple() && paths.length > 1) {
                            s.appendError("file chooser expects single file but " + paths.length + " provided");
                            return;
                        }
                        chooser.setFiles(paths);
                    } catch (final RuntimeException ex) {
                        s.appendError("file chooser setFiles failed: " + ex.getMessage());
                    }
                };
                tab.page.onFileChooser(holder[0]);
                tab.armedFileChooser = holder[0];
                return replacedPrev;
            });
            final String prefix = replaced ? "Previous handler replaced. " : "";
            return prefix + "File chooser armed for " + paths.length + " files; expires in " + timeout
                + "ms. Trigger the picker with a subsequent browser_click or similar call.";
        } catch (final RuntimeException ex) {
            log.warn("expect file chooser failed", ex);
            return "Error arming file chooser: " + ex.getMessage();
        }
    }

    @McpTool(name = "browser_set_input_files", description = "Set files on a file <input> element by CSS selector, "
        + "bypassing the OS dialog entirely — more reliable than browser_expect_file_chooser. "
        + "Two modes: (1) real files — filePaths is a newline-separated list of absolute host paths "
        + "(empty string clears the selection); (2) fake in-memory file — set payloadName + payloadMimeType "
        + "plus either payloadFilePath (read bytes from a host file, e.g. to disguise its name/MIME) or "
        + "payloadBase64 (arbitrary bytes). Use mode 2 for upload validation tests: extension spoofing, "
        + "magic-byte sniffing, size limits with crafted content.")
    public String browserSetInputFiles(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "CSS selector of the file input, e.g. 'input[type=file]'") final String selector,
        @McpToolParam(description = "Newline-separated absolute host paths. Empty = clear the selection. "
            + "Ignored when payloadName is set.", required = false) final String filePaths,
        @McpToolParam(description = "[fake file] File name presented to the page, e.g. 'avatar.png'",
            required = false) final String payloadName,
        @McpToolParam(description = "[fake file] MIME type presented to the page, e.g. 'image/png'. "
            + "Default: application/octet-stream", required = false) final String payloadMimeType,
        @McpToolParam(description = "[fake file] Absolute host path to read the byte content from",
            required = false) final String payloadFilePath,
        @McpToolParam(description = "[fake file] Base64-encoded byte content (alternative to payloadFilePath)",
            required = false) final String payloadBase64
    ) {
        log.info("MCP tool invoked: browser_set_input_files selector={} sessionId={}", selector, sessionId);
        if (selector == null || selector.isBlank()) {
            return "Error: selector is required";
        }
        if (payloadName != null && !payloadName.isBlank()) {
            final byte[] bytes;
            try {
                bytes = resolvePayloadBytes(payloadFilePath, payloadBase64);
            } catch (final IllegalArgumentException ex) {
                return "Error: " + ex.getMessage();
            }
            final String mime = payloadMimeType != null && !payloadMimeType.isBlank()
                ? payloadMimeType.trim() : "application/octet-stream";
            final FilePayload payload = new FilePayload(payloadName.trim(), mime, bytes);
            final BrowserSession s = this.requireSession(sessionId);
            try {
                s.submit(() -> {
                    s.page.setInputFiles(selector, new FilePayload[]{payload});
                    return null;
                });
                return "Set fake file '" + payloadName.trim() + "' (" + mime + ", " + bytes.length
                    + " bytes) on " + selector;
            } catch (final RuntimeException ex) {
                return "Error setting input files: " + ex.getMessage();
            }
        }
        final Path[] paths;
        try {
            paths = parseAndValidatePaths(filePaths);
        } catch (final IllegalArgumentException ex) {
            return "Error: " + ex.getMessage();
        }
        final BrowserSession s = this.requireSession(sessionId);
        try {
            s.submit(() -> {
                s.page.setInputFiles(selector, paths);
                return null;
            });
            return paths.length == 0
                ? "Cleared file selection on " + selector
                : "Set " + paths.length + " file(s) on " + selector;
        } catch (final RuntimeException ex) {
            return "Error setting input files: " + ex.getMessage();
        }
    }

    // ===== Tabs / contexts =====

    @McpTool(name = "browser_new_context", description = "Open a new tab in a NEW isolated browser context "
        + "(separate cookies/localStorage/sessionStorage — like an incognito profile). Use for multi-account "
        + "scenarios: log in as a second user without touching the first tab's session, then verify "
        + "interactions between the accounts (friend requests, chats, realtime broadcasts, permission "
        + "differences, concurrent-edit conflicts). The new tab becomes the active tab — all other browser_* "
        + "tools operate on the active tab; background tabs keep running (websockets stay alive). "
        + "Switch with browser_switch_tab. Optionally navigates to url right away.")
    public String browserNewContext(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "URL to open in the new tab. Optional — omit for a blank tab.",
            required = false) final String url
    ) {
        log.info("MCP tool invoked: browser_new_context sessionId={} url={}", sessionId, url);
        return this.openTab(sessionId, url, true);
    }

    @McpTool(name = "browser_new_tab", description = "Open a new tab in the ACTIVE tab's context — it SHARES "
        + "cookies/localStorage with the active tab (same account). Use for same-account two-tab scenarios "
        + "such as concurrent-edit (409/stale-version) tests. For a separate account use browser_new_context "
        + "instead. The new tab becomes the active tab. Optionally navigates to url right away.")
    public String browserNewTab(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "URL to open in the new tab. Optional — omit for a blank tab.",
            required = false) final String url
    ) {
        log.info("MCP tool invoked: browser_new_tab sessionId={} url={}", sessionId, url);
        return this.openTab(sessionId, url, false);
    }

    private String openTab(final String sessionId, final String url, final boolean isolated) {
        final String targetUrl = url != null && !url.isBlank() ? url.trim() : null;
        if (targetUrl != null) {
            final BrowserUrlValidator.ValidationResult check = BrowserUrlValidator.validate(targetUrl);
            if (!check.allowed()) {
                return "Error: URL blocked by security policy (" + check.reason() + "): " + targetUrl;
            }
        }
        final BrowserSession s;
        try {
            s = this.requireSession(sessionId);
        } catch (final IllegalStateException ex) {
            return "Error: " + ex.getMessage();
        }
        final int viewportW = this.properties.viewportWidth();
        final int viewportH = this.properties.viewportHeight();
        final double dpr = this.properties.deviceScaleFactor();
        final Duration navTimeout = this.properties.navigationTimeout();
        try {
            final String tabId = s.submit(() -> {
                final BrowserContext owner;
                if (isolated) {
                    owner = s.browser.newContext(new Browser.NewContextOptions()
                        .setViewportSize(viewportW, viewportH)
                        .setDeviceScaleFactor(dpr));
                    owner.setDefaultNavigationTimeout(navTimeout.toMillis());
                } else {
                    owner = s.activeTab.context;
                }
                final Tab tab = s.registerTab(owner.newPage(), owner);
                s.attachPageListeners(tab);
                s.setActive(tab);
                if (targetUrl != null) {
                    tab.page.navigate(targetUrl,
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                }
                return tab.tabId;
            });
            final String cookieNote = isolated
                ? "isolated cookies (new context)" : "shared cookies (same context)";
            return "Opened " + tabId + " with " + cookieNote + "; it is now the active tab"
                + (targetUrl != null ? " at " + targetUrl : "") + ". List tabs with browser_list_tabs.";
        } catch (final RuntimeException ex) {
            log.warn("Open tab failed (isolated={})", isolated, ex);
            return "Error opening tab: " + ex.getMessage();
        }
    }

    @McpTool(name = "browser_switch_tab", description = "Switch the active tab. All other browser_* tools "
        + "operate on the active tab. Background tabs keep running (websockets/timers stay alive), so you can "
        + "act in one account's tab and then switch to another account's tab to verify realtime effects.")
    public String browserSwitchTab(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Tab ID from browser_list_tabs / browser_new_tab / browser_new_context") final String tabId
    ) {
        log.info("MCP tool invoked: browser_switch_tab tabId={} sessionId={}", tabId, sessionId);
        final BrowserSession s = this.requireSession(sessionId);
        try {
            s.submit(() -> {
                final Tab tab = s.tabs.get(tabId);
                if (tab == null) {
                    throw new IllegalStateException("no such tab: " + tabId + ". Check browser_list_tabs.");
                }
                s.setActive(tab);
                tab.page.bringToFront();
                return null;
            });
            return "Active tab is now " + tabId;
        } catch (final RuntimeException ex) {
            return "Error switching tab: " + ex.getMessage();
        }
    }

    @McpTool(name = "browser_list_tabs", description = "List all tabs in this session: tab ID, context group "
        + "(tabs in the same ctx-N share cookies), URL and title. The active tab is marked with '*'.")
    public String browserListTabs(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        try {
            return s.submit(() -> {
                final StringBuilder sb = new StringBuilder();
                for (final Tab tab : s.tabs.values()) {
                    sb.append(tab == s.activeTab ? "* " : "  ")
                        .append(tab.tabId)
                        .append(" [").append(s.contextLabelFor(tab.context)).append("] ");
                    try {
                        sb.append(tab.page.url());
                        final String title = tab.page.title();
                        if (!title.isBlank()) {
                            sb.append(" — ").append(title);
                        }
                    } catch (final RuntimeException ex) {
                        sb.append("(unavailable: ").append(ex.getMessage()).append(')');
                    }
                    sb.append(System.lineSeparator());
                }
                return sb.toString().trim();
            });
        } catch (final RuntimeException ex) {
            return "Error listing tabs: " + ex.getMessage();
        }
    }

    @McpTool(name = "browser_close_tab", description = "Close a tab by ID. The last remaining tab cannot be "
        + "closed (use browser_close_session). If the closed tab was active, the first remaining tab becomes "
        + "active. When the closed tab was the last one of its context, the context (cookies) is disposed too.")
    public String browserCloseTab(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Tab ID from browser_list_tabs") final String tabId
    ) {
        log.info("MCP tool invoked: browser_close_tab tabId={} sessionId={}", tabId, sessionId);
        final BrowserSession s = this.requireSession(sessionId);
        try {
            final Tab newActive = s.submit(() -> s.closeTabInThread(tabId));
            return "Closed " + tabId + "; active tab is now " + newActive.tabId;
        } catch (final RuntimeException ex) {
            return "Error closing tab: " + ex.getMessage();
        }
    }

    // ===== Network interception =====

    @McpTool(name = "browser_route", description = "Intercept network requests of the ACTIVE tab whose URL "
        + "matches a glob pattern (Playwright glob, e.g. '**/api/users*'). action: 'fulfill' (default) answers "
        + "with a canned response (status/body/contentType/headers) without hitting the server — use it to "
        + "force 500/409/429 error paths deterministically; 'abort' fails the request at network level "
        + "(download-failure placeholders, reissue cascades); 'continue' lets it through, optionally after "
        + "delayMs (loading spinners, transient states). delayMs applies to every action. Routes registered on "
        + "the same pattern stack (last registered wins per Playwright semantics). Remove with browser_unroute. "
        + "Routes are bound to the tab active at call time and survive tab switches. "
        + "NOTE: the handler (and its delayMs sleep) runs on the session's single message-pump thread and fires "
        + "PER MATCHED REQUEST — with a broad pattern many requests each sleep, so the TOTAL sleep per "
        + "registered route is capped at 30000ms (further matches pass through without delay). Keep delayed "
        + "routes narrow (max delayMs 30000).")
    public String browserRoute(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "URL glob pattern (Playwright syntax), e.g. '**/api/users' or '**/*.png'") final String urlPattern,
        @McpToolParam(description = "Action: 'fulfill', 'abort', or 'continue'. Default: fulfill",
            required = false) final String action,
        @McpToolParam(description = "[fulfill] HTTP status code. Default: 200", required = false) final Integer status,
        @McpToolParam(description = "[fulfill] Response body string. Default: empty", required = false) final String body,
        @McpToolParam(description = "[fulfill] Content-Type. Default: application/json",
            required = false) final String contentType,
        @McpToolParam(description = "[fulfill] Extra response headers as 'Name: value' lines (newline-separated)",
            required = false) final String headers,
        @McpToolParam(description = "[abort] Playwright error code, e.g. 'failed', 'connectionrefused', "
            + "'timedout', 'internetdisconnected'. Default: failed", required = false) final String abortErrorCode,
        @McpToolParam(description = "Delay in ms before responding (any action). Max 30000 — blocks the "
            + "session thread. Default: 0", required = false) final Integer delayMs
    ) {
        log.info("MCP tool invoked: browser_route pattern={} action={} sessionId={}", urlPattern, action, sessionId);
        if (urlPattern == null || urlPattern.isBlank()) {
            return "Error: urlPattern is required";
        }
        final RouteAction act;
        final Map<String, String> headerMap;
        try {
            act = parseRouteAction(action);
            headerMap = parseHeaderLines(headers);
        } catch (final IllegalArgumentException ex) {
            return "Error: " + ex.getMessage();
        }
        final int delay = delayMs != null && delayMs > 0 ? delayMs : 0;
        if (delay > MAX_ROUTE_DELAY_MS) {
            return "Error: delayMs (" + delay + "ms) exceeds the " + MAX_ROUTE_DELAY_MS
                + "ms limit — it blocks the session thread. Reduce it.";
        }
        final BrowserSession s = this.requireSession(sessionId);
        final int st = status != null ? status : 200;
        final String ct = contentType != null && !contentType.isBlank() ? contentType.trim() : "application/json";
        final String bodyStr = body != null ? body : "";
        final String errCode = abortErrorCode != null && !abortErrorCode.isBlank() ? abortErrorCode.trim() : "failed";
        final String pattern = urlPattern.trim();
        try {
            // 핸들러는 매칭 요청마다 호출되므로 sleep이 N×delay로 누적될 수 있다 —
            // 등록 route당 누적 sleep 예산을 두어 단일 펌핑 호출이 submit await(120s)를 넘지 않게 한다.
            final AtomicLong delayBudget = new AtomicLong(MAX_ROUTE_DELAY_MS);
            final String tabId = s.submit(() -> {
                final Tab tab = s.activeTab;
                // 이 핸들러는 Playwright 메시지 펌프(=세션 executor 스레드)에서 실행된다.
                // 안에서 s.submit을 호출하면 영구 데드락 — Playwright 호출/필드 접근만 한다.
                final Consumer<Route> handler = route -> {
                    try {
                        final long granted = grantRouteDelay(delayBudget, delay);
                        if (granted > 0) {
                            try {
                                Thread.sleep(granted);
                            } catch (final InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        switch (act) {
                            case ABORT -> route.abort(errCode);
                            case CONTINUE -> route.resume();
                            case FULFILL -> {
                                final Route.FulfillOptions opts = new Route.FulfillOptions()
                                    .setStatus(st)
                                    .setContentType(ct)
                                    .setBody(bodyStr);
                                if (!headerMap.isEmpty()) {
                                    final Map<String, String> merged = new LinkedHashMap<>(headerMap);
                                    merged.put("Content-Type", ct);
                                    opts.setHeaders(merged);
                                }
                                route.fulfill(opts);
                            }
                        }
                    } catch (final RuntimeException ex) {
                        s.appendError("[" + tab.tabId + "] route handler failed for "
                            + route.request().url() + ": " + ex.getMessage());
                    }
                };
                tab.page.route(pattern, handler);
                tab.routes.computeIfAbsent(pattern, _ -> new ArrayList<>()).add(handler);
                return tab.tabId;
            });
            final String detail = switch (act) {
                case FULFILL -> "fulfill status=" + st + " contentType=" + ct + " bodyLength=" + bodyStr.length();
                case ABORT -> "abort errorCode=" + errCode;
                case CONTINUE -> "continue";
            };
            return "Route registered on " + tabId + ": " + pattern + " -> " + detail
                + (delay > 0 ? " delayMs=" + delay : "") + ". Remove with browser_unroute.";
        } catch (final RuntimeException ex) {
            log.warn("Route registration failed", ex);
            return "Error registering route: " + ex.getMessage();
        }
    }

    @McpTool(name = "browser_unroute", description = "Remove request interception from the ACTIVE tab. "
        + "Pass the exact glob pattern previously given to browser_route to remove its handlers, "
        + "or omit urlPattern to clear ALL routes on the active tab.")
    public String browserUnroute(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Exact glob pattern previously passed to browser_route. Omit to clear all.",
            required = false) final String urlPattern
    ) {
        log.info("MCP tool invoked: browser_unroute pattern={} sessionId={}", urlPattern, sessionId);
        final BrowserSession s = this.requireSession(sessionId);
        final String pattern = urlPattern != null && !urlPattern.isBlank() ? urlPattern.trim() : null;
        try {
            final int removed = s.submit(() -> {
                final Tab tab = s.activeTab;
                if (pattern == null) {
                    int count = 0;
                    for (final Map.Entry<String, List<Consumer<Route>>> entry : tab.routes.entrySet()) {
                        for (final Consumer<Route> handler : entry.getValue()) {
                            tab.page.unroute(entry.getKey(), handler);
                            count++;
                        }
                    }
                    tab.routes.clear();
                    return count;
                }
                final List<Consumer<Route>> handlers = tab.routes.remove(pattern);
                if (handlers == null) {
                    throw new IllegalStateException("no route registered on the active tab for pattern: "
                        + pattern);
                }
                for (final Consumer<Route> handler : handlers) {
                    tab.page.unroute(pattern, handler);
                }
                return handlers.size();
            });
            return removed == 0 ? "No routes were registered"
                : "Removed " + removed + " route handler(s)" + (pattern != null ? " for " + pattern : "");
        } catch (final RuntimeException ex) {
            return "Error removing route: " + ex.getMessage();
        }
    }

    // ===== Network conditions =====

    @McpTool(name = "browser_set_network_conditions", description = "Throttle the ACTIVE tab's network via CDP "
        + "(Network.emulateNetworkConditions) — slow down responses to observe loading spinners, transition "
        + "states and toasts that vanish too quickly at full speed. Throughputs are bytes/second; omit (or pass "
        + "<=0) for unlimited. Requires browserType=chromium. Reset with browser_clear_network_conditions. "
        + "For request-level delays on specific URLs prefer browser_route with delayMs.")
    public String browserSetNetworkConditions(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Added round-trip latency in ms. Default: 0", required = false) final Integer latencyMs,
        @McpToolParam(description = "Max download throughput in bytes/sec (e.g. 51200 = 50KB/s). "
            + "Omit for unlimited.", required = false) final Integer downloadBytesPerSec,
        @McpToolParam(description = "Max upload throughput in bytes/sec. Omit for unlimited.",
            required = false) final Integer uploadBytesPerSec,
        @McpToolParam(description = "Emulate a dropped connection. Default: false. "
            + "(browser_set_offline does the same per-context without CDP.)", required = false) final Boolean offline
    ) {
        log.info("MCP tool invoked: browser_set_network_conditions latency={} down={} up={} offline={} sessionId={}",
            latencyMs, downloadBytesPerSec, uploadBytesPerSec, offline, sessionId);
        final String guard = chromiumGuard(this.properties.browserType());
        if (guard != null) {
            return guard;
        }
        final int latency = latencyMs != null && latencyMs > 0 ? latencyMs : 0;
        final int down = downloadBytesPerSec != null && downloadBytesPerSec > 0 ? downloadBytesPerSec : -1;
        final int up = uploadBytesPerSec != null && uploadBytesPerSec > 0 ? uploadBytesPerSec : -1;
        final boolean off = Boolean.TRUE.equals(offline);
        final BrowserSession s = this.requireSession(sessionId);
        try {
            final String tabId = this.applyNetworkConditions(s, off, latency, down, up);
            return "Network conditions applied to " + tabId + ": latency=" + latency + "ms, download="
                + (down < 0 ? "unlimited" : down + " B/s") + ", upload=" + (up < 0 ? "unlimited" : up + " B/s")
                + ", offline=" + off + ". Reset with browser_clear_network_conditions.";
        } catch (final RuntimeException ex) {
            log.warn("Set network conditions failed", ex);
            return "Error setting network conditions: " + ex.getMessage();
        }
    }

    @McpTool(name = "browser_clear_network_conditions", description = "Reset CDP network throttling on the "
        + "ACTIVE tab back to unlimited speed / zero added latency / online.")
    public String browserClearNetworkConditions(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId
    ) {
        log.info("MCP tool invoked: browser_clear_network_conditions sessionId={}", sessionId);
        final String guard = chromiumGuard(this.properties.browserType());
        if (guard != null) {
            return guard;
        }
        final BrowserSession s = this.requireSession(sessionId);
        try {
            final String tabId = this.applyNetworkConditions(s, false, 0, -1, -1);
            return "Network conditions cleared on " + tabId + " (unlimited, no latency, online)";
        } catch (final RuntimeException ex) {
            return "Error clearing network conditions: " + ex.getMessage();
        }
    }

    private String applyNetworkConditions(final BrowserSession s, final boolean offline, final int latency,
        final int downloadBps, final int uploadBps) {
        return s.submit(() -> {
            final Tab tab = s.activeTab;
            final CDPSession cdp = cdpInThread(tab);
            cdp.send("Network.enable");
            final JsonObject params = new JsonObject();
            params.addProperty("offline", offline);
            params.addProperty("latency", latency);
            params.addProperty("downloadThroughput", downloadBps);
            params.addProperty("uploadThroughput", uploadBps);
            cdp.send("Network.emulateNetworkConditions", params);
            return tab.tabId;
        });
    }

    @McpTool(name = "browser_set_offline", description = "Toggle offline mode for the ACTIVE tab's context "
        + "(affects every tab sharing its cookies). Works on all browser types — use to test offline guards, "
        + "heartbeat loss, reconnect flows. Pass offline=false to restore connectivity.")
    public String browserSetOffline(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "true = go offline, false = back online") final Boolean offline
    ) {
        log.info("MCP tool invoked: browser_set_offline offline={} sessionId={}", offline, sessionId);
        final BrowserSession s = this.requireSession(sessionId);
        final boolean off = Boolean.TRUE.equals(offline);
        try {
            s.submit(() -> {
                s.context.setOffline(off);
                return null;
            });
            return off
                ? "Context is now offline (all tabs sharing the active tab's cookies)"
                : "Context is back online";
        } catch (final RuntimeException ex) {
            return "Error toggling offline: " + ex.getMessage();
        }
    }

    // ===== Frame capture / pixels =====

    @McpTool(name = "browser_capture_timeline", description = "Capture a sequence of screenshots of the active "
        + "tab WITHOUT any input: f0 immediately, then `count-1` more frames ≈`intervalMs` apart, all returned "
        + "inline plus saved file paths. Use to observe ongoing/continuous animations a single screenshot "
        + "misses: blinking cursors, toast fade-outs, loading spinner rotation, typewriter text, marquee "
        + "scrolling, SHAKE/FADE/GLITCH effects. (After a click, prefer browser_click_with_timeline.) "
        + "Actual capture times drift slightly beyond nominal because each screenshot takes ~100-300ms. "
        + "For sub-interval frame pacing driven by actual repaints use browser_record_video.")
    public CallToolResult browserCaptureTimeline(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Capture interval in ms. Default: 1000", required = false) final Integer intervalMs,
        @McpToolParam(description = "Total number of frames. Default: 5", required = false) final Integer count,
        @McpToolParam(description = FORMAT_PARAM_DESC, required = false) final String format
    ) {
        final int interval = intervalMs != null && intervalMs > 0 ? intervalMs : 1000;
        final int captures = count != null && count > 0 ? count : 5;
        if ((long) interval * captures > MAX_OPERATION_BUDGET_MS) {
            return errText("Error: total timeline duration would exceed 110s (intervalMs=" + interval
                + " * count=" + captures + "). Reduce either parameter.");
        }
        log.info("MCP tool invoked: browser_capture_timeline interval={} count={} sessionId={}",
            interval, captures, sessionId);
        final BrowserSession s = this.requireSession(sessionId);
        final boolean jpeg = wantsJpeg(format);
        final int seq = s.seq.incrementAndGet();
        try {
            final List<Shot> shots = s.submit(() -> {
                final List<Shot> collected = new ArrayList<>(captures);
                for (int i = 0; i < captures; i++) {
                    if (i > 0) {
                        s.page.waitForTimeout(interval);
                    }
                    collected.add(s.captureInThread(String.format("%04d_f%d.%s", seq, i, ext(jpeg)), jpeg));
                }
                return collected;
            });
            return buildTimelineResult(s,
                String.format("%d frames captured (f0=immediate, then ≈+%dms each):", shots.size(), interval),
                shots);
        } catch (final RuntimeException ex) {
            log.warn("Capture timeline failed", ex);
            return errText("Error capturing timeline: " + ex.getMessage());
        }
    }

    @McpTool(name = "browser_record_video", description = "Record the ACTIVE tab via the CDP screencast for "
        + "durationMs: frames arrive as the page actually repaints (not on a fixed interval), are saved as JPEG "
        + "files, and an evenly-sampled subset is returned inline with a relative-timestamp manifest. Best for "
        + "fast or irregular animations where fixed-interval capture misses frames. Requires "
        + "browserType=chromium. Static screens produce few or zero frames — use browser_capture_timeline for "
        + "guaranteed fixed-interval captures. NOTE: recording occupies the session thread for the whole "
        + "duration (max 105000ms); no other tool call runs meanwhile.")
    public CallToolResult browserRecordVideo(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Recording duration in ms (1..105000)") final Integer durationMs,
        @McpToolParam(description = "Max frames returned inline (evenly sampled). Default: 6, max: 12",
            required = false) final Integer maxInlineFrames,
        @McpToolParam(description = "JPEG quality 0-100. Default: 60", required = false) final Integer quality,
        @McpToolParam(description = "Capture every Nth compositor frame (raise to thin out 60fps pages). "
            + "Default: 1", required = false) final Integer everyNthFrame
    ) {
        log.info("MCP tool invoked: browser_record_video durationMs={} sessionId={}", durationMs, sessionId);
        final String guard = chromiumGuard(this.properties.browserType());
        if (guard != null) {
            return errText(guard);
        }
        if (durationMs == null || durationMs <= 0) {
            return errText("Error: durationMs is required (1.." + MAX_RECORD_DURATION_MS + ")");
        }
        final int dur = Math.min(durationMs, MAX_RECORD_DURATION_MS);
        final int inline = maxInlineFrames != null && maxInlineFrames > 0
            ? Math.min(maxInlineFrames, MAX_INLINE_FRAMES) : 6;
        final int q = quality != null && quality >= 0 && quality <= 100 ? quality : 60;
        final int nth = everyNthFrame != null && everyNthFrame > 0 ? everyNthFrame : 1;
        final BrowserSession s = this.requireSession(sessionId);
        final int seq = s.seq.incrementAndGet();
        final int maxW = this.properties.viewportWidth();
        final int maxH = this.properties.viewportHeight();
        try {
            final List<RecordedFrame> frames = s.submit(() -> {
                final Tab tab = s.activeTab;
                final CDPSession cdp = cdpInThread(tab);
                final List<RecordedFrame> collected = new ArrayList<>();
                final int[] dropped = {0};
                final long t0 = System.currentTimeMillis();
                // 이 핸들러는 메시지 펌프(=세션 executor 스레드)에서 실행된다 — submit 호출 금지.
                final Consumer<JsonObject> onFrame = ev -> {
                    try {
                        if (collected.size() >= MAX_RECORD_FRAMES) {
                            dropped[0]++;
                        } else {
                            final byte[] bytes = Base64.getDecoder().decode(ev.get("data").getAsString());
                            final String fn = String.format("%04d_v%03d.jpg", seq, collected.size());
                            Files.write(s.hostScreenshotDir.resolve(fn), bytes);
                            collected.add(new RecordedFrame(fn, System.currentTimeMillis() - t0));
                        }
                    } catch (final IOException | RuntimeException ex) {
                        s.appendError("[" + tab.tabId + "] screencast frame failed: " + ex.getMessage());
                    } finally {
                        // ack 누락 시 Chrome이 후속 프레임 전송을 멈춘다 — 드랍/실패 프레임도 항상 ack.
                        final JsonObject ack = new JsonObject();
                        ack.addProperty("sessionId", ev.get("sessionId").getAsInt());
                        cdp.send("Page.screencastFrameAck", ack);
                    }
                };
                cdp.on("Page.screencastFrame", onFrame);
                try {
                    final JsonObject start = new JsonObject();
                    start.addProperty("format", "jpeg");
                    start.addProperty("quality", q);
                    start.addProperty("maxWidth", maxW);
                    start.addProperty("maxHeight", maxH);
                    start.addProperty("everyNthFrame", nth);
                    cdp.send("Page.startScreencast", start);
                    // waitForTimeout이 메시지 펌프를 돌려 screencastFrame 이벤트가 수신된다
                    tab.page.waitForTimeout(dur);
                } finally {
                    try {
                        cdp.send("Page.stopScreencast");
                    } catch (final RuntimeException ex) {
                        log.warn("stopScreencast failed", ex);
                    }
                    cdp.off("Page.screencastFrame", onFrame);
                }
                if (dropped[0] > 0) {
                    s.appendError("[" + tab.tabId + "] screencast frame cap reached ("
                        + MAX_RECORD_FRAMES + "); " + dropped[0] + " frames dropped");
                }
                return collected;
            });
            if (frames.isEmpty()) {
                return okText("Recorded " + dur + "ms but no frames arrived — the page likely never repainted "
                    + "(static screen). Use browser_capture_timeline for fixed-interval captures.");
            }
            final List<RecordedFrame> sampled = sampleEvenly(frames, inline);
            final StringBuilder out = new StringBuilder(String.format(
                "Recorded %d frames over %dms; %d sampled inline (timestamps relative to start):",
                frames.size(), dur, sampled.size()));
            for (final RecordedFrame frame : sampled) {
                out.append(System.lineSeparator())
                    .append(String.format("@+%dms -> %s", frame.tMillis(), s.returnPathFor(frame.filename())));
            }
            out.append(System.lineSeparator()).append("All frames: ")
                .append(s.returnPathFor(String.format("%04d_v000.jpg", seq)))
                .append(" .. ")
                .append(s.returnPathFor(String.format("%04d_v%03d.jpg", seq, frames.size() - 1)));
            final CallToolResult.Builder builder = CallToolResult.builder().addTextContent(out.toString());
            for (final RecordedFrame frame : sampled) {
                final byte[] bytes = Files.readAllBytes(s.hostScreenshotDir.resolve(frame.filename()));
                builder.addContent(ImageContent.builder(
                    Base64.getEncoder().encodeToString(bytes), "image/jpeg").build());
            }
            return builder.isError(false).build();
        } catch (final IOException ex) {
            log.warn("Record video failed to read frame", ex);
            return errText("Error reading recorded frame: " + ex.getMessage());
        } catch (final RuntimeException ex) {
            log.warn("Record video failed", ex);
            return errText("Error recording video: " + ex.getMessage());
        }
    }

    @McpTool(name = "browser_sample_pixels", description = "Sample exact pixel colors from the active tab's "
        + "viewport for quantitative color checks (hover color transitions, cursor/marker colors, selection "
        + "highlights) — essential for canvas apps where DOM color inspection is impossible. Captures a "
        + "lossless PNG and returns JSON: per-point #RRGGBBAA for points='x1,y1;x2,y2;...' and/or the average "
        + "#RRGGBB over rect='x,y,w,h'. Coordinates are viewport CSS pixels (same as browser_click).")
    public String browserSamplePixels(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Semicolon-separated points 'x1,y1;x2,y2'. Optional when rect is given.",
            required = false) final String points,
        @McpToolParam(description = "Rect 'x,y,w,h' to average over. Optional when points is given.",
            required = false) final String rect
    ) {
        log.info("MCP tool invoked: browser_sample_pixels points={} rect={} sessionId={}", points, rect, sessionId);
        final List<PixelSampler.Point> pts;
        final int[] rectVals;
        try {
            pts = PixelSampler.parsePoints(points);
            rectVals = PixelSampler.parseRect(rect);
        } catch (final IllegalArgumentException ex) {
            return "Error: " + ex.getMessage();
        }
        if (pts.isEmpty() && rectVals == null) {
            return "Error: provide points ('x,y;x,y') and/or rect ('x,y,w,h')";
        }
        final BrowserSession s = this.requireSession(sessionId);
        final String filename = String.format("%04d_px.png", s.seq.incrementAndGet());
        try {
            final Shot shot = s.submit(() -> s.captureInThread(filename, false));
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(shot.bytes()));
            if (image == null) {
                return "Error: failed to decode screenshot bytes";
            }
            return PixelSampler.sampleToJson(image, pts, rectVals);
        } catch (final IOException ex) {
            return "Error sampling pixels: " + ex.getMessage();
        } catch (final RuntimeException ex) {
            log.warn("Sample pixels failed", ex);
            return "Error sampling pixels: " + ex.getMessage();
        }
    }

    // ===== Diagnostics =====

    @McpTool(name = "browser_get_console_logs", description = "Return console logs accumulated since session start (capped at 1000 most recent). "
        + "For LibGDX/teavm apps this includes System.out.println output.")
    public String browserGetConsoleLogs(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        synchronized (s.consoleLogs) {
            if (s.consoleLogs.isEmpty()) return "(no console logs)";
            return String.join("\n", s.consoleLogs);
        }
    }

    @McpTool(name = "browser_get_page_errors", description = "Return uncaught JavaScript / WASM errors accumulated since session start (capped at 1000 most recent).")
    public String browserGetPageErrors(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        synchronized (s.pageErrors) {
            if (s.pageErrors.isEmpty()) return "(no page errors)";
            return String.join("\n", s.pageErrors);
        }
    }

    // ===== Session =====

    @McpTool(name = "browser_reload", description = "Reload the current page.")
    public String browserReload(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        try {
            s.submit(() -> {
                s.page.reload();
                return null;
            });
            return "Reloaded";
        } catch (final RuntimeException ex) {
            return "Error reloading: " + ex.getMessage();
        }
    }

    @McpTool(name = "browser_close_session", description = "Close the browser session for this sessionId (releases Playwright resources, "
        + "stops the Node.js browser process, and removes the sessionId from the registry). "
        + "After this call the sessionId is invalid; call browser_init again to obtain a new one.")
    public String browserCloseSession(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId
    ) {
        final BrowserSession s = this.sessions.remove(sessionId);
        final Project removedProject = this.projectSessionRegistry.remove(sessionId);
        if (s == null && removedProject == null) {
            return "No session to close";
        }
        try {
            if (s != null) {
                s.close();
            }
            this.browserServerService.stopBrowserServer(sessionId);
            return "Session closed";
        } catch (final RuntimeException ex) {
            return "Error closing session: " + ex.getMessage();
        }
    }

    // ===== Helpers =====

    Map<String, BrowserSession> getSessions() {
        return this.sessions;
    }

    /**
     * Runs {@code pageAction} on the session thread; when {@code screenshotAfterMs} is non-null,
     * additionally waits and captures a JPEG screenshot within the SAME executor task so no other
     * tool call can interleave between the action and the capture.
     */
    private CallToolResult actAndMaybeCapture(final BrowserSession s, final Integer screenshotAfterMs,
        final String successText, final Runnable pageAction) {
        final String budgetError = screenshotBudgetError(screenshotAfterMs);
        if (budgetError != null) {
            return errText(budgetError);
        }
        if (screenshotAfterMs == null) {
            s.submit(() -> {
                pageAction.run();
                return null;
            });
            return okText(successText);
        }
        final int waitMs = Math.max(0, screenshotAfterMs);
        final String filename = String.format("%04d.jpg", s.seq.incrementAndGet());
        final Shot shot = s.submit(() -> {
            pageAction.run();
            s.page.waitForTimeout(waitMs);
            return s.captureInThread(filename, true);
        });
        return textAndImage(successText + " (screenshot after " + waitMs + "ms): " + s.returnPathFor(filename), shot);
    }

    /** Returns an error message when screenshotAfterMs would exceed the operation budget, else null. */
    static String screenshotBudgetError(final Integer screenshotAfterMs) {
        if (screenshotAfterMs != null && screenshotAfterMs > MAX_OPERATION_BUDGET_MS) {
            return "Error: screenshotAfterMs (" + screenshotAfterMs
                + "ms) would exceed the 110s operation budget. Reduce it.";
        }
        return null;
    }

    /** 타임라인 결과 공통 조립: 헤더 + 프레임별 경로 텍스트 + 전체 프레임 인라인 이미지. */
    private static CallToolResult buildTimelineResult(final BrowserSession s, final String header,
        final List<Shot> shots) {
        final StringBuilder out = new StringBuilder(header);
        for (final Shot shot : shots) {
            out.append(System.lineSeparator()).append(s.returnPathFor(shot.filename()));
        }
        final CallToolResult.Builder builder = CallToolResult.builder().addTextContent(out.toString());
        for (final Shot shot : shots) {
            builder.addContent(ImageContent.builder(shot.base64(), shot.mimeType()).build());
        }
        return builder.isError(false).build();
    }

    enum RouteAction {FULFILL, ABORT, CONTINUE}

    static RouteAction parseRouteAction(final String action) {
        if (action == null || action.isBlank()) return RouteAction.FULFILL;
        return switch (action.trim().toLowerCase()) {
            case "fulfill" -> RouteAction.FULFILL;
            case "abort" -> RouteAction.ABORT;
            case "continue" -> RouteAction.CONTINUE;
            default -> throw new IllegalArgumentException(
                "unknown route action '" + action + "' (use fulfill, abort, or continue)");
        };
    }

    /**
     * 등록 route당 누적 sleep 예산에서 이번 요청에 허용되는 sleep을 차감해 반환한다.
     * 핸들러는 매칭 요청마다 호출되므로, 예산 없이는 광범위 패턴 + delay 조합이
     * 단일 펌핑 호출(navigate 등)을 N×delay 동안 점유해 submit await(120s)를 넘길 수 있다.
     */
    static long grantRouteDelay(final AtomicLong remaining, final int requested) {
        if (requested <= 0) return 0L;
        final long before = remaining.getAndAdd(-requested);
        return Math.clamp(requested, 0L, before);
    }

    /** "Name: value" 줄 단위 헤더 문자열을 파싱한다. 빈 입력은 빈 맵. */
    static Map<String, String> parseHeaderLines(final String headers) {
        final Map<String, String> map = new LinkedHashMap<>();
        if (headers == null || headers.isBlank()) return map;
        for (final String line : NEWLINE_SPLIT.split(headers)) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            final int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("invalid header line (expected 'Name: value'): " + trimmed);
            }
            map.put(trimmed.substring(0, colon).trim(), trimmed.substring(colon + 1).trim());
        }
        return map;
    }

    /** CDP 기반 도구의 chromium 전용 가드 — chromium이 아니면 에러 메시지, 맞으면 null. */
    static String chromiumGuard(final String browserType) {
        final String type = browserType == null || browserType.isBlank() ? "chromium" : browserType.trim();
        if (!"chromium".equalsIgnoreCase(type)) {
            return "Error: this tool requires browserType=chromium (current: " + type
                + "). CDP network emulation / screencast is unavailable on firefox/webkit; "
                + "browser_set_offline and browser_route work on all browser types.";
        }
        return null;
    }

    /** 탭의 CDP 세션을 lazy 생성/재사용한다. executor 스레드에서만 호출해야 한다. */
    private static CDPSession cdpInThread(final Tab tab) {
        if (tab.cdp == null) {
            tab.cdp = tab.context.newCDPSession(tab.page);
        }
        return tab.cdp;
    }

    /** 위장 파일 모드의 바이트 소스 해석: payloadFilePath/payloadBase64 중 정확히 하나만 허용. */
    static byte[] resolvePayloadBytes(final String payloadFilePath, final String payloadBase64) {
        final boolean hasFile = payloadFilePath != null && !payloadFilePath.isBlank();
        final boolean hasB64 = payloadBase64 != null && !payloadBase64.isBlank();
        if (hasFile == hasB64) {
            throw new IllegalArgumentException(
                "provide exactly one of payloadFilePath or payloadBase64 together with payloadName");
        }
        if (hasFile) {
            final Path p = Paths.get(payloadFilePath.trim()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(p)) {
                throw new IllegalArgumentException("payload source file not found: " + p);
            }
            try {
                return Files.readAllBytes(p);
            } catch (final IOException ex) {
                throw new IllegalArgumentException("failed to read payload file: " + ex.getMessage());
            }
        }
        try {
            return Base64.getDecoder().decode(payloadBase64.trim());
        } catch (final IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid base64 payload: " + ex.getMessage());
        }
    }

    private record RecordedFrame(String filename, long tMillis) {
    }

    /** items에서 최대 max개를 균등 간격으로 샘플링한다 (양 끝 포함). */
    static <T> List<T> sampleEvenly(final List<T> items, final int max) {
        if (items.size() <= max) return items;
        if (max == 1) return List.of(items.getFirst());
        final List<T> result = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            result.add(items.get((int) Math.round((double) i * (items.size() - 1) / (max - 1))));
        }
        return result;
    }

    static boolean wantsJpeg(final String format) {
        return format == null || !"png".equalsIgnoreCase(format.trim());
    }

    static String ext(final boolean jpeg) {
        return jpeg ? "jpg" : "png";
    }

    private static CallToolResult okText(final String text) {
        return CallToolResult.builder().addTextContent(text).isError(false).build();
    }

    private static CallToolResult errText(final String text) {
        return CallToolResult.builder().addTextContent(text).isError(true).build();
    }

    private static CallToolResult textAndImage(final String text, final Shot shot) {
        return CallToolResult.builder()
            .addTextContent(text)
            .addContent(ImageContent.builder(shot.base64(), shot.mimeType()).build())
            .isError(false)
            .build();
    }

    private static WaitUntilState parseWaitUntil(final String s) {
        if (s == null) return WaitUntilState.NETWORKIDLE;
        return switch (s.toLowerCase()) {
            case "load" -> WaitUntilState.LOAD;
            case "domcontentloaded" -> WaitUntilState.DOMCONTENTLOADED;
            case "commit" -> WaitUntilState.COMMIT;
            default -> WaitUntilState.NETWORKIDLE;
        };
    }

    private static final Pattern NEWLINE_SPLIT = Pattern.compile("\\R");

    private static Path[] parseAndValidatePaths(final String filePaths) {
        if (filePaths == null || filePaths.isEmpty()) {
            return new Path[0];
        }
        final String[] raw = NEWLINE_SPLIT.split(filePaths);
        final List<Path> result = new ArrayList<>(raw.length);
        for (final String entry : raw) {
            final String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            final Path p = Paths.get(trimmed).toAbsolutePath().normalize();
            if (!Files.exists(p)) {
                throw new IllegalArgumentException("file not found: " + p);
            }
            if (!Files.isRegularFile(p)) {
                throw new IllegalArgumentException("not a regular file: " + p);
            }
            result.add(p);
        }
        return result.toArray(new Path[0]);
    }

    private static MouseButton parseButton(final String s) {
        if (s == null) return MouseButton.LEFT;
        return switch (s.toLowerCase()) {
            case "right" -> MouseButton.RIGHT;
            case "middle" -> MouseButton.MIDDLE;
            default -> MouseButton.LEFT;
        };
    }

    private static String buildGridOverlayJs(final int gridPx) {
        return "(() => {"
            + "  const g = " + gridPx + ";"
            + "  const id = '__browser_mcp_grid__';"
            + "  const old = document.getElementById(id); if (old) old.remove();"
            + "  const ov = document.createElement('div');"
            + "  ov.id = id;"
            + "  ov.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:2147483647;'"
            + "    + 'background-image:'"
            + "    + 'linear-gradient(to right, rgba(255,0,0,0.3) 1px, transparent 1px),'"
            + "    + 'linear-gradient(to bottom, rgba(255,0,0,0.3) 1px, transparent 1px);'"
            + "    + 'background-size:' + g + 'px ' + g + 'px;';"
            + "  for (let x = 0; x < window.innerWidth; x += g) {"
            + "    for (let y = 0; y < window.innerHeight; y += g) {"
            + "      const lbl = document.createElement('div');"
            + "      lbl.textContent = x + ',' + y;"
            + "      lbl.style.cssText = 'position:absolute;left:' + (x+2) + 'px;top:' + (y+2) + 'px;'"
            + "        + 'font:10px monospace;color:rgba(255,0,0,0.9);background:rgba(255,255,255,0.7);padding:0 2px;';"
            + "      ov.appendChild(lbl);"
            + "    }"
            + "  }"
            + "  document.body.appendChild(ov);"
            + "})()";
    }
}
