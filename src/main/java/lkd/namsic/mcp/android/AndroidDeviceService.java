package lkd.namsic.mcp.android;

import lkd.namsic.mcp.android.AdbCommandRunner.AdbBinaryResult;
import lkd.namsic.mcp.android.AndroidUiParser.ParseResult;
import lkd.namsic.mcp.config.AndroidProperties;
import lkd.namsic.mcp.util.ProcessBuilders.ProcessResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * adb 도메인 로직 전부를 캡슐화한다 (AndroidMcpTools는 얇은 어댑터).
 * 실패는 IllegalStateException으로 던지고, 도구 계층이 "Error ..." 문자열/errText로 변환한다.
 */
@Slf4j
@Service
public class AndroidDeviceService {

    static final String ADB_KEYBOARD_PKG = "com.android.adbkeyboard";
    static final String ADB_KEYBOARD_IME = "com.android.adbkeyboard/.AdbIME";

    private static final String DUMP_DEVICE_PATH = "/sdcard/window_dump.xml";
    private static final int DUMP_MAX_ATTEMPTS = 3;
    private static final long DUMP_RETRY_DELAY_MS = 500L;
    private static final float JPEG_QUALITY = 0.8f;
    /** adb shell input text로 안전하게 전달 가능한 문자 집합 — 그 외는 ADBKeyboard 경유. */
    private static final String SAFE_INPUT_SYMBOLS = " .,:@_/+-";

    private final AdbCommandRunner adb;
    private final AndroidProperties properties;

    /**
     * {@code exec-out screencap -p} stdout이 디코드 가능한 PNG를 못 내놓는 기기(adb.exe stdout
     * 텍스트모드 변환·일부 OEM의 stdout 선행 출력 등)를 기억해 두 번째부터는 곧바로 파일+pull로 캡처한다.
     */
    private final Set<String> pullOnlyScreencap = ConcurrentHashMap.newKeySet();

    public AndroidDeviceService(final AdbCommandRunner adb, final AndroidProperties properties) {
        this.adb = adb;
        this.properties = properties;
    }

    public record DeviceInfo(String serial, String state, String model) {

        String describe() {
            return this.serial + " (state=" + this.state
                + (this.model.isEmpty() ? "" : ", model=" + this.model) + ")";
        }
    }

    public record ScreenshotResult(byte[] bytes, String filename, String mimeType,
                                   int deviceWidth, int deviceHeight, int imageWidth, int imageHeight) {

        public String base64() {
            return Base64.getEncoder().encodeToString(this.bytes());
        }

        /** 다운스케일 시 이미지 픽셀 ≠ 기기 좌표이므로 LLM이 환산할 수 있게 비율을 명시한다. */
        public String scaleSummary() {
            if (this.deviceWidth == this.imageWidth && this.deviceHeight == this.imageHeight) {
                return "device " + this.deviceWidth + "x" + this.deviceHeight + ", image 1:1";
            }
            final double scale = (double) this.imageWidth / this.deviceWidth;
            return String.format(Locale.ROOT,
                "device %dx%d, image %dx%d (scale %.3f — divide image coords by %.3f for device tap coords)",
                this.deviceWidth, this.deviceHeight, this.imageWidth, this.imageHeight, scale, scale);
        }
    }

    // ===== 기기 목록/선택 =====

    public List<DeviceInfo> listDevices() {
        final ProcessResult result = this.adb.runText(null, List.of("devices", "-l"), this.properties.commandTimeout());
        if (!result.success()) {
            throw new IllegalStateException("adb devices failed: "
                + (result.timedOut() ? "timed out" : result.stdout()));
        }
        final List<DeviceInfo> devices = new ArrayList<>();
        for (final String line : result.stdout().lines().toList()) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("List of devices") || trimmed.startsWith("*")) {
                continue;
            }
            final String[] parts = trimmed.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            String model = "";
            for (final String part : parts) {
                if (part.startsWith("model:")) {
                    model = part.substring("model:".length());
                    break;
                }
            }
            devices.add(new DeviceInfo(parts[0], parts[1], model));
        }
        return devices;
    }

    /** serial 미지정 시 단일 ready 기기를 자동 선택한다. 0대/다중이면 목록을 담은 예외. */
    public DeviceInfo resolveDevice(final String requested) {
        final List<DeviceInfo> devices = this.listDevices();
        if (requested != null && !requested.isBlank()) {
            final DeviceInfo found = devices.stream()
                .filter(d -> d.serial().equals(requested))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Device not found: " + requested
                    + ". Connected: " + formatDevices(devices)));
            if (!"device".equals(found.state())) {
                throw new IllegalStateException("Device " + requested + " is in state '" + found.state()
                    + "' (unauthorized = approve the RSA debugging prompt on the device; offline = reconnect)");
            }
            return found;
        }
        final List<DeviceInfo> ready = devices.stream().filter(d -> "device".equals(d.state())).toList();
        if (ready.isEmpty()) {
            throw new IllegalStateException("No device in 'device' state. Connected: " + formatDevices(devices)
                + " — start an emulator or connect a device with USB debugging enabled.");
        }
        if (ready.size() > 1) {
            throw new IllegalStateException("Multiple devices connected; specify a serial. Ready: "
                + formatDevices(ready));
        }
        return ready.getFirst();
    }

    private static String formatDevices(final List<DeviceInfo> devices) {
        return devices.isEmpty() ? "(none)" : String.join(", ", devices.stream().map(DeviceInfo::describe).toList());
    }

    // ===== 앱 설치/실행 =====

    public String install(final String serial, final Path apkPath, final boolean reinstall) {
        final List<String> args = new ArrayList<>(List.of("install"));
        if (reinstall) {
            args.add("-r");
        }
        args.add(apkPath.toAbsolutePath().toString());
        final ProcessResult result = this.adb.runText(serial, args, this.properties.installTimeout());
        if (result.timedOut()) {
            throw new IllegalStateException("adb install timed out after "
                + this.properties.installTimeout().toSeconds() + "s");
        }
        if (result.exitCode() != 0 || !result.stdout().contains("Success")) {
            throw new IllegalStateException("adb install failed: " + result.stdout());
        }
        return result.stdout();
    }

    public String launchApp(final String serial, final String packageName, final String activity) {
        if (activity != null && !activity.isBlank()) {
            final String component = activity.contains("/") ? activity : packageName + "/" + activity;
            final String output = this.runChecked(serial, List.of("shell", "am", "start", "-W", "-n", component),
                this.properties.commandTimeout(), "am start");
            // am start는 액티비티가 없어도 exit 0이고 출력이 "Starting: ..."으로 시작한다 —
            // "Error type 3" / "Error: Activity class ... does not exist."는 뒤 라인에 온다.
            // contains 검사는 "Starting: Intent { cmp=.../.ErrorReportActivity }"처럼 컴포넌트
            // 이름에 'Error'가 들어가는 정상 시작을 거짓 실패 처리하므로 라인 시작만 본다.
            if (output.lines().anyMatch(line -> line.trim().startsWith("Error"))) {
                throw new IllegalStateException("am start failed: " + output);
            }
            return output;
        }
        final String output = this.runChecked(serial,
            List.of("shell", "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1"),
            this.properties.commandTimeout(), "monkey launch");
        // monkey는 패키지/런처 액티비티가 없으면 "** No activities found to run, monkey aborted."를
        // 출력하면서도 exit 0으로 끝난다.
        if (output.contains("No activities found") || output.contains("monkey aborted")) {
            throw new IllegalStateException(
                "monkey launch failed (package not installed or has no launcher activity): " + output);
        }
        return output;
    }

    public void stopApp(final String serial, final String packageName) {
        this.runChecked(serial, List.of("shell", "am", "force-stop", packageName),
            this.properties.commandTimeout(), "am force-stop");
    }

    public String listPackages(final String serial, final boolean thirdPartyOnly, final String nameFilter) {
        final List<String> args = new ArrayList<>(List.of("shell", "pm", "list", "packages"));
        if (thirdPartyOnly) {
            args.add("-3");
        }
        if (nameFilter != null && !nameFilter.isBlank()) {
            args.add(nameFilter.trim());
        }
        return this.runChecked(serial, args, this.properties.commandTimeout(), "pm list packages");
    }

    // ===== UI 인식/조작 =====

    /** dump → exec-out cat → 파싱. idle 실패/무효 XML은 최대 3회 재시도. */
    public ParseResult dumpUi(final String serial, final int maxNodes) {
        String lastError = "";
        for (int attempt = 1; attempt <= DUMP_MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                sleepQuietly(DUMP_RETRY_DELAY_MS);
            }
            final ProcessResult dump = this.adb.runText(serial,
                List.of("shell", "uiautomator", "dump", DUMP_DEVICE_PATH), this.properties.dumpTimeout());
            if (dump.timedOut() || dump.stdout().contains("could not get idle")
                || dump.stdout().contains("ERROR")) {
                lastError = dump.timedOut() ? "dump timed out" : dump.stdout();
                continue;
            }
            // exec-out cat — PTY를 거치지 않아 멀티바이트(UTF-8) 텍스트도 변형 없이 수신
            final AdbBinaryResult cat = this.adb.runBinary(serial,
                List.of("exec-out", "cat", DUMP_DEVICE_PATH), this.properties.dumpTimeout());
            if (cat.isFail()) {
                lastError = "cat failed: " + cat.stderr();
                continue;
            }
            final String xml = new String(cat.stdout(), StandardCharsets.UTF_8);
            if (!xml.contains("<?xml") && !xml.contains("<hierarchy")) {
                lastError = "invalid dump content";
                continue;
            }
            return AndroidUiParser.parse(xml, maxNodes);
        }
        throw new IllegalStateException("UI dump failed after " + DUMP_MAX_ATTEMPTS + " attempts (" + lastError
            + "). The screen may be animating; retry after it settles, or use android_screenshot instead.");
    }

    public void tap(final String serial, final int x, final int y) {
        this.runChecked(serial, List.of("shell", "input", "tap", String.valueOf(x), String.valueOf(y)),
            this.properties.commandTimeout(), "input tap");
    }

    public void swipe(final String serial, final int x1, final int y1, final int x2, final int y2,
        final int durationMs) {
        this.runChecked(serial, List.of("shell", "input", "swipe",
                String.valueOf(x1), String.valueOf(y1), String.valueOf(x2), String.valueOf(y2),
                String.valueOf(durationMs)),
            this.properties.commandTimeout(), "input swipe");
    }

    public void pressKey(final String serial, final String keycode) {
        this.runChecked(serial, List.of("shell", "input", "keyevent", keycode),
            this.properties.commandTimeout(), "input keyevent");
    }

    /** "HOME"/"back" → "KEYCODE_HOME"/"KEYCODE_BACK", 숫자/KEYCODE_* 는 그대로. */
    static String normalizeKeycode(final String key) {
        final String trimmed = key.trim();
        if (!trimmed.isEmpty() && trimmed.chars().allMatch(Character::isDigit)) {
            return trimmed;
        }
        final String upper = trimmed.toUpperCase(Locale.ROOT);
        return upper.startsWith("KEYCODE_") ? upper : "KEYCODE_" + upper;
    }

    // ===== 스크린샷 =====

    /** screencap PNG를 받아둘 기기 측 임시 경로 (stdout이 못 미더운 기기의 pull 폴백용). */
    private static final String SHOT_DEVICE_PATH = "/sdcard/namsic_shot.png";
    /** PNG 시그니처 길이 — 정상 PNG라면 선두가 "89 50 4e 47 0d 0a 1a 0a". */
    private static final int PNG_SIGNATURE_LENGTH = 8;

    /**
     * {@code exec-out screencap -p}로 raw PNG를 받아 저장한다.
     * jpeg=true면 다운스케일(최대 변 screenshotMaxDimension) + JPEG q80으로 변환해
     * API 비전 한계(~1.15MP) 초과 낭비를 막는다.
     * <p>일부 기기는 stdout으로 받은 바이트가 디코드 가능한 PNG가 아니다(시그니처 손상 등). 그 경우
     * {@link #recordScreen}와 동일하게 파일+{@code adb pull}(바이너리 안전)로 재캡처하고, 해당 기기는
     * 이후 곧바로 pull 경로를 타도록 기억한다.
     */
    public ScreenshotResult screenshot(final String serial, final Path dir, final String filename,
        final boolean jpeg) {
        byte[] png;
        BufferedImage source;
        if (this.pullOnlyScreencap.contains(serial)) {
            png = this.screencapViaPull(serial);
            source = tryDecodePng(png);
        } else {
            png = this.screencapViaStdout(serial);
            source = tryDecodePng(png);
            if (source == null) {
                log.warn("screencap stdout was not a decodable PNG on {} ({} bytes, header=[{}]); "
                        + "switching this device to the adb pull capture path",
                    serial, png.length, hexPrefix(png));
                this.pullOnlyScreencap.add(serial);
                png = this.screencapViaPull(serial);
                source = tryDecodePng(png);
            }
        }
        if (source == null) {
            throw new IllegalStateException("screencap returned undecodable image data (" + png.length
                + " bytes, header=[" + hexPrefix(png) + "]). If the foreground window is FLAG_SECURE "
                + "(DRM/banking/password manager), it cannot be captured.");
        }
        try {
            if (!jpeg) {
                Files.write(dir.resolve(filename), png);
                return new ScreenshotResult(png, filename, "image/png",
                    source.getWidth(), source.getHeight(), source.getWidth(), source.getHeight());
            }
            final BufferedImage scaled = downscale(source, this.properties.screenshotMaxDimension());
            final byte[] jpegBytes = toJpeg(scaled, JPEG_QUALITY);
            Files.write(dir.resolve(filename), jpegBytes);
            return new ScreenshotResult(jpegBytes, filename, "image/jpeg",
                source.getWidth(), source.getHeight(), scaled.getWidth(), scaled.getHeight());
        } catch (final IOException ex) {
            throw new IllegalStateException("Failed to process screenshot: " + ex.getMessage(), ex);
        }
    }

    /** 빠른 경로: {@code exec-out screencap -p} stdout 원시 바이트. 프로세스 자체가 실패하면 throw. */
    private byte[] screencapViaStdout(final String serial) {
        final AdbBinaryResult result = this.adb.runBinary(serial, List.of("exec-out", "screencap", "-p"),
            this.properties.commandTimeout());
        if (result.isFail() || result.stdout().length == 0) {
            throw new IllegalStateException("screencap failed: "
                + (result.timedOut() ? "timed out" : result.stderr()));
        }
        return result.stdout();
    }

    /**
     * 폴백 경로: 기기 파일로 캡처한 뒤 {@code adb pull}로 가져온다. pull은 sync 프로토콜로 파일을
     * 직접 쓰므로 stdout 텍스트모드 변환에 영향받지 않는다. 기기·호스트 임시 파일은 항상 정리한다.
     */
    private byte[] screencapViaPull(final String serial) {
        this.runChecked(serial, List.of("shell", "screencap", "-p", SHOT_DEVICE_PATH),
            this.properties.commandTimeout(), "screencap to file");
        final Path tmp;
        try {
            tmp = Files.createTempFile("namsic_shot", ".png");
        } catch (final IOException ex) {
            throw new IllegalStateException("could not create temp file for screencap pull: " + ex.getMessage(), ex);
        }
        try {
            final ProcessResult pull = this.adb.runText(serial,
                List.of("pull", SHOT_DEVICE_PATH, tmp.toString()), this.properties.commandTimeout());
            if (pull.timedOut() || pull.exitCode() != 0) {
                throw new IllegalStateException("adb pull of screencap failed: "
                    + (pull.timedOut() ? "timed out" : pull.stdout()));
            }
            return Files.readAllBytes(tmp);
        } catch (final IOException ex) {
            throw new IllegalStateException("failed to read pulled screencap: " + ex.getMessage(), ex);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (final IOException ignored) {
                // 호스트 임시 파일 정리 실패는 캡처 결과에 영향이 없다
            }
            this.adb.runText(serial, List.of("shell", "rm", "-f", SHOT_DEVICE_PATH),
                this.properties.commandTimeout());
        }
    }

    /** PNG 디코드 시도. 디코드 불가(시그니처 미인식·본문 손상)면 IOException도 삼키고 null 반환. */
    private static BufferedImage tryDecodePng(final byte[] png) {
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (final IOException ex) {
            return null;
        }
    }

    /** 진단용: 선두 바이트를 hex로. PNG라면 "89 50 4e 47 0d 0a 1a 0a"로 시작해야 한다. */
    private static String hexPrefix(final byte[] data) {
        final int len = Math.min(PNG_SIGNATURE_LENGTH, data.length);
        final StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02x", data[i] & 0xff));
        }
        return sb.toString();
    }

    /** 최대 변이 maxDimension을 넘으면 비율 유지 축소. 항상 TYPE_INT_RGB로 반환(JPEG 호환). */
    static BufferedImage downscale(final BufferedImage source, final int maxDimension) {
        final int width = source.getWidth();
        final int height = source.getHeight();
        final int longest = Math.max(width, height);
        final double scale = longest > maxDimension ? (double) maxDimension / longest : 1.0;
        final int newWidth = Math.max(1, (int) Math.round(width * scale));
        final int newHeight = Math.max(1, (int) Math.round(height * scale));
        if (scale >= 1.0 && source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        final BufferedImage out = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        final Graphics2D graphics = out.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, newWidth, newHeight, null);
        } finally {
            graphics.dispose();
        }
        return out;
    }

    static byte[] toJpeg(final BufferedImage image, final float quality) throws IOException {
        final ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        final ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    // ===== logcat =====

    public String logcat(final String serial, final String filterSpec, final int tailLines,
        final boolean clearFirst) {
        if (clearFirst) {
            this.runChecked(serial, List.of("shell", "logcat", "-c"), this.properties.commandTimeout(),
                "logcat -c");
            return "Logcat buffer cleared. Reproduce the issue, then call android_logcat again without clearFirst.";
        }
        final List<String> args = new ArrayList<>(List.of(
            "shell", "logcat", "-d", "-v", "threadtime", "-t", String.valueOf(tailLines)));
        for (final String token : filterSpec.trim().split("\\s+")) {
            if (!token.isEmpty()) {
                args.add(token);
            }
        }
        final String output = this.runChecked(serial, args, this.properties.commandTimeout(), "logcat");
        return output.isEmpty() ? "(no log entries matching '" + filterSpec + "')" : output;
    }

    // ===== 텍스트 입력 / IME =====

    /** adb shell input text로 안전하게 보낼 수 있는지 — 그 외(비ASCII/특수문자)는 ADBKeyboard 경유. */
    static boolean isSafeAsciiText(final String text) {
        return text.chars().allMatch(c ->
            (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || SAFE_INPUT_SYMBOLS.indexOf(c) >= 0);
    }

    public void inputAsciiText(final String serial, final String text) {
        // input text의 공백 표기는 %s — 그 외 문자는 isSafeAsciiText 게이트로 이미 걸러짐
        this.runChecked(serial, List.of("shell", "input", "text", text.replace(" ", "%s")),
            this.properties.commandTimeout(), "input text");
    }

    /** 호스트에서 Base64 인코딩해 ADBKeyboard에 브로드캐스트 — 셸 인코딩/이스케이프 함정 회피. */
    public void inputUnicodeViaKeyboard(final String serial, final String text) {
        final String b64 = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        final String output = this.runChecked(serial,
            List.of("shell", "am", "broadcast", "-a", "ADB_INPUT_B64", "--es", "msg", b64),
            this.properties.commandTimeout(), "ADB_INPUT_B64 broadcast");
        // am broadcast는 수신기 처리 결과와 무관하게 항상 "Broadcast completed: result=0"을 출력하므로
        // result 코드는 성공 신호가 못 된다. am 자체 오류만 검출하고, 실제 입력 반영 여부는
        // 호출자가 dump/screenshot으로 확인하도록 안내한다 (도구 반환 메시지 참고).
        if (output.contains("Error") || output.contains("Exception")) {
            throw new IllegalStateException("ADB_INPUT_B64 broadcast failed: " + output);
        }
    }

    public boolean isAdbKeyboardInstalled(final String serial) {
        final ProcessResult result = this.adb.runText(serial,
            List.of("shell", "pm", "list", "packages", ADB_KEYBOARD_PKG), this.properties.commandTimeout());
        return result.success() && result.stdout().contains("package:" + ADB_KEYBOARD_PKG);
    }

    /** 미설치면 설정된 APK로 설치 후 ADBKeyboard를 활성 IME로 전환한다. */
    public void ensureAdbKeyboardActive(final String serial) {
        if (!this.isAdbKeyboardInstalled(serial)) {
            final String apk = this.properties.adbKeyboardApk();
            if (apk == null || apk.isBlank()) {
                throw new IllegalStateException("non-ASCII or special-character text requires the ADBKeyboard IME, "
                    + "which is not installed on the device and app.android.adb-keyboard-apk is not configured. "
                    + "Download ADBKeyboard.apk (github.com/senzhk/ADBKeyBoard), set the property, "
                    + "or use plain ASCII text.");
            }
            this.install(serial, Path.of(apk), true);
            if (!this.isAdbKeyboardInstalled(serial)) {
                throw new IllegalStateException("ADBKeyboard install did not register package " + ADB_KEYBOARD_PKG);
            }
        }
        this.runChecked(serial, List.of("shell", "ime", "enable", ADB_KEYBOARD_IME),
            this.properties.commandTimeout(), "ime enable");
        this.runChecked(serial, List.of("shell", "ime", "set", ADB_KEYBOARD_IME),
            this.properties.commandTimeout(), "ime set");
        // IME 전환 직후 브로드캐스트가 유실되지 않도록 짧게 대기
        sleepQuietly(300L);
    }

    public String currentIme(final String serial) {
        final ProcessResult result = this.adb.runText(serial,
            List.of("shell", "settings", "get", "secure", "default_input_method"),
            this.properties.commandTimeout());
        if (!result.success()) {
            return "";
        }
        final String ime = result.stdout().trim();
        return "null".equals(ime) ? "" : ime;
    }

    /** 기록해 둔 originalIme가 유효하면 그것으로, 아니면 ime reset으로 복원한다. */
    public void restoreIme(final String serial, final String originalIme) {
        if (originalIme != null && !originalIme.isBlank()) {
            this.runChecked(serial, List.of("shell", "ime", "set", originalIme),
                this.properties.commandTimeout(), "ime set (restore)");
        } else {
            this.runChecked(serial, List.of("shell", "ime", "reset"),
                this.properties.commandTimeout(), "ime reset");
        }
    }

    // ===== 네트워크 제어 =====

    /**
     * 무선(TCP/IP·mDNS) adb 연결인지 판별한다. 무선 연결에서 wifi를 끄면 adb 제어 채널 자체가
     * 끊겨 기기를 되살릴 수 없으므로 {@link #setOffline}을 거부하는 데 쓴다.
     * 무선 serial 형태: "192.168.0.10:5555"(TCP/IP) 또는
     * "adb-XXXX._adb-tls-connect._tcp"(Android 11+ 무선 디버깅). USB serial엔 콜론이 없고,
     * 에뮬레이터는 콘솔 포트("emulator-5554")라 무선이 아니다.
     */
    static boolean isWirelessSerial(final String serial) {
        if (serial == null || serial.startsWith("emulator-")) {
            return false;
        }
        return serial.contains(":") || serial.contains("_adb-tls-");
    }

    /** wifi와 모바일 데이터를 함께 토글한다 (svc — 실기기/에뮬레이터 공통). */
    public void setOffline(final String serial, final boolean offline) {
        if (isWirelessSerial(serial)) {
            throw new IllegalStateException("android_set_offline is disabled over a wireless adb connection ("
                + serial + "): disabling wifi would sever the adb control channel and the device could not be "
                + "recovered without physical access. Connect the device over USB to test offline behavior, "
                + "or use android_set_http_proxy / a server-side network fault to simulate connectivity loss.");
        }
        final String op = offline ? "disable" : "enable";
        this.runChecked(serial, List.of("shell", "svc", "wifi", op),
            this.properties.commandTimeout(), "svc wifi " + op);
        this.runChecked(serial, List.of("shell", "svc", "data", op),
            this.properties.commandTimeout(), "svc data " + op);
    }

    /**
     * 에뮬레이터 콘솔 명령으로 네트워크 속도/지연을 흉내낸다 (adb emu — 에뮬레이터 전용).
     * speed: "full"(해제), "gsm", "edge", "3g", "lte", 또는 "up:down" kbps.
     * delay: "none"(해제), "gprs", "edge", 또는 "min:max" ms.
     */
    public void setNetworkConditions(final String serial, final String speed, final String delay) {
        if (serial == null || !serial.startsWith("emulator-")) {
            throw new IllegalStateException("network speed/delay emulation requires an emulator "
                + "(serial starting with 'emulator-'); current device: " + serial
                + ". On physical devices use android_set_offline or android_set_http_proxy instead.");
        }
        if ((speed == null || speed.isBlank()) && (delay == null || delay.isBlank())) {
            throw new IllegalStateException("specify speed and/or delay");
        }
        if (speed != null && !speed.isBlank()) {
            this.runEmuChecked(serial, List.of("emu", "network", "speed", speed.trim()), "emu network speed");
        }
        if (delay != null && !delay.isBlank()) {
            this.runEmuChecked(serial, List.of("emu", "network", "delay", delay.trim()), "emu network delay");
        }
    }

    /** adb emu는 콘솔 응답("OK"/"KO: ...")을 stdout으로 돌려준다 — exit 0이어도 KO면 실패. */
    private void runEmuChecked(final String serial, final List<String> args, final String what) {
        final String output = this.runChecked(serial, args, this.properties.commandTimeout(), what);
        if (output.lines().anyMatch(line -> line.trim().startsWith("KO"))) {
            throw new IllegalStateException(what + " rejected by emulator console: " + output);
        }
    }

    /**
     * 기기 전역 HTTP 프록시를 설정한다 (settings put global http_proxy).
     * hostPort가 비었거나 ":0"이면 해제. 모킹 응답은 외부 프록시(mitmproxy 등) 책임.
     */
    public void setHttpProxy(final String serial, final String hostPort) {
        final String value = hostPort == null || hostPort.isBlank() ? ":0" : hostPort.trim();
        if (!":0".equals(value) && !value.matches("[A-Za-z0-9.\\-]+:\\d+")) {
            throw new IllegalStateException("invalid proxy value '" + value
                + "' — expected 'host:port' (e.g. 10.0.2.2:8888) or ':0' to clear");
        }
        this.runChecked(serial, List.of("shell", "settings", "put", "global", "http_proxy", value),
            this.properties.commandTimeout(), "settings put global http_proxy");
    }

    // ===== 파일 푸시 =====

    /** 호스트 파일을 기기로 복사하고, 요청 시 미디어 스캐너 브로드캐스트로 갤러리에 노출시킨다. */
    public String pushFile(final String serial, final Path localPath, final String devicePath,
        final boolean scanMedia) {
        final ProcessResult result = this.adb.runText(serial,
            List.of("push", localPath.toString(), devicePath), this.properties.installTimeout());
        if (result.timedOut()) {
            throw new IllegalStateException("adb push timed out after "
                + this.properties.installTimeout().toSeconds() + "s");
        }
        if (result.exitCode() != 0) {
            throw new IllegalStateException("adb push failed: " + result.stdout());
        }
        if (scanMedia) {
            this.runChecked(serial, List.of("shell", "am", "broadcast",
                    "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://" + devicePath),
                this.properties.commandTimeout(), "media scan broadcast");
        }
        return result.stdout().trim();
    }

    // ===== 화면 녹화 =====

    private static final String RECORD_DEVICE_PATH = "/sdcard/namsic_record.mp4";

    /**
     * screenrecord로 durationMs 동안 녹화한 mp4를 {@code adb pull}로 호스트에 저장한다
     * (pull은 파일을 직접 쓰므로 stdout CRLF 변형 문제가 없다). 디바이스 측 한계로 최대 180초.
     * 호출자는 녹화 시간을 감당하는 await로 {@link AndroidSession#submitWithTimeout}을 써야 한다.
     */
    public Path recordScreen(final String serial, final int durationMs, final Integer bitRateMbps,
        final Path dir, final String filename) {
        final int seconds = Math.max(1, (int) Math.ceil(durationMs / 1000.0));
        final List<String> args = new ArrayList<>(List.of("shell", "screenrecord",
            "--time-limit", String.valueOf(seconds)));
        if (bitRateMbps != null && bitRateMbps > 0) {
            args.add("--bit-rate");
            args.add(String.valueOf(bitRateMbps * 1_000_000));
        }
        args.add(RECORD_DEVICE_PATH);
        final Duration recordTimeout = Duration.ofSeconds(seconds + 30L);
        final ProcessResult record = this.adb.runText(serial, args, recordTimeout);
        if (record.timedOut()) {
            throw new IllegalStateException("screenrecord timed out after " + recordTimeout.toSeconds() + "s");
        }
        if (record.exitCode() != 0) {
            throw new IllegalStateException("screenrecord failed (exit " + record.exitCode() + "): "
                + record.stdout());
        }
        final Path hostPath = dir.resolve(filename);
        final ProcessResult pull = this.adb.runText(serial,
            List.of("pull", RECORD_DEVICE_PATH, hostPath.toString()), this.properties.installTimeout());
        if (pull.timedOut() || pull.exitCode() != 0) {
            throw new IllegalStateException("adb pull of the recording failed: "
                + (pull.timedOut() ? "timed out" : pull.stdout()));
        }
        if (!Files.isRegularFile(hostPath)) {
            throw new IllegalStateException("adb pull reported success but " + hostPath + " is missing");
        }
        final ProcessResult rm = this.adb.runText(serial, List.of("shell", "rm", "-f", RECORD_DEVICE_PATH),
            this.properties.commandTimeout());
        if (!rm.success()) {
            log.warn("failed to remove device-side recording file: {}", rm.stdout());
        }
        return hostPath;
    }

    // ===== 픽셀 샘플링 =====

    /** 다운스케일 없는 원본 PNG 캡처 — 픽셀 샘플링용 (좌표가 기기 픽셀과 1:1). */
    public BufferedImage screenshotRaw(final String serial) {
        final AdbBinaryResult result = this.adb.runBinary(serial, List.of("exec-out", "screencap", "-p"),
            this.properties.commandTimeout());
        if (result.isFail() || result.stdout().length == 0) {
            throw new IllegalStateException("screencap failed: "
                + (result.timedOut() ? "timed out" : result.stderr()));
        }
        try {
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.stdout()));
            if (image == null) {
                throw new IllegalStateException("screencap returned invalid PNG data ("
                    + result.stdout().length + " bytes)");
            }
            return image;
        } catch (final IOException ex) {
            throw new IllegalStateException("Failed to decode screenshot: " + ex.getMessage(), ex);
        }
    }

    // ===== 공통 =====

    private String runChecked(final String serial, final List<String> args, final Duration timeout,
        final String what) {
        final ProcessResult result = this.adb.runText(serial, args, timeout);
        if (result.timedOut()) {
            throw new IllegalStateException(what + " timed out after " + timeout.toSeconds() + "s");
        }
        if (result.exitCode() != 0) {
            throw new IllegalStateException(what + " failed (exit " + result.exitCode() + "): " + result.stdout());
        }
        // input 계열은 인자 오류 시에도 exit 0으로 "Error: ..."만 출력하는 경우가 있음
        if (result.stdout().startsWith("Error")) {
            throw new IllegalStateException(what + " failed: " + result.stdout());
        }
        return result.stdout();
    }

    private static void sleepQuietly(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", ex);
        }
    }
}
