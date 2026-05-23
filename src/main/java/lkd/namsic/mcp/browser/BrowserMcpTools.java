package lkd.namsic.mcp.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Keyboard;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.MouseButton;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import lkd.namsic.mcp.config.BrowserProperties;
import lkd.namsic.mcp.session.ProjectSessionRegistry;
import lkd.namsic.mcp.session.ProjectSessionRegistry.Project;
import lkd.namsic.mcp.util.BrowserUrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class BrowserMcpTools {

    private static final String SESSION_PARAM_DESC =
        "Session ID returned by browser_init. Required; obtain it by calling browser_init with a project name first.";

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
            session.context = session.browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(viewportW, viewportH)
                .setDeviceScaleFactor(dpr));
            session.context.setDefaultNavigationTimeout(navTimeout.toMillis());
            session.page = session.context.newPage();
            session.page.onConsoleMessage(msg -> session.appendLog("[" + msg.type() + "] " + msg.text()));
            session.page.onPageError(session::appendError);
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

    @Tool(name = "browser_navigate", description = "Navigate the headed browser tab to a URL. "
        + "Coordinate system is fixed at 1920x1080 (top-left origin). "
        + "For LibGDX/teavm canvas-based apps use waitUntil=networkidle so WASM bundles fully load.")
    public String browserNavigate(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "URL to navigate to (e.g. http://localhost:8080)") final String url,
        @ToolParam(description = "Wait condition: 'load', 'domcontentloaded', 'networkidle', or 'commit'. Default: networkidle") final String waitUntil,
        @ToolParam(description = "Navigation timeout in ms. Default: 60000") final Integer timeoutMs
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
            return "Navigated to " + url;
        } catch (final RuntimeException ex) {
            log.warn("Navigation failed: {}", url, ex);
            return "Error navigating to " + url + ": " + ex.getMessage();
        }
    }

    @Tool(name = "browser_screenshot", description = "Capture the current viewport (1920x1080) and save as PNG. "
        + "Returns the path the LLM CLI can read directly. Use this after each action to verify state changes.")
    public String browserScreenshot(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId
    ) {
        log.info("MCP tool invoked: browser_screenshot sessionId={}", sessionId);
        final BrowserSession s = this.requireSession(sessionId);
        final String filename = String.format("%04d.png", s.seq.incrementAndGet());
        final Path hostFile = s.hostScreenshotDir.resolve(filename);
        try {
            s.submit(() -> {
                s.page.screenshot(new Page.ScreenshotOptions().setPath(hostFile).setFullPage(false));
                return null;
            });
            return s.returnPathFor(filename);
        } catch (final RuntimeException ex) {
            log.warn("Screenshot failed", ex);
            return "Error taking screenshot: " + ex.getMessage();
        }
    }

    @Tool(name = "browser_screenshot_with_grid", description = "Capture screenshot with a coordinate grid overlay. "
        + "Useful for canvas-based UIs to estimate click coordinates more accurately.")
    public String browserScreenshotWithGrid(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "Grid spacing in pixels. Default: 100") final Integer gridPx
    ) {
        log.info("MCP tool invoked: browser_screenshot_with_grid sessionId={} grid={}", sessionId, gridPx);
        final BrowserSession s = this.requireSession(sessionId);
        final int grid = gridPx != null && gridPx > 0 ? gridPx : 100;
        final String filename = String.format("%04d_grid.png", s.seq.incrementAndGet());
        final Path hostFile = s.hostScreenshotDir.resolve(filename);
        final String overlayJs = buildGridOverlayJs(grid);
        try {
            s.submit(() -> {
                s.page.evaluate(overlayJs);
                s.page.screenshot(new Page.ScreenshotOptions().setPath(hostFile).setFullPage(false));
                s.page.evaluate("document.getElementById('__browser_mcp_grid__')?.remove();");
                return null;
            });
            return s.returnPathFor(filename);
        } catch (final RuntimeException ex) {
            log.warn("Grid screenshot failed", ex);
            return "Error taking grid screenshot: " + ex.getMessage();
        }
    }

    @Tool(name = "browser_evaluate", description = "Evaluate a JavaScript expression in the page context and return the serialized result. "
        + "For complex objects, wrap your expression with JSON.stringify(...) for clean output.")
    public String browserEvaluate(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "JavaScript expression to evaluate") final String jsExpression
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

    @Tool(name = "browser_wait", description = "Sleep for the specified milliseconds. Useful for waiting on animations or initial WASM/JS loading.")
    public String browserWait(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "Milliseconds to wait") final Integer ms
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

    @Tool(name = "browser_wait_for_function", description = "Wait until a JavaScript expression returns truthy.")
    public String browserWaitForFunction(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "JS expression that should eventually return truthy") final String jsExpression,
        @ToolParam(description = "Timeout in ms. Default: 10000") final Integer timeoutMs
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

    @Tool(name = "browser_wait_for_frames", description = "Wait for N animation frames to elapse (requestAnimationFrame). "
        + "Useful for canvas games where state updates over multiple frames after an input.")
    public String browserWaitForFrames(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "Number of frames to wait") final Integer n
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

    @Tool(name = "browser_click", description = "Click at viewport coordinates (x, y). "
        + "Coordinate system: 1920x1080 with top-left at (0,0). "
        + "For canvas-based apps (LibGDX/teavm) this is the primary interaction method - DOM selectors are not usable. "
        + "Click on the canvas first to focus it before keyboard input. "
        + "If you need to observe transient UI state that appears briefly after the click "
        + "(toasts, loading indicators, short animations), use browser_click_with_timeline "
        + "instead — it clicks and captures a sequence of frames in one call.")
    public String browserClick(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "X coordinate (0-1919)") final Integer x,
        @ToolParam(description = "Y coordinate (0-1079)") final Integer y,
        @ToolParam(description = "Mouse button: 'left', 'right', 'middle'. Default: left") final String button,
        @ToolParam(description = "Click count (1=single, 2=double). Default: 1") final Integer clickCount
    ) {
        log.info("MCP tool invoked: browser_click ({},{}) sessionId={}", x, y, sessionId);
        final BrowserSession s = this.requireSession(sessionId);
        final MouseButton mb = parseButton(button);
        final int cc = clickCount != null && clickCount > 0 ? clickCount : 1;
        try {
            s.submit(() -> {
                s.page.mouse().click(x, y, new Mouse.ClickOptions().setButton(mb).setClickCount(cc));
                return null;
            });
            return String.format("Clicked at (%d,%d) button=%s count=%d", x, y, mb, cc);
        } catch (final RuntimeException ex) {
            return "Error clicking: " + ex.getMessage();
        }
    }

    @Tool(name = "browser_click_with_timeline", description = "Click at viewport coordinates "
        + "and capture a timeline of screenshots: one frame just before the click (t0), then "
        + "`captureCount` frames each approximately `intervalMs` apart after the click "
        + "(t1..tN). Returns a newline-separated list of PNG paths. Use this instead of "
        + "browser_click + browser_screenshot when you need to observe transient UI (toasts, "
        + "loading indicators, short animations) that a single after-screenshot would miss. "
        + "Actual capture times drift slightly beyond nominal because each screenshot itself "
        + "takes ~100-300ms. Coordinate system: 1920x1080 with top-left origin.")
    public String browserClickWithTimeline(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "X coordinate (0-1919)") final Integer x,
        @ToolParam(description = "Y coordinate (0-1079)") final Integer y,
        @ToolParam(description = "Mouse button: 'left', 'right', 'middle'. Default: left") final String button,
        @ToolParam(description = "Click count (1=single, 2=double). Default: 1") final Integer clickCount,
        @ToolParam(description = "Capture interval in ms. Default: 1000") final Integer intervalMs,
        @ToolParam(description = "Number of captures after the click. Default: 5") final Integer captureCount
    ) {
        final int interval = intervalMs != null && intervalMs > 0 ? intervalMs : 1000;
        final int captures = captureCount != null && captureCount > 0 ? captureCount : 5;
        if ((long) interval * captures > 110_000L) {
            return "Error: total timeline duration would exceed 110s (intervalMs=" + interval
                + " * captureCount=" + captures + "). Reduce either parameter.";
        }
        log.info("MCP tool invoked: browser_click_with_timeline ({},{}) interval={} captures={} sessionId={}",
            x, y, interval, captures, sessionId);
        final BrowserSession s = this.requireSession(sessionId);
        final MouseButton mb = parseButton(button);
        final int cc = clickCount != null && clickCount > 0 ? clickCount : 1;
        final int seq = s.seq.incrementAndGet();
        try {
            final List<String> paths = s.submit(() -> {
                final List<String> collected = new ArrayList<>(captures + 1);
                final String beforeName = String.format("%04d_t0.png", seq);
                s.page.screenshot(new Page.ScreenshotOptions()
                    .setPath(s.hostScreenshotDir.resolve(beforeName)).setFullPage(false));
                collected.add(s.returnPathFor(beforeName));
                s.page.mouse().click(x, y, new Mouse.ClickOptions().setButton(mb).setClickCount(cc));
                for (int i = 1; i <= captures; i++) {
                    s.page.waitForTimeout(interval);
                    final String frameName = String.format("%04d_t%d.png", seq, i);
                    s.page.screenshot(new Page.ScreenshotOptions()
                        .setPath(s.hostScreenshotDir.resolve(frameName)).setFullPage(false));
                    collected.add(s.returnPathFor(frameName));
                }
                return collected;
            });
            final StringBuilder out = new StringBuilder();
            out.append(String.format("Clicked at (%d,%d) button=%s count=%d. %d frames (t0=before, t1..t%d≈+%dms each):%n",
                x, y, mb, cc, paths.size(), captures, interval));
            for (final String p : paths) {
                out.append(p).append(System.lineSeparator());
            }
            return out.toString().trim();
        } catch (final RuntimeException ex) {
            log.warn("Click with timeline failed", ex);
            return "Error click with timeline: " + ex.getMessage();
        }
    }

    @Tool(name = "browser_double_click", description = "Double-click at viewport coordinates.")
    public String browserDoubleClick(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "X coordinate") final Integer x,
        @ToolParam(description = "Y coordinate") final Integer y
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        try {
            s.submit(() -> {
                s.page.mouse().dblclick(x, y);
                return null;
            });
            return String.format("Double-clicked at (%d,%d)", x, y);
        } catch (final RuntimeException ex) {
            return "Error double-clicking: " + ex.getMessage();
        }
    }

    @Tool(name = "browser_drag", description = "Drag from (x1,y1) to (x2,y2). Sends N intermediate move events so canvas drag listeners (e.g. LibGDX) react properly.")
    public String browserDrag(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "Start X") final Integer x1,
        @ToolParam(description = "Start Y") final Integer y1,
        @ToolParam(description = "End X") final Integer x2,
        @ToolParam(description = "End Y") final Integer y2,
        @ToolParam(description = "Number of intermediate move events. Default: 20") final Integer steps
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        final int st = steps != null && steps > 0 ? steps : 20;
        try {
            s.submit(() -> {
                s.page.mouse().move(x1, y1);
                s.page.mouse().down();
                s.page.mouse().move(x2, y2, new Mouse.MoveOptions().setSteps(st));
                s.page.mouse().up();
                return null;
            });
            return String.format("Dragged (%d,%d) -> (%d,%d) steps=%d", x1, y1, x2, y2, st);
        } catch (final RuntimeException ex) {
            return "Error dragging: " + ex.getMessage();
        }
    }

    @Tool(name = "browser_mouse_move", description = "Move the mouse to (x,y) over N steps. Useful for hover or drag mid-motion.")
    public String browserMouseMove(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "X coordinate") final Integer x,
        @ToolParam(description = "Y coordinate") final Integer y,
        @ToolParam(description = "Number of intermediate steps. Default: 1") final Integer steps
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

    @Tool(name = "browser_mouse_down", description = "Press a mouse button at the current position without releasing. Pair with browser_mouse_up.")
    public String browserMouseDown(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "Mouse button: 'left', 'right', 'middle'. Default: left") final String button
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

    @Tool(name = "browser_mouse_up", description = "Release a mouse button.")
    public String browserMouseUp(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "Mouse button: 'left', 'right', 'middle'. Default: left") final String button
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

    @Tool(name = "browser_wheel", description = "Send a wheel/scroll event at the current mouse position.")
    public String browserWheel(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "Horizontal scroll delta in pixels") final Integer deltaX,
        @ToolParam(description = "Vertical scroll delta in pixels (positive=down)") final Integer deltaY
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        final int dx = deltaX != null ? deltaX : 0;
        final int dy = deltaY != null ? deltaY : 0;
        try {
            s.submit(() -> {
                s.page.mouse().wheel(dx, dy);
                return null;
            });
            return String.format("Scrolled (%d,%d)", dx, dy);
        } catch (final RuntimeException ex) {
            return "Error scrolling: " + ex.getMessage();
        }
    }

    // ===== Keyboard =====

    @Tool(name = "browser_type", description = "Type text into the focused element. "
        + "For canvas apps, call browser_click on the canvas first to give it focus.")
    public String browserType(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "Text to type") final String text,
        @ToolParam(description = "Delay between keystrokes in ms. Default: 10") final Integer delayMs
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        final int d = delayMs != null && delayMs >= 0 ? delayMs : 10;
        try {
            s.submit(() -> {
                s.page.keyboard().type(text, new Keyboard.TypeOptions().setDelay(d));
                return null;
            });
            return "Typed " + text.length() + " chars";
        } catch (final RuntimeException ex) {
            return "Error typing: " + ex.getMessage();
        }
    }

    @Tool(name = "browser_press_key", description = "Press and release a key. Examples: 'Enter', 'Escape', 'ArrowLeft', 'Space', 'Tab', 'Control+a'.")
    public String browserPressKey(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "Key name (Playwright key syntax)") final String key
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        try {
            s.submit(() -> {
                s.page.keyboard().press(key);
                return null;
            });
            return "Pressed " + key;
        } catch (final RuntimeException ex) {
            return "Error pressing key: " + ex.getMessage();
        }
    }

    @Tool(name = "browser_key_down", description = "Hold a key down without releasing. Pair with browser_key_up. "
        + "Useful for game movement keys (e.g. holding ArrowRight to move).")
    public String browserKeyDown(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "Key name") final String key
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

    @Tool(name = "browser_key_up", description = "Release a held key.")
    public String browserKeyUp(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @ToolParam(description = "Key name") final String key
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

    // ===== Diagnostics =====

    @Tool(name = "browser_get_console_logs", description = "Return console logs accumulated since session start (capped at 1000 most recent). "
        + "For LibGDX/teavm apps this includes System.out.println output.")
    public String browserGetConsoleLogs(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        synchronized (s.consoleLogs) {
            if (s.consoleLogs.isEmpty()) return "(no console logs)";
            return String.join("\n", s.consoleLogs);
        }
    }

    @Tool(name = "browser_get_page_errors", description = "Return uncaught JavaScript / WASM errors accumulated since session start (capped at 1000 most recent).")
    public String browserGetPageErrors(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId
    ) {
        final BrowserSession s = this.requireSession(sessionId);
        synchronized (s.pageErrors) {
            if (s.pageErrors.isEmpty()) return "(no page errors)";
            return String.join("\n", s.pageErrors);
        }
    }

    // ===== Session =====

    @Tool(name = "browser_reload", description = "Reload the current page.")
    public String browserReload(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId
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

    @Tool(name = "browser_close_session", description = "Close the browser session for this sessionId (releases Playwright resources, "
        + "stops the Node.js browser process, and removes the sessionId from the registry). "
        + "After this call the sessionId is invalid; call browser_init again to obtain a new one.")
    public String browserCloseSession(
        @ToolParam(description = SESSION_PARAM_DESC) final String sessionId
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

    private static WaitUntilState parseWaitUntil(final String s) {
        if (s == null) return WaitUntilState.NETWORKIDLE;
        return switch (s.toLowerCase()) {
            case "load" -> WaitUntilState.LOAD;
            case "domcontentloaded" -> WaitUntilState.DOMCONTENTLOADED;
            case "commit" -> WaitUntilState.COMMIT;
            default -> WaitUntilState.NETWORKIDLE;
        };
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
