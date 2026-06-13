package lkd.namsic.mcp.android;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import jakarta.annotation.PreDestroy;
import lkd.namsic.mcp.android.AndroidDeviceService.DeviceInfo;
import lkd.namsic.mcp.android.AndroidDeviceService.ScreenshotResult;
import lkd.namsic.mcp.android.AndroidUiParser.ParseResult;
import lkd.namsic.mcp.config.AndroidProperties;
import lkd.namsic.mcp.session.ProjectSessionRegistry;
import lkd.namsic.mcp.session.ProjectSessionRegistry.Project;
import lkd.namsic.mcp.util.PixelSampler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 네이티브 안드로이드 앱 테스트 MCP 도구 (순수 ADB 직접 제어).
 * BrowserMcpTools와 동일하게 @McpTool 어노테이션 스캐너로 자동 등록된다 —
 * McpToolConfig.toolObjects에 넣으면 이중 등록 충돌이 나므로 절대 추가하지 말 것.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AndroidMcpTools {

    private static final String SESSION_PARAM_DESC =
        "Session ID returned by project_init. Required; obtain it by calling project_init with a project name first.";

    private static final long MAX_OPERATION_BUDGET_MS = 110_000L;
    /** screenrecord 자체의 디바이스 측 한계 (3분). */
    private static final int MAX_RECORD_DURATION_MS = 180_000;
    /** capture_timeline 예산 계산에 쓰는 프레임당 screencap 추정 오버헤드. */
    private static final long CAPTURE_OVERHEAD_MS = 1_000L;

    private static final String SCREENSHOT_AFTER_DESC =
        "Optional. If set (>=0, max 110000), waits this many ms after the action, then captures a JPEG screenshot "
            + "returned inline plus its saved file path. Prefer this over a separate android_screenshot "
            + "call to verify the action result in a single round trip. Omit for no screenshot.";

    private static final String TARGET_PARAM_NOTE =
        "Targeting priority: (1) selector — substring match against the last android_dump_ui's "
            + "resource-id/text/content-desc; (2) elementIndex — the [index] from the last dump; "
            + "(3) raw x,y device pixels. Prefer selector/elementIndex (call android_dump_ui first).";

    private final AndroidDeviceService deviceService;
    private final ProjectSessionRegistry projectSessionRegistry;
    private final AndroidProperties properties;

    private final Map<String, AndroidSession> sessions = new ConcurrentHashMap<>();

    private AndroidSession requireSession(final String sid) {
        this.projectSessionRegistry.require(sid);
        final AndroidSession s = this.sessions.get(sid);
        if (s == null) {
            throw new IllegalStateException(
                "No Android session for sessionId=" + sid + ". Call android_use_device first.");
        }
        return s;
    }

    @PreDestroy
    public void destroy() {
        log.info("Closing all Android sessions (count={})", this.sessions.size());
        this.sessions.forEach((id, s) -> {
            try {
                this.restoreAllImesQuietly(s);
                s.close();
            } catch (final Exception e) {
                log.warn("Failed to close Android session for {}", id, e);
            }
        });
        this.sessions.clear();
    }

    // ===== 기기 선택 =====

    @McpTool(name = "android_list_devices", description = "List Android devices and emulators visible to adb "
        + "(adb devices -l): serial, state (device/offline/unauthorized), and model. "
        + "'unauthorized' means the RSA debugging prompt must be approved on the device screen. "
        + "Call this first, then android_use_device with the chosen serial. "
        + "No other android_* tool works until a device is selected.")
    public String androidListDevices(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId
    ) {
        log.info("MCP tool invoked: android_list_devices sessionId={}", sessionId);
        try {
            this.projectSessionRegistry.require(sessionId);
            final List<DeviceInfo> devices = this.deviceService.listDevices();
            if (devices.isEmpty()) {
                return "No devices connected. Start an emulator (e.g. from Android Studio) "
                    + "or connect a device with USB debugging enabled, then call this again.";
            }
            final StringBuilder sb = new StringBuilder("Connected devices:\n");
            for (final DeviceInfo device : devices) {
                sb.append("- ").append(device.describe()).append('\n');
            }
            sb.append("Select one with android_use_device.");
            return sb.toString();
        } catch (final RuntimeException ex) {
            return "Error listing devices: " + ex.getMessage();
        }
    }

    @McpTool(name = "android_use_device", description = "Select the target device/emulator for this session and "
        + "pin all subsequent android_* tools to it (adb -s <serial>). Omit serial to auto-select when exactly "
        + "one ready device is connected. Records the device's current keyboard (IME) so it can be restored "
        + "on android_close_session. Must be called before any interaction tool. "
        + "Each device's state (UI-dump cache, IME) is tracked separately, so you can switch back and forth "
        + "between two devices (e.g. two test accounts on two emulators) without losing context — the "
        + "browser-side analogue of browser_switch_tab.")
    public String androidUseDevice(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Device serial from android_list_devices (e.g. 'emulator-5554'). "
            + "Optional — omit to auto-select the single connected device.", required = false) final String serial
    ) {
        log.info("MCP tool invoked: android_use_device serial={} sessionId={}", serial, sessionId);
        final Project project;
        try {
            project = this.projectSessionRegistry.require(sessionId);
        } catch (final IllegalStateException ex) {
            return "Error: " + ex.getMessage();
        }
        try {
            final DeviceInfo device = this.deviceService.resolveDevice(serial);
            final AndroidSession session = this.sessions.computeIfAbsent(sessionId,
                sid -> new AndroidSession(sid, project.screenshotDir()));
            session.serial = device.serial();
            // 기기별 상태는 보존된다 — 처음 보는 기기일 때만 기본 IME를 기록해
            // 같은 기기 재바인딩 시 originalIme에 ADBKeyboard 자신이 덮어써지는 것을 막는다.
            // 빈 문자열은 currentIme의 읽기 실패 sentinel이므로 "미기록"으로 취급해
            // 이후 재바인딩에서 자가 교정되게 한다 (영구 '' 고정 → ime reset 복원 방지).
            final AndroidSession.DeviceState state = session.stateOf(device.serial());
            if (state.originalIme == null || state.originalIme.isBlank()) {
                state.originalIme = session.submit(() -> this.deviceService.currentIme(device.serial()));
            }
            log.info("Android session bound: sessionId={} device={}", sessionId, device.describe());
            return "Selected device " + device.describe()
                + ". All android_* tools in this session now target it. "
                + "Next: android_launch_app or android_dump_ui / android_screenshot to inspect the screen.";
        } catch (final RuntimeException ex) {
            return "Error selecting device: " + ex.getMessage();
        }
    }

    // ===== 앱 관리 =====

    @McpTool(name = "android_install_app", description = "Install an APK from a host absolute path onto the "
        + "selected device (adb install). reinstall=true (default) keeps existing app data (-r). "
        + "Returns adb's install output on success.")
    public String androidInstallApp(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Absolute host path to the APK file") final String apkPath,
        @McpToolParam(description = "Keep app data if already installed (-r). Default: true",
            required = false) final Boolean reinstall
    ) {
        log.info("MCP tool invoked: android_install_app apkPath={} sessionId={}", apkPath, sessionId);
        final AndroidSession s = this.requireSession(sessionId);
        final Path apk = Paths.get(apkPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(apk)) {
            return "Error: APK file not found: " + apk;
        }
        final boolean keepData = reinstall == null || reinstall;
        try {
            final String output = s.submit(() -> this.deviceService.install(s.serial, apk, keepData));
            return "Installed " + apk.getFileName() + ": " + output.trim();
        } catch (final RuntimeException ex) {
            return "Error installing app: " + ex.getMessage();
        }
    }

    @McpTool(name = "android_launch_app", description = "Launch an installed app by package name. Optionally pass "
        + "an activity ('.MainActivity' or fully-qualified) to open a specific screen; otherwise the launcher "
        + "activity is started. After launching, call android_dump_ui or android_screenshot to inspect the screen.")
    public String androidLaunchApp(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Package name (e.g. 'com.example.app')") final String packageName,
        @McpToolParam(description = "Activity to start (e.g. '.MainActivity'). Optional — omit to use "
            + "the launcher activity.", required = false) final String activity
    ) {
        log.info("MCP tool invoked: android_launch_app package={} activity={} sessionId={}",
            packageName, activity, sessionId);
        final AndroidSession s = this.requireSession(sessionId);
        try {
            final String output = s.submit(() -> this.deviceService.launchApp(s.serial, packageName, activity));
            s.activeState().lastDump = null;
            return "Launched " + packageName + (output.isBlank() ? "" : ": " + output.trim());
        } catch (final RuntimeException ex) {
            return "Error launching app: " + ex.getMessage();
        }
    }

    @McpTool(name = "android_stop_app", description = "Force-stop an app by package name (am force-stop). "
        + "Use to reset app state before re-launching.")
    public String androidStopApp(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Package name to stop") final String packageName
    ) {
        log.info("MCP tool invoked: android_stop_app package={} sessionId={}", packageName, sessionId);
        final AndroidSession s = this.requireSession(sessionId);
        try {
            s.submit(() -> {
                this.deviceService.stopApp(s.serial, packageName);
                return null;
            });
            return "Stopped " + packageName;
        } catch (final RuntimeException ex) {
            return "Error stopping app: " + ex.getMessage();
        }
    }

    @McpTool(name = "android_list_packages", description = "List installed packages on the device "
        + "(pm list packages). Use thirdPartyOnly=true to hide system packages, and nameFilter to search "
        + "by package-name substring.")
    public String androidListPackages(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Only third-party (user-installed) packages. Default: false",
            required = false) final Boolean thirdPartyOnly,
        @McpToolParam(description = "Package-name substring filter (e.g. 'example')",
            required = false) final String nameFilter
    ) {
        log.info("MCP tool invoked: android_list_packages sessionId={}", sessionId);
        final AndroidSession s = this.requireSession(sessionId);
        try {
            final String output = s.submit(() -> this.deviceService.listPackages(
                s.serial, Boolean.TRUE.equals(thirdPartyOnly), nameFilter));
            return output.isBlank() ? "(no matching packages)" : output;
        } catch (final RuntimeException ex) {
            return "Error listing packages: " + ex.getMessage();
        }
    }

    // ===== 화면 인식 =====

    @McpTool(name = "android_dump_ui", description = "Dump the current screen's UI hierarchy (uiautomator) as a "
        + "compact element list: each line shows [index], text, resource-id, content-desc, class, clickable, "
        + "bounds and center coordinates in device pixels. Use the index or an id/text/desc substring with "
        + "android_tap to interact. The result is cached, so android_tap's selector/elementIndex always targets "
        + "the most recent dump — re-run this after the screen changes. Retries automatically when the screen "
        + "is not idle. WebView/Flutter/game surfaces may expose no elements; fall back to android_screenshot "
        + "plus raw-coordinate taps in that case.")
    public String androidDumpUi(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Maximum number of elements to return. Default: 200",
            required = false) final Integer maxNodes
    ) {
        log.info("MCP tool invoked: android_dump_ui sessionId={}", sessionId);
        final AndroidSession s = this.requireSession(sessionId);
        final int max = maxNodes != null && maxNodes > 0 ? maxNodes : this.properties.maxUiNodes();
        try {
            final ParseResult result = s.submit(() -> this.deviceService.dumpUi(s.serial, max));
            s.activeState().lastDump = result.nodes();
            return AndroidUiParser.toCompactList(result);
        } catch (final RuntimeException ex) {
            return "Error dumping UI: " + ex.getMessage();
        }
    }

    // ===== 조작 =====

    @McpTool(name = "android_tap", description = "Tap an element or coordinate on the device screen. "
        + TARGET_PARAM_NOTE + " "
        + "Set screenshotAfterMs to verify the result inline in one round trip. "
        + "A tap may change the screen — re-run android_dump_ui before reusing selector/elementIndex, "
        + "as cached element coordinates go stale.")
    public CallToolResult androidTap(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Substring matched against resource-id/text/content-desc of the last "
            + "android_dump_ui (case-insensitive). Takes priority over elementIndex and x,y.",
            required = false) final String selector,
        @McpToolParam(description = "Element [index] from the last android_dump_ui",
            required = false) final Integer elementIndex,
        @McpToolParam(description = "X coordinate in device pixels (used with y when no selector/elementIndex)",
            required = false) final Integer x,
        @McpToolParam(description = "Y coordinate in device pixels",
            required = false) final Integer y,
        @McpToolParam(description = SCREENSHOT_AFTER_DESC, required = false) final Integer screenshotAfterMs
    ) {
        log.info("MCP tool invoked: android_tap selector={} index={} xy=({},{}) sessionId={}",
            selector, elementIndex, x, y, sessionId);
        final AndroidSession s = this.requireSession(sessionId);
        try {
            final TapTarget target = resolveTarget(s, selector, elementIndex, x, y);
            return this.actAndMaybeCapture(s, screenshotAfterMs,
                String.format("Tapped %s at (%d,%d)", target.description(), target.x(), target.y()),
                () -> this.deviceService.tap(s.serial, target.x(), target.y()));
        } catch (final RuntimeException ex) {
            return errText("Error tapping: " + ex.getMessage());
        }
    }

    @McpTool(name = "android_long_press", description = "Long-press an element or coordinate "
        + "(input swipe with identical start/end points). " + TARGET_PARAM_NOTE + " "
        + "A long-press may change the screen (context menus etc.) — re-run android_dump_ui before "
        + "reusing selector/elementIndex.")
    public CallToolResult androidLongPress(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Substring matched against resource-id/text/content-desc of the last "
            + "android_dump_ui (case-insensitive)", required = false) final String selector,
        @McpToolParam(description = "Element [index] from the last android_dump_ui",
            required = false) final Integer elementIndex,
        @McpToolParam(description = "X coordinate in device pixels", required = false) final Integer x,
        @McpToolParam(description = "Y coordinate in device pixels", required = false) final Integer y,
        @McpToolParam(description = "Press duration in ms. Default: 800", required = false) final Integer durationMs,
        @McpToolParam(description = SCREENSHOT_AFTER_DESC, required = false) final Integer screenshotAfterMs
    ) {
        log.info("MCP tool invoked: android_long_press selector={} index={} xy=({},{}) sessionId={}",
            selector, elementIndex, x, y, sessionId);
        final AndroidSession s = this.requireSession(sessionId);
        final int duration = durationMs != null && durationMs > 0 ? durationMs : 800;
        try {
            final TapTarget target = resolveTarget(s, selector, elementIndex, x, y);
            return this.actAndMaybeCapture(s, screenshotAfterMs,
                String.format("Long-pressed %s at (%d,%d) for %dms",
                    target.description(), target.x(), target.y(), duration),
                () -> this.deviceService.swipe(s.serial, target.x(), target.y(), target.x(), target.y(), duration));
        } catch (final RuntimeException ex) {
            return errText("Error long-pressing: " + ex.getMessage());
        }
    }

    @McpTool(name = "android_swipe", description = "Swipe from (x1,y1) to (x2,y2) in device pixels — use for "
        + "scrolling (swipe up to scroll down), page transitions, and drags. Larger durationMs = slower drag; "
        + "short duration = fling. After scrolling, re-run android_dump_ui — cached element coordinates "
        + "are stale.")
    public CallToolResult androidSwipe(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Start X in device pixels") final Integer x1,
        @McpToolParam(description = "Start Y in device pixels") final Integer y1,
        @McpToolParam(description = "End X in device pixels") final Integer x2,
        @McpToolParam(description = "End Y in device pixels") final Integer y2,
        @McpToolParam(description = "Swipe duration in ms. Default: 300", required = false) final Integer durationMs,
        @McpToolParam(description = SCREENSHOT_AFTER_DESC, required = false) final Integer screenshotAfterMs
    ) {
        log.info("MCP tool invoked: android_swipe ({},{})->({},{}) sessionId={}", x1, y1, x2, y2, sessionId);
        final AndroidSession s = this.requireSession(sessionId);
        final int duration = durationMs != null && durationMs > 0 ? durationMs : 300;
        try {
            return this.actAndMaybeCapture(s, screenshotAfterMs,
                String.format("Swiped (%d,%d) -> (%d,%d) over %dms", x1, y1, x2, y2, duration),
                () -> this.deviceService.swipe(s.serial, x1, y1, x2, y2, duration));
        } catch (final RuntimeException ex) {
            return errText("Error swiping: " + ex.getMessage());
        }
    }

    @McpTool(name = "android_press_key", description = "Press a hardware/navigation key (input keyevent). "
        + "Accepts a key name (HOME, BACK, ENTER, DEL, TAB, APP_SWITCH, POWER, VOLUME_UP, ...) or a numeric "
        + "keycode. Note: if the on-screen keyboard is open, the first BACK only closes the keyboard. "
        + "Keys like BACK/HOME change the screen — re-run android_dump_ui before reusing "
        + "selector/elementIndex.")
    public CallToolResult androidPressKey(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Key name (e.g. 'BACK', 'HOME', 'ENTER') or numeric keycode "
            + "(e.g. '4')") final String key,
        @McpToolParam(description = SCREENSHOT_AFTER_DESC, required = false) final Integer screenshotAfterMs
    ) {
        log.info("MCP tool invoked: android_press_key key={} sessionId={}", key, sessionId);
        final AndroidSession s = this.requireSession(sessionId);
        final String keycode = AndroidDeviceService.normalizeKeycode(key);
        try {
            return this.actAndMaybeCapture(s, screenshotAfterMs,
                "Pressed " + keycode,
                () -> this.deviceService.pressKey(s.serial, keycode));
        } catch (final RuntimeException ex) {
            return errText("Error pressing key: " + ex.getMessage());
        }
    }

    @McpTool(name = "android_input_text", description = "Type text into the currently focused input field. "
        + "Tap the target field with android_tap first to focus it. Plain ASCII goes via 'adb shell input text'; "
        + "non-ASCII (e.g. Korean) and special characters are sent through the ADBKeyboard IME "
        + "(auto-installed from app.android.adb-keyboard-apk when configured). The device's original keyboard "
        + "is restored on android_close_session. The IME broadcast cannot confirm the text actually landed — "
        + "verify with screenshotAfterMs or android_dump_ui.")
    public CallToolResult androidInputText(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Text to type") final String text,
        @McpToolParam(description = SCREENSHOT_AFTER_DESC, required = false) final Integer screenshotAfterMs
    ) {
        log.info("MCP tool invoked: android_input_text length={} sessionId={}",
            text == null ? 0 : text.length(), sessionId);
        final AndroidSession s = this.requireSession(sessionId);
        if (text == null || text.isEmpty()) {
            return errText("Error: text is required");
        }
        final boolean safeAscii = AndroidDeviceService.isSafeAsciiText(text);
        final String successText = safeAscii
            ? "Typed " + text.length() + " chars"
            : "Typed " + text.length() + " chars via ADBKeyboard IME "
                + "(original keyboard is restored on android_close_session)";
        try {
            return this.actAndMaybeCapture(s, screenshotAfterMs, successText, () -> {
                if (safeAscii) {
                    this.deviceService.inputAsciiText(s.serial, text);
                    return;
                }
                final AndroidSession.DeviceState state = s.activeState();
                if (!state.adbKeyboardActive) {
                    this.deviceService.ensureAdbKeyboardActive(s.serial);
                    state.adbKeyboardActive = true;
                }
                this.deviceService.inputUnicodeViaKeyboard(s.serial, text);
            });
        } catch (final RuntimeException ex) {
            return errText("Error typing text: " + ex.getMessage());
        }
    }

    // ===== 스크린샷 / 진단 =====

    @McpTool(name = "android_screenshot", description = "Capture the device screen (adb exec-out screencap) and "
        + "save it under the project screenshot dir. Returns the image inline (no separate file read needed) "
        + "plus the saved file path. Defaults to JPEG q80 downscaled to fit the vision-input budget; the "
        + "response states the device-to-image scale — divide image coordinates by the scale for tap "
        + "coordinates. Pass format=png for the lossless full-resolution original. To verify an action result, "
        + "prefer the screenshotAfterMs parameter on the action tool itself.")
    public CallToolResult androidScreenshot(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Image format: 'jpeg' (default, downscaled, much smaller) or "
            + "'png' (lossless full resolution).", required = false) final String format
    ) {
        log.info("MCP tool invoked: android_screenshot sessionId={} format={}", sessionId, format);
        final AndroidSession s = this.requireSession(sessionId);
        final boolean jpeg = format == null || !"png".equalsIgnoreCase(format.trim());
        final String filename = String.format("%04d.%s", s.seq.incrementAndGet(), jpeg ? "jpg" : "png");
        try {
            final ScreenshotResult shot = s.submit(() ->
                this.deviceService.screenshot(s.serial, s.hostScreenshotDir, filename, jpeg));
            return textAndImage(s.returnPathFor(filename) + " (" + shot.scaleSummary() + ")", shot);
        } catch (final RuntimeException ex) {
            log.warn("Android screenshot failed", ex);
            return errText("Error taking screenshot: " + ex.getMessage());
        }
    }

    @McpTool(name = "android_logcat", description = "Dump recent device logs (logcat -d -v threadtime). "
        + "Default filter '*:E' shows errors only — use e.g. 'MyTag:D *:S' to follow one tag, or '*:W' for "
        + "warnings and above. Set clearFirst=true to clear the buffer (then reproduce the issue and call "
        + "again). Use after a crash or unexpected behavior to diagnose.")
    public String androidLogcat(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Logcat filter spec. Default: '*:E' (errors only). "
            + "Example: 'ActivityManager:I MyApp:D *:S'", required = false) final String filterSpec,
        @McpToolParam(description = "Maximum number of recent lines. Default: 200, max: 1000",
            required = false) final Integer tailLines,
        @McpToolParam(description = "Clear the log buffer instead of reading (call again afterwards). "
            + "Default: false", required = false) final Boolean clearFirst
    ) {
        log.info("MCP tool invoked: android_logcat sessionId={}", sessionId);
        final AndroidSession s = this.requireSession(sessionId);
        final String spec = filterSpec != null && !filterSpec.isBlank() ? filterSpec : "*:E";
        final int tail = tailLines != null && tailLines > 0 ? Math.min(tailLines, 1_000) : 200;
        try {
            return s.submit(() ->
                this.deviceService.logcat(s.serial, spec, tail, Boolean.TRUE.equals(clearFirst)));
        } catch (final RuntimeException ex) {
            return "Error reading logcat: " + ex.getMessage();
        }
    }

    // ===== 네트워크 제어 =====

    @McpTool(name = "android_set_offline", description = "Toggle device connectivity by enabling/disabling BOTH "
        + "wifi and mobile data (svc wifi/data). Works on USB-connected physical devices and emulators. "
        + "NOT available over a wireless adb connection (serial like '192.168.0.10:5555' or an '_adb-tls-connect' "
        + "mDNS name) — disabling wifi would sever the adb channel and brick the session; the call is rejected. "
        + "Use to test offline guards, heartbeat loss, and reconnect flows. Pass offline=false to restore "
        + "connectivity. Browser-side equivalent: browser_set_offline.")
    public String androidSetOffline(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "true = go offline (wifi+data off), false = back online") final Boolean offline
    ) {
        log.info("MCP tool invoked: android_set_offline offline={} sessionId={}", offline, sessionId);
        final AndroidSession s = this.requireSession(sessionId);
        final boolean off = Boolean.TRUE.equals(offline);
        try {
            s.submit(() -> {
                this.deviceService.setOffline(s.serial, off);
                return null;
            });
            return off
                ? "Device is now offline (wifi + mobile data disabled)"
                : "Device connectivity restored (wifi + mobile data enabled)";
        } catch (final RuntimeException ex) {
            return "Error toggling offline mode: " + ex.getMessage();
        }
    }

    @McpTool(name = "android_set_network_conditions", description = "Emulate network speed/latency via the "
        + "emulator console (adb emu network speed/delay). EMULATOR ONLY — fails on physical devices "
        + "(serial must start with 'emulator-'). speed: 'full' (reset), 'gsm', 'edge', '3g', 'lte', or "
        + "'up:down' in kbps (e.g. '100:100'). delay: 'none' (reset), 'gprs', 'edge', or 'min:max' in ms "
        + "(e.g. '500:1000'). Use to observe loading spinners and transient states that vanish at full speed. "
        + "Reset with speed='full' delay='none'. Browser-side equivalent: browser_set_network_conditions.")
    public String androidSetNetworkConditions(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Network speed: 'full', 'gsm', 'edge', '3g', 'lte', or 'up:down' kbps",
            required = false) final String speed,
        @McpToolParam(description = "Network delay: 'none', 'gprs', 'edge', or 'min:max' ms",
            required = false) final String delay
    ) {
        log.info("MCP tool invoked: android_set_network_conditions speed={} delay={} sessionId={}",
            speed, delay, sessionId);
        final AndroidSession s = this.requireSession(sessionId);
        try {
            s.submit(() -> {
                this.deviceService.setNetworkConditions(s.serial, speed, delay);
                return null;
            });
            return "Network conditions applied"
                + (speed != null && !speed.isBlank() ? " speed=" + speed.trim() : "")
                + (delay != null && !delay.isBlank() ? " delay=" + delay.trim() : "")
                + ". Reset with speed='full' delay='none'.";
        } catch (final RuntimeException ex) {
            return "Error setting network conditions: " + ex.getMessage();
        }
    }

    @McpTool(name = "android_set_http_proxy", description = "Set or clear the device-wide HTTP proxy "
        + "(settings put global http_proxy). Point it at an external intercepting proxy (e.g. mitmproxy on the "
        + "host) to inspect or mock API responses — this tool only configures the device; running the proxy is "
        + "the caller's responsibility. Omit hostPort (or pass ':0') to clear. From an emulator the host "
        + "machine is reachable as 10.0.2.2. CAVEATS: apps using their own HTTP stack can ignore the global "
        + "proxy, and HTTPS interception additionally requires the proxy CA to be trusted on the device "
        + "(user-added CAs are distrusted by default since API 24). There is no pure-ADB equivalent of "
        + "browser_route — request mocking on Android always needs an external proxy.")
    public String androidSetHttpProxy(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Proxy as 'host:port' (e.g. '10.0.2.2:8888'). Omit or ':0' to clear.",
            required = false) final String hostPort
    ) {
        log.info("MCP tool invoked: android_set_http_proxy hostPort={} sessionId={}", hostPort, sessionId);
        final AndroidSession s = this.requireSession(sessionId);
        final boolean clearing = hostPort == null || hostPort.isBlank() || ":0".equals(hostPort.trim());
        try {
            s.submit(() -> {
                this.deviceService.setHttpProxy(s.serial, hostPort);
                return null;
            });
            return clearing
                ? "Global HTTP proxy cleared"
                : "Global HTTP proxy set to " + hostPort.trim()
                    + " (make sure the proxy is running and reachable from the device)";
        } catch (final RuntimeException ex) {
            return "Error setting HTTP proxy: " + ex.getMessage();
        }
    }

    // ===== 파일 푸시 =====

    @McpTool(name = "android_push_file", description = "Push a host file onto the device (adb push) — e.g. a "
        + "test image/audio file for upload and file-picker scenarios. scanMedia=true (default) broadcasts "
        + "MEDIA_SCANNER_SCAN_FILE afterwards so gallery/picker apps see the file immediately. "
        + "Browser-side equivalent: browser_set_input_files (the browser injects directly; on Android the file "
        + "must be on-device first, then picked through the app's own UI).")
    public String androidPushFile(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Absolute host path of the file to push") final String localPath,
        @McpToolParam(description = "Absolute device destination path, e.g. '/sdcard/Download/test.png'") final String devicePath,
        @McpToolParam(description = "Broadcast a media-scan so gallery apps index the file. Default: true",
            required = false) final Boolean scanMedia
    ) {
        log.info("MCP tool invoked: android_push_file local={} device={} sessionId={}",
            localPath, devicePath, sessionId);
        final AndroidSession s = this.requireSession(sessionId);
        if (localPath == null || localPath.isBlank()) {
            return "Error: localPath is required";
        }
        final Path local = Paths.get(localPath.trim()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(local)) {
            return "Error: local file not found: " + local;
        }
        if (devicePath == null || devicePath.isBlank() || !devicePath.trim().startsWith("/")) {
            return "Error: devicePath must be an absolute device path (e.g. /sdcard/Download/test.png)";
        }
        final String target = devicePath.trim();
        final boolean scan = scanMedia == null || scanMedia;
        try {
            final String output = s.submit(() -> this.deviceService.pushFile(s.serial, local, target, scan));
            return "Pushed " + local.getFileName() + " -> " + target
                + (scan ? " (media scan broadcast sent)" : "")
                + (output.isBlank() ? "" : ": " + output);
        } catch (final RuntimeException ex) {
            return "Error pushing file: " + ex.getMessage();
        }
    }

    // ===== 연속 캡처 / 녹화 / 픽셀 =====

    @McpTool(name = "android_capture_timeline", description = "Capture a sequence of screenshots WITHOUT any "
        + "input: f0 immediately, then `count-1` more frames ≈`intervalMs` apart, all returned inline plus "
        + "saved file paths. Use to observe ongoing animations (spinners, toasts fading, transitions) that a "
        + "single screenshot misses. Each screencap itself takes several hundred ms on-device, so actual frame "
        + "spacing drifts beyond nominal — treat intervalMs as a lower bound. "
        + "Browser-side equivalent: browser_capture_timeline.")
    public CallToolResult androidCaptureTimeline(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Capture interval in ms. Default: 1000", required = false) final Integer intervalMs,
        @McpToolParam(description = "Total number of frames. Default: 5", required = false) final Integer count
    ) {
        final int interval = intervalMs != null && intervalMs > 0 ? intervalMs : 1000;
        final int captures = count != null && count > 0 ? count : 5;
        if ((interval + CAPTURE_OVERHEAD_MS) * captures > MAX_OPERATION_BUDGET_MS) {
            return errText("Error: total timeline duration (including ~" + CAPTURE_OVERHEAD_MS
                + "ms screencap overhead per frame) would exceed 110s (intervalMs=" + interval
                + " * count=" + captures + "). Reduce either parameter.");
        }
        log.info("MCP tool invoked: android_capture_timeline interval={} count={} sessionId={}",
            interval, captures, sessionId);
        final AndroidSession s = this.requireSession(sessionId);
        final int seq = s.seq.incrementAndGet();
        try {
            final List<ScreenshotResult> shots = s.submit(() -> {
                final List<ScreenshotResult> collected = new ArrayList<>(captures);
                for (int i = 0; i < captures; i++) {
                    if (i > 0) {
                        Thread.sleep(interval);
                    }
                    collected.add(this.deviceService.screenshot(s.serial, s.hostScreenshotDir,
                        String.format("%04d_f%d.jpg", seq, i), true));
                }
                return collected;
            });
            final StringBuilder out = new StringBuilder(String.format(
                "%d frames captured (f0=immediate, then ≈+%dms each; %s):",
                shots.size(), interval, shots.getFirst().scaleSummary()));
            for (final ScreenshotResult shot : shots) {
                out.append(System.lineSeparator()).append(s.returnPathFor(shot.filename()));
            }
            final CallToolResult.Builder builder = CallToolResult.builder().addTextContent(out.toString());
            for (final ScreenshotResult shot : shots) {
                builder.addContent(ImageContent.builder(shot.base64(), shot.mimeType()).build());
            }
            return builder.isError(false).build();
        } catch (final RuntimeException ex) {
            log.warn("Android capture timeline failed", ex);
            return errText("Error capturing timeline: " + ex.getMessage());
        }
    }

    @McpTool(name = "android_record_screen", description = "Record the device screen to an MP4 file "
        + "(screenrecord), pull it to the host, and return the saved file path. durationMs max 180000 "
        + "(screenrecord's own limit). NOTE: the MP4 cannot be analyzed inline by the model — prefer "
        + "android_capture_timeline for frames the model can see; use this when a human will review the video "
        + "or as archival evidence. Recording occupies the session for the whole duration. "
        + "Browser-side equivalent: browser_record_video.")
    public String androidRecordScreen(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Recording duration in ms (1..180000)") final Integer durationMs,
        @McpToolParam(description = "Video bitrate in Mbps (e.g. 4). Optional — omit for the device default.",
            required = false) final Integer bitRateMbps
    ) {
        log.info("MCP tool invoked: android_record_screen durationMs={} sessionId={}", durationMs, sessionId);
        final AndroidSession s = this.requireSession(sessionId);
        if (durationMs == null || durationMs <= 0) {
            return "Error: durationMs is required (1.." + MAX_RECORD_DURATION_MS + ")";
        }
        final int dur = Math.min(durationMs, MAX_RECORD_DURATION_MS);
        final String filename = String.format("%04d_rec.mp4", s.seq.incrementAndGet());
        try {
            // 외부 await는 recordScreen 내부 단계별 adb 타임아웃 합(녹화 dur+30s 슬랙 + pull은
            // installTimeout + rm은 commandTimeout)보다 커야 한다 — 작으면 정상 녹화가 외부 타임아웃의
            // future.cancel(true)로 강제 중단되어 거짓 실패가 된다. (기본 submit await 150s도 부족)
            final long awaitMs = dur + 30_000L
                + this.properties.installTimeout().toMillis()
                + this.properties.commandTimeout().toMillis()
                + 10_000L;
            final Path saved = s.submitWithTimeout(
                () -> this.deviceService.recordScreen(s.serial, dur, bitRateMbps, s.hostScreenshotDir, filename),
                awaitMs);
            return "Recorded " + dur + "ms of screen video: " + saved.toAbsolutePath()
                + " (MP4 — not analyzable inline; use android_capture_timeline for model-visible frames)";
        } catch (final RuntimeException ex) {
            log.warn("Android screen recording failed", ex);
            return "Error recording screen: " + ex.getMessage();
        }
    }

    @McpTool(name = "android_sample_pixels", description = "Sample exact pixel colors from a full-resolution "
        + "screenshot for quantitative color checks (highlight colors, state-dependent tints). Coordinates are "
        + "DEVICE pixels — the same space as android_tap x,y and android_dump_ui bounds (NOT the downscaled "
        + "android_screenshot image). Returns JSON: per-point #RRGGBBAA for points='x1,y1;x2,y2;...' and/or "
        + "the average #RRGGBB over rect='x,y,w,h'. Browser-side equivalent: browser_sample_pixels.")
    public String androidSamplePixels(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Semicolon-separated device-pixel points 'x1,y1;x2,y2'. "
            + "Optional when rect is given.", required = false) final String points,
        @McpToolParam(description = "Device-pixel rect 'x,y,w,h' to average over. Optional when points is given.",
            required = false) final String rect
    ) {
        log.info("MCP tool invoked: android_sample_pixels points={} rect={} sessionId={}", points, rect, sessionId);
        final AndroidSession s = this.requireSession(sessionId);
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
        try {
            final BufferedImage image = s.submit(() -> this.deviceService.screenshotRaw(s.serial));
            return PixelSampler.sampleToJson(image, pts, rectVals);
        } catch (final RuntimeException ex) {
            log.warn("Android sample pixels failed", ex);
            return "Error sampling pixels: " + ex.getMessage();
        }
    }

    // ===== 세션 =====

    @McpTool(name = "android_close_session", description = "Close the Android session: restores the device's "
        + "original keyboard (if ADBKeyboard was activated) and releases the device binding. The sessionId "
        + "itself stays valid for browser_* and dev_server_* tools; call android_use_device again to resume "
        + "Android testing.")
    public String androidCloseSession(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId
    ) {
        log.info("MCP tool invoked: android_close_session sessionId={}", sessionId);
        final AndroidSession s = this.sessions.remove(sessionId);
        if (s == null) {
            return "No session to close";
        }
        try {
            this.restoreAllImesQuietly(s);
            s.close();
            return "Android session closed (device binding released; sessionId remains valid for other tools)";
        } catch (final RuntimeException ex) {
            return "Error closing session: " + ex.getMessage();
        }
    }

    // ===== Helpers =====

    Map<String, AndroidSession> getSessions() {
        return this.sessions;
    }

    /**
     * 세션에서 ADBKeyboard로 전환했던 모든 기기의 IME를 복원한다 (기기별 상태 누적 순회).
     * executor shutdown 전에 호출해야 한다.
     */
    private void restoreAllImesQuietly(final AndroidSession s) {
        s.deviceStates.forEach((deviceSerial, state) -> {
            if (!state.adbKeyboardActive) {
                return;
            }
            try {
                s.submit(() -> {
                    this.deviceService.restoreIme(deviceSerial, state.originalIme);
                    return null;
                });
            } catch (final RuntimeException ex) {
                log.warn("Failed to restore IME for device {}", deviceSerial, ex);
            } finally {
                // 복원 실패여도 리셋 — 이후 잘못된 originalIme(예: ADBKeyboard 자신)로 재복원 시도하는 것보다 안전
                state.adbKeyboardActive = false;
            }
        });
    }

    /**
     * 액션을 세션 스레드에서 실행하고, screenshotAfterMs가 지정되면 같은 task 안에서
     * 대기 후 캡처까지 수행한다 — 다른 도구 호출이 액션과 캡처 사이에 끼어들 수 없다.
     */
    private CallToolResult actAndMaybeCapture(final AndroidSession s, final Integer screenshotAfterMs,
        final String successText, final Runnable deviceAction) {
        final String budgetError = screenshotBudgetError(screenshotAfterMs);
        if (budgetError != null) {
            return errText(budgetError);
        }
        if (screenshotAfterMs == null) {
            s.submit(() -> {
                deviceAction.run();
                return null;
            });
            return okText(successText);
        }
        final int waitMs = Math.max(0, screenshotAfterMs);
        final String filename = String.format("%04d.jpg", s.seq.incrementAndGet());
        final ScreenshotResult shot = s.submit(() -> {
            deviceAction.run();
            Thread.sleep(waitMs);
            return this.deviceService.screenshot(s.serial, s.hostScreenshotDir, filename, true);
        });
        return textAndImage(successText + " (screenshot after " + waitMs + "ms, " + shot.scaleSummary() + "): "
            + s.returnPathFor(filename), shot);
    }

    /** Returns an error message when screenshotAfterMs would exceed the operation budget, else null. */
    static String screenshotBudgetError(final Integer screenshotAfterMs) {
        if (screenshotAfterMs != null && screenshotAfterMs > MAX_OPERATION_BUDGET_MS) {
            return "Error: screenshotAfterMs (" + screenshotAfterMs
                + "ms) would exceed the 110s operation budget. Reduce it.";
        }
        return null;
    }

    private record TapTarget(int x, int y, String description) {
    }

    /** selector → elementIndex → x,y 우선순위로 탭 좌표를 해석한다. */
    static TapTarget resolveTarget(final AndroidSession s, final String selector, final Integer elementIndex,
        final Integer x, final Integer y) {
        if (selector != null && !selector.isBlank()) {
            final List<UiNode> dump = s.activeState().lastDump;
            if (dump == null) {
                throw new IllegalStateException(
                    "no UI dump cached. Call android_dump_ui first to use selector targeting.");
            }
            final List<UiNode> matches = dump.stream().filter(node -> matchesSelector(node, selector)).toList();
            if (matches.isEmpty()) {
                throw new IllegalStateException("no element matches selector '" + selector
                    + "'. Re-run android_dump_ui — the screen may have changed.");
            }
            final UiNode chosen = matches.getFirst();
            final String note = matches.size() > 1
                ? " (" + matches.size() + " matched, used [" + chosen.index() + "])" : "";
            return new TapTarget(chosen.centerX(), chosen.centerY(),
                "element [" + chosen.index() + "] '" + selector + "'" + note);
        }
        if (elementIndex != null) {
            final List<UiNode> dump = s.activeState().lastDump;
            if (dump == null) {
                throw new IllegalStateException(
                    "no UI dump cached. Call android_dump_ui first to use elementIndex targeting.");
            }
            if (elementIndex < 0 || elementIndex >= dump.size()) {
                throw new IllegalStateException("elementIndex " + elementIndex + " out of range (0.."
                    + (dump.size() - 1) + "). Re-run android_dump_ui.");
            }
            final UiNode node = dump.get(elementIndex);
            return new TapTarget(node.centerX(), node.centerY(), "element [" + elementIndex + "]");
        }
        if (x != null && y != null) {
            return new TapTarget(x, y, "coordinates");
        }
        throw new IllegalStateException("specify selector, elementIndex, or both x and y");
    }

    private static boolean matchesSelector(final UiNode node, final String selector) {
        final String needle = selector.toLowerCase();
        return node.resourceId().toLowerCase().contains(needle)
            || node.text().toLowerCase().contains(needle)
            || node.contentDesc().toLowerCase().contains(needle);
    }

    private static CallToolResult okText(final String text) {
        return CallToolResult.builder().addTextContent(text).isError(false).build();
    }

    private static CallToolResult errText(final String text) {
        return CallToolResult.builder().addTextContent(text).isError(true).build();
    }

    private static CallToolResult textAndImage(final String text, final ScreenshotResult shot) {
        return CallToolResult.builder()
            .addTextContent(text)
            .addContent(ImageContent.builder(shot.base64(), shot.mimeType()).build())
            .isError(false)
            .build();
    }
}
