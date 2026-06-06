package lkd.namsic.mcp.android;

import lkd.namsic.mcp.config.AndroidProperties;
import lkd.namsic.mcp.util.ProcessBuilders.ProcessResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AndroidDeviceServiceTest {

    private static final String SERIAL = "emulator-5554";

    private AndroidDeviceService serviceWithStdout(final String stdout) {
        AdbCommandRunner adb = mock(AdbCommandRunner.class);
        when(adb.runText(eq(SERIAL), anyList(), any(Duration.class)))
            .thenReturn(new ProcessResult(0, stdout, false));
        return new AndroidDeviceService(adb,
            new AndroidProperties(null, null, null, null, null, null, null));
    }

    // ===== exit 0이지만 실패하는 adb 명령들의 거짓 성공 방지 =====

    @Test
    void launchAppDetectsMissingActivityDespiteExitZero() {
        AndroidDeviceService service = this.serviceWithStdout("""
            Starting: Intent { cmp=com.example/.Missing }
            Error type 3
            Error: Activity class {com.example/.Missing} does not exist.""");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.launchApp(SERIAL, "com.example", ".Missing"));
        assertTrue(ex.getMessage().contains("am start failed"), ex.getMessage());
    }

    @Test
    void launchAppAcceptsSuccessfulAmStart() {
        AndroidDeviceService service = this.serviceWithStdout(
            "Starting: Intent { cmp=com.example/.Main }\nStatus: ok\nLaunchState: COLD\nTotalTime: 512");
        assertDoesNotThrow(() -> service.launchApp(SERIAL, "com.example", ".Main"));
    }

    @Test
    void launchAppAcceptsComponentNameContainingError() {
        // 컴포넌트 이름에 'Error'가 들어가는 정상 시작을 거짓 실패 처리하면 안 된다
        AndroidDeviceService service = this.serviceWithStdout(
            "Starting: Intent { cmp=com.example/.ErrorReportActivity }\nStatus: ok\nTotalTime: 300");
        assertDoesNotThrow(() -> service.launchApp(SERIAL, "com.example", ".ErrorReportActivity"));
    }

    @Test
    void launchAppDetectsMonkeyAbortDespiteExitZero() {
        AndroidDeviceService service = this.serviceWithStdout(
            "** No activities found to run, monkey aborted.");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.launchApp(SERIAL, "com.example.missing", null));
        assertTrue(ex.getMessage().contains("monkey launch failed"), ex.getMessage());
    }

    @Test
    void launchAppAcceptsSuccessfulMonkeyLaunch() {
        AndroidDeviceService service = this.serviceWithStdout("Events injected: 1\n## Network stats: ...");
        assertDoesNotThrow(() -> service.launchApp(SERIAL, "com.example", null));
    }

    @Test
    void unicodeBroadcastAcceptsNormalCompletionAndRejectsAmErrors() {
        // am broadcast는 수신 처리와 무관하게 result=0을 출력 — 정상 완료는 통과해야 함
        AndroidDeviceService ok = this.serviceWithStdout(
            "Broadcasting: Intent { act=ADB_INPUT_B64 }\nBroadcast completed: result=0");
        assertDoesNotThrow(() -> ok.inputUnicodeViaKeyboard(SERIAL, "한글"));

        AndroidDeviceService failing = this.serviceWithStdout(
            "Broadcasting: Intent { act=ADB_INPUT_B64 }\njava.lang.SecurityException: Permission Denial");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> failing.inputUnicodeViaKeyboard(SERIAL, "한글"));
        assertTrue(ex.getMessage().contains("broadcast failed"), ex.getMessage());
    }

    @Test
    void listDevicesParsesSerialStateAndModel() {
        AdbCommandRunner adb = mock(AdbCommandRunner.class);
        when(adb.runText(eq(null), anyList(), any(Duration.class)))
            .thenReturn(new ProcessResult(0, """
                List of devices attached
                emulator-5554\tdevice product:sdk_gphone64 model:sdk_gphone64_x86_64 device:emu64x
                R3CN30XXXX\tunauthorized usb:1-1
                """, false));
        AndroidDeviceService service = new AndroidDeviceService(adb,
            new AndroidProperties(null, null, null, null, null, null, null));

        List<AndroidDeviceService.DeviceInfo> devices = service.listDevices();
        assertEquals(2, devices.size());
        assertEquals("emulator-5554", devices.getFirst().serial());
        assertEquals("device", devices.getFirst().state());
        assertEquals("sdk_gphone64_x86_64", devices.getFirst().model());
        assertEquals("unauthorized", devices.get(1).state());
    }

    @Test
    void downscaleShrinksLongestSideToMaxDimension() {
        BufferedImage source = new BufferedImage(1080, 2400, BufferedImage.TYPE_INT_ARGB);
        BufferedImage scaled = AndroidDeviceService.downscale(source, 1280);

        assertEquals(1280, Math.max(scaled.getWidth(), scaled.getHeight()));
        assertEquals(576, scaled.getWidth());
        assertEquals(1280, scaled.getHeight());
        assertEquals(BufferedImage.TYPE_INT_RGB, scaled.getType());
    }

    @Test
    void downscaleKeepsSmallRgbImageAsIs() {
        BufferedImage source = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        assertSame(source, AndroidDeviceService.downscale(source, 1280));
    }

    @Test
    void downscaleConvertsSmallArgbImageToRgb() {
        // 크기는 유지하되 JPEG 호환을 위해 RGB로 변환되어야 함
        BufferedImage source = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        BufferedImage converted = AndroidDeviceService.downscale(source, 1280);

        assertEquals(800, converted.getWidth());
        assertEquals(600, converted.getHeight());
        assertEquals(BufferedImage.TYPE_INT_RGB, converted.getType());
    }

    @Test
    void toJpegProducesReadableJpegBytes() throws IOException {
        BufferedImage source = new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB);
        byte[] jpeg = AndroidDeviceService.toJpeg(source, 0.8f);

        assertTrue(jpeg.length > 0);
        // JPEG SOI 마커
        assertEquals((byte) 0xFF, jpeg[0]);
        assertEquals((byte) 0xD8, jpeg[1]);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertNotNull(decoded);
        assertEquals(100, decoded.getWidth());
        assertEquals(50, decoded.getHeight());
    }

    // ===== 네트워크 제어 =====

    @Test
    void setNetworkConditionsRejectsPhysicalDevice() {
        AndroidDeviceService service = this.serviceWithStdout("OK");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.setNetworkConditions("R3CN30XXXX", "3g", null));
        assertTrue(ex.getMessage().contains("requires an emulator"), ex.getMessage());
    }

    @Test
    void setNetworkConditionsRequiresSpeedOrDelay() {
        AndroidDeviceService service = this.serviceWithStdout("OK");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.setNetworkConditions(SERIAL, null, " "));
        assertTrue(ex.getMessage().contains("specify speed and/or delay"), ex.getMessage());
    }

    @Test
    void setNetworkConditionsDetectsKoConsoleResponseDespiteExitZero() {
        AndroidDeviceService service = this.serviceWithStdout("KO: bad network speed format");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.setNetworkConditions(SERIAL, "not-a-speed", null));
        assertTrue(ex.getMessage().contains("rejected by emulator console"), ex.getMessage());
    }

    @Test
    void setNetworkConditionsAcceptsOkConsoleResponse() {
        AndroidDeviceService service = this.serviceWithStdout("OK");
        assertDoesNotThrow(() -> service.setNetworkConditions(SERIAL, "full", "none"));
    }

    @Test
    void setHttpProxyValidatesHostPortAndClearsWithColonZero() {
        AdbCommandRunner adb = mock(AdbCommandRunner.class);
        when(adb.runText(eq(SERIAL), anyList(), any(Duration.class)))
            .thenReturn(new ProcessResult(0, "", false));
        AndroidDeviceService service = new AndroidDeviceService(adb,
            new AndroidProperties(null, null, null, null, null, null, null));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.setHttpProxy(SERIAL, "not a proxy"));
        assertTrue(ex.getMessage().contains("invalid proxy value"), ex.getMessage());

        service.setHttpProxy(SERIAL, "10.0.2.2:8888");
        verify(adb).runText(eq(SERIAL),
            eq(List.of("shell", "settings", "put", "global", "http_proxy", "10.0.2.2:8888")),
            any(Duration.class));

        service.setHttpProxy(SERIAL, null);
        verify(adb).runText(eq(SERIAL),
            eq(List.of("shell", "settings", "put", "global", "http_proxy", ":0")),
            any(Duration.class));
    }

    @Test
    void setOfflineTogglesBothWifiAndData() {
        AdbCommandRunner adb = mock(AdbCommandRunner.class);
        when(adb.runText(eq(SERIAL), anyList(), any(Duration.class)))
            .thenReturn(new ProcessResult(0, "", false));
        AndroidDeviceService service = new AndroidDeviceService(adb,
            new AndroidProperties(null, null, null, null, null, null, null));

        service.setOffline(SERIAL, true);
        verify(adb).runText(eq(SERIAL), eq(List.of("shell", "svc", "wifi", "disable")), any(Duration.class));
        verify(adb).runText(eq(SERIAL), eq(List.of("shell", "svc", "data", "disable")), any(Duration.class));

        service.setOffline(SERIAL, false);
        verify(adb).runText(eq(SERIAL), eq(List.of("shell", "svc", "wifi", "enable")), any(Duration.class));
        verify(adb).runText(eq(SERIAL), eq(List.of("shell", "svc", "data", "enable")), any(Duration.class));
    }

    // ===== 화면 녹화 =====

    @Test
    void recordScreenFailsWhenPulledFileMissing(@TempDir final Path tempDir) {
        // screenrecord와 pull 모두 exit 0이지만 호스트에 파일이 생기지 않은 경우를 거짓 성공으로 잡는다
        AndroidDeviceService service = this.serviceWithStdout("");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.recordScreen(SERIAL, 3_000, null, tempDir, "rec.mp4"));
        assertTrue(ex.getMessage().contains("is missing"), ex.getMessage());
    }
}
