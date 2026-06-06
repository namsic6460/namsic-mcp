package lkd.namsic.mcp.android;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import lkd.namsic.mcp.android.AndroidDeviceService.DeviceInfo;
import lkd.namsic.mcp.config.AndroidProperties;
import lkd.namsic.mcp.config.BrowserProperties;
import lkd.namsic.mcp.session.ProjectSessionRegistry;
import lkd.namsic.mcp.session.ProjectSessionRegistry.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AndroidMcpToolsTest {

    private static final String UNKNOWN_SID = "00000000-0000-0000-0000-000000000000";

    @TempDir
    Path tempScreenshotBase;

    private ProjectSessionRegistry registry;
    private AndroidDeviceService deviceService;
    private AndroidMcpTools tools;

    @BeforeEach
    void setUp() {
        BrowserProperties browserProps = new BrowserProperties(
            "node", "chromium", List.of(), null, null, null, null, null, null, this.tempScreenshotBase
        );
        this.registry = new ProjectSessionRegistry(browserProps);
        this.deviceService = mock(AndroidDeviceService.class);
        this.tools = new AndroidMcpTools(
            this.deviceService,
            this.registry,
            new AndroidProperties(null, null, null, null, null, null, null)
        );
    }

    private static String firstText(CallToolResult result) {
        TextContent text = assertInstanceOf(TextContent.class, result.content().getFirst());
        return text.text();
    }

    /** use_device까지 끝낸 활성 세션을 만든다 (deviceService는 mock). */
    private Project bindDevice() {
        Project project = this.registry.init("android-test");
        when(this.deviceService.resolveDevice(null))
            .thenReturn(new DeviceInfo("emulator-5554", "device", "sdk_gphone64"));
        when(this.deviceService.currentIme("emulator-5554"))
            .thenReturn("com.android.inputmethod.latin/.LatinIME");
        String result = this.tools.androidUseDevice(project.sessionId(), null);
        assertTrue(result.startsWith("Selected device emulator-5554"), result);
        return project;
    }

    // ===== 세션 게이트 =====

    @Test
    void useDeviceReturnsErrorWithoutInit() {
        String result = this.tools.androidUseDevice(UNKNOWN_SID, null);
        assertTrue(result.startsWith("Error: Unknown sessionId"), result);
    }

    @Test
    void listDevicesReturnsErrorWithBlankSessionId() {
        String result = this.tools.androidListDevices("");
        assertTrue(result.contains("sessionId is required"), result);
    }

    @Test
    void tapFailsWithoutInit() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> this.tools.androidTap(UNKNOWN_SID, null, null, 10, 20, null));
        assertTrue(ex.getMessage().contains("project_init"), ex.getMessage());
    }

    @Test
    void screenshotFailsWithoutUseDevice() {
        Project project = this.registry.init("no-device");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> this.tools.androidScreenshot(project.sessionId(), null));
        assertTrue(ex.getMessage().contains("android_use_device"), ex.getMessage());
    }

    // ===== tap 타게팅 가드 =====

    @Test
    void tapRejectsWhenNoTargetSpecified() {
        Project project = this.bindDevice();
        CallToolResult result = this.tools.androidTap(project.sessionId(), null, null, null, null, null);
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(firstText(result).contains("specify selector, elementIndex, or both x and y"),
            firstText(result));
    }

    @Test
    void tapSelectorRequiresCachedDump() {
        Project project = this.bindDevice();
        CallToolResult result = this.tools.androidTap(project.sessionId(), "login", null, null, null, null);
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(firstText(result).contains("Call android_dump_ui first"), firstText(result));
    }

    @Test
    void tapElementIndexRejectsOutOfRange() {
        Project project = this.bindDevice();
        AndroidSession session = this.tools.getSessions().get(project.sessionId());
        session.activeState().lastDump = List.of(
            new UiNode(0, "로그인", "com.example:id/btn_login", "", "android.widget.Button",
                true, 0, 0, 100, 100, 50, 50));

        CallToolResult result = this.tools.androidTap(project.sessionId(), null, 5, null, null, null);
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(firstText(result).contains("out of range"), firstText(result));
    }

    @Test
    void tapSelectorMatchesCachedDumpAndTapsCenter() {
        Project project = this.bindDevice();
        AndroidSession session = this.tools.getSessions().get(project.sessionId());
        session.activeState().lastDump = List.of(
            new UiNode(0, "로그인", "com.example:id/btn_login", "", "android.widget.Button",
                true, 120, 1400, 960, 1520, 540, 1460));

        CallToolResult result = this.tools.androidTap(project.sessionId(), "btn_login", null, null, null, null);
        assertEquals(Boolean.FALSE, result.isError());
        assertTrue(firstText(result).contains("(540,1460)"), firstText(result));
    }

    @Test
    void tapRejectsScreenshotAfterMsOverBudget() {
        Project project = this.bindDevice();
        CallToolResult result = this.tools.androidTap(project.sessionId(), null, null, 10, 20, 110_001);
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(firstText(result).contains("110s operation budget"), firstText(result));
    }

    // ===== 텍스트 입력 =====

    @Test
    void inputTextRejectsNonAsciiWithoutAdbKeyboard() {
        Project project = this.bindDevice();
        when(this.deviceService.isAdbKeyboardInstalled("emulator-5554")).thenReturn(false);
        // 실제 AndroidDeviceService.ensureAdbKeyboardActive의 예외를 mock으로 재현
        doThrow(new IllegalStateException(
                "non-ASCII or special-character text requires the ADBKeyboard IME"))
            .when(this.deviceService).ensureAdbKeyboardActive("emulator-5554");

        CallToolResult result = this.tools.androidInputText(project.sessionId(), "한글테스트", null);
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(firstText(result).startsWith("Error typing text:"), firstText(result));
        assertTrue(firstText(result).contains("ADBKeyboard"), firstText(result));
    }

    @Test
    void inputTextRejectsEmptyText() {
        Project project = this.bindDevice();
        CallToolResult result = this.tools.androidInputText(project.sessionId(), "", null);
        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("Error: text is required", firstText(result));
    }

    @Test
    void rebindKeepsOriginalImeAndRestoresOnClose() {
        Project project = this.bindDevice();
        AndroidSession session = this.tools.getSessions().get(project.sessionId());
        // 비ASCII 입력으로 ADBKeyboard로 전환한 상태를 재현
        session.activeState().adbKeyboardActive = true;

        // 같은 기기 재바인딩 — originalIme는 최초 기록이 유지되고(덮어쓰기 없음) 복원도 일어나지 않는다
        String result = this.tools.androidUseDevice(project.sessionId(), null);
        assertTrue(result.startsWith("Selected device"), result);
        verify(this.deviceService, never()).restoreIme(any(), any());
        verify(this.deviceService, times(1)).currentIme("emulator-5554");
        assertEquals("com.android.inputmethod.latin/.LatinIME", session.activeState().originalIme);
        assertTrue(session.activeState().adbKeyboardActive);

        // close 시점에 ADBKeyboard가 활성이었던 기기의 IME가 복원된다
        this.tools.androidCloseSession(project.sessionId());
        verify(this.deviceService).restoreIme("emulator-5554", "com.android.inputmethod.latin/.LatinIME");
    }

    // ===== 세션 정리 =====

    @Test
    void closeSessionReturnsNoSessionWhenUnknown() {
        assertEquals("No session to close", this.tools.androidCloseSession(UNKNOWN_SID));
    }

    @Test
    void closeSessionKeepsRegistryEntryForOtherTools() {
        Project project = this.bindDevice();
        String result = this.tools.androidCloseSession(project.sessionId());
        assertTrue(result.startsWith("Android session closed"), result);
        assertTrue(this.tools.getSessions().isEmpty());
        // sessionId는 browser_*/dev_server_* 용으로 계속 유효해야 함
        assertDoesNotThrow(() -> this.registry.require(project.sessionId()));
    }

    @Test
    void destroyShouldBeIdempotent() {
        assertDoesNotThrow(this.tools::destroy);
        assertTrue(this.tools.getSessions().isEmpty());
    }

    @Test
    void installRejectsMissingApk() {
        Project project = this.bindDevice();
        String result = this.tools.androidInstallApp(project.sessionId(),
            this.tempScreenshotBase.resolve("missing.apk").toString(), null);
        assertTrue(result.startsWith("Error: APK file not found"), result);
    }

    // ===== per-serial 상태 =====

    @Test
    void perSerialStatePreservedWhenSwitchingDevices() {
        Project project = this.bindDevice(); // emulator-5554
        AndroidSession session = this.tools.getSessions().get(project.sessionId());
        List<UiNode> dumpA = List.of(new UiNode(0, "로그인", "com.example:id/btn_login", "",
            "android.widget.Button", true, 0, 0, 100, 100, 50, 50));
        session.activeState().lastDump = dumpA;

        // 두 번째 기기로 전환 — 새 기기의 상태는 비어 있고 IME가 새로 기록된다
        when(this.deviceService.resolveDevice("emulator-5556"))
            .thenReturn(new DeviceInfo("emulator-5556", "device", "sdk_gphone64"));
        when(this.deviceService.currentIme("emulator-5556")).thenReturn("second.ime/.Ime");
        assertTrue(this.tools.androidUseDevice(project.sessionId(), "emulator-5556")
            .startsWith("Selected device"));
        assertNull(session.activeState().lastDump);
        assertEquals("second.ime/.Ime", session.activeState().originalIme);

        // 첫 번째 기기로 복귀 — dump 캐시와 originalIme가 보존되어 있다
        when(this.deviceService.resolveDevice("emulator-5554"))
            .thenReturn(new DeviceInfo("emulator-5554", "device", "sdk_gphone64"));
        assertTrue(this.tools.androidUseDevice(project.sessionId(), "emulator-5554")
            .startsWith("Selected device"));
        assertSame(dumpA, session.activeState().lastDump);
        assertEquals("com.android.inputmethod.latin/.LatinIME", session.activeState().originalIme);
    }

    @Test
    void useDeviceRetriesImeCaptureWhenFirstReadWasBlank() {
        Project project = this.registry.init("ime-retry");
        when(this.deviceService.resolveDevice(null))
            .thenReturn(new DeviceInfo("emulator-5554", "device", "sdk_gphone64"));
        // 첫 바인딩 시 settings 읽기 실패('')— 재바인딩에서 자가 교정되어야 한다
        when(this.deviceService.currentIme("emulator-5554")).thenReturn("", "real.ime/.Ime");

        this.tools.androidUseDevice(project.sessionId(), null);
        AndroidSession session = this.tools.getSessions().get(project.sessionId());
        assertEquals("", session.activeState().originalIme);

        this.tools.androidUseDevice(project.sessionId(), null);
        assertEquals("real.ime/.Ime", session.activeState().originalIme);
    }

    // ===== 신규 도구 가드 =====

    @Test
    void setOfflineFailsWithoutUseDevice() {
        Project project = this.registry.init("no-device-offline");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> this.tools.androidSetOffline(project.sessionId(), true));
        assertTrue(ex.getMessage().contains("android_use_device"), ex.getMessage());
    }

    @Test
    void setNetworkConditionsConvertsDomainErrorToErrorString() {
        Project project = this.bindDevice();
        doThrow(new IllegalStateException("network speed/delay emulation requires an emulator"))
            .when(this.deviceService).setNetworkConditions("emulator-5554", "3g", null);

        String result = this.tools.androidSetNetworkConditions(project.sessionId(), "3g", null);
        assertTrue(result.startsWith("Error setting network conditions:"), result);
        assertTrue(result.contains("requires an emulator"), result);
    }

    @Test
    void pushFileRejectsMissingLocalFile() {
        Project project = this.bindDevice();
        String result = this.tools.androidPushFile(project.sessionId(),
            this.tempScreenshotBase.resolve("missing.png").toString(), "/sdcard/Download/missing.png", null);
        assertTrue(result.startsWith("Error: local file not found"), result);
    }

    @Test
    void pushFileRejectsRelativeDevicePath() throws IOException {
        Project project = this.bindDevice();
        Path local = this.tempScreenshotBase.resolve("exists.png");
        Files.createFile(local);
        String result = this.tools.androidPushFile(project.sessionId(),
            local.toString(), "sdcard/Download/exists.png", null);
        assertTrue(result.startsWith("Error: devicePath must be an absolute device path"), result);
    }

    @Test
    void captureTimelineRejectsOverBudgetBeforeSessionCheck() {
        CallToolResult result = this.tools.androidCaptureTimeline(UNKNOWN_SID, 1000, 200);
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(firstText(result).contains("would exceed 110s"), firstText(result));
    }

    @Test
    void recordScreenRequiresDuration() {
        Project project = this.bindDevice();
        String result = this.tools.androidRecordScreen(project.sessionId(), null, null);
        assertTrue(result.startsWith("Error: durationMs is required"), result);
    }

    @Test
    void samplePixelsRejectsMissingAndMalformedInput() {
        Project project = this.bindDevice();
        String missing = this.tools.androidSamplePixels(project.sessionId(), null, null);
        assertTrue(missing.startsWith("Error: provide points"), missing);

        String malformed = this.tools.androidSamplePixels(project.sessionId(), "10", null);
        assertTrue(malformed.startsWith("Error: invalid point"), malformed);
    }

    // ===== 정적 헬퍼 =====

    @Test
    void screenshotBudgetErrorGuardsUpperBoundOnly() {
        assertNull(AndroidMcpTools.screenshotBudgetError(0));
        assertNull(AndroidMcpTools.screenshotBudgetError(110_000));
        String error = AndroidMcpTools.screenshotBudgetError(110_001);
        assertTrue(error != null && error.startsWith("Error: screenshotAfterMs"), String.valueOf(error));
    }

    @Test
    void normalizeKeycodeHandlesNamesAndNumbers() {
        assertEquals("KEYCODE_BACK", AndroidDeviceService.normalizeKeycode("back"));
        assertEquals("KEYCODE_HOME", AndroidDeviceService.normalizeKeycode("KEYCODE_HOME"));
        assertEquals("4", AndroidDeviceService.normalizeKeycode("4"));
    }

    @Test
    void isSafeAsciiTextDistinguishesInputPaths() {
        assertTrue(AndroidDeviceService.isSafeAsciiText("hello world 123 user@example.com"));
        assertFalse(AndroidDeviceService.isSafeAsciiText("한글"));
        assertFalse(AndroidDeviceService.isSafeAsciiText("quote\"inside"));
        assertFalse(AndroidDeviceService.isSafeAsciiText("semi;colon"));
    }
}
