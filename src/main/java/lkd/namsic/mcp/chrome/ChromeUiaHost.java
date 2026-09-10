package lkd.namsic.mcp.chrome;

import jakarta.annotation.PreDestroy;
import lkd.namsic.mcp.config.ChromeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * chrome-uia-host.ps1 을 상주 PowerShell 프로세스로 띄우고 한 줄 명령/응답을 주고받는다.
 * <p>
 * 상주시키는 이유는 비용 때문이다 — Add-Type C# 컴파일과 UIAutomation 어셈블리 로드가
 * 호출당 1.5초 넘게 들어, 일회성 호출로 만들면 스크린샷마다 그 값을 다시 치르게 된다.
 * <p>
 * 모든 명령은 단일 스레드 executor 로 직렬화한다. 프로토콜이 "명령 1줄 → 응답 N줄 → END" 라
 * 동시 호출이 섞이면 응답을 서로 훔쳐가기 때문이다.
 */
@Slf4j
@Component
public class ChromeUiaHost {

    private static final String SCRIPT_RESOURCE = "/chrome/chrome-uia-host.ps1";
    private static final String SCRIPT_FILE_NAME = "chrome-uia-host.ps1";
    private static final String END_MARKER = "<<<END>>>";
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final long STOP_GRACE_MS = 2_000L;

    private final ChromeProperties properties;
    private final ExecutorService executor;

    private volatile Path scriptPath;
    private volatile Process process;
    private volatile BufferedWriter toHost;
    private volatile BufferedReader fromHost;

    public ChromeUiaHost(final ChromeProperties properties) {
        this.properties = properties;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "chrome-uia-host");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 실행 중인 모든 크롬 창/탭의 W·T 레코드. */
    public List<String> listTabs() {
        return this.command("LIST");
    }

    /** 지정 창의 tabIndex 번째 탭을 선택한다. 최소화된 창은 먼저 복원된다. */
    public List<String> activateTab(final long hwnd, final int tabIndex, final boolean bringToFront) {
        return this.command("ACTIVATE\t" + hwnd + '\t' + tabIndex + '\t' + (bringToFront ? '1' : '0'));
    }

    private List<String> command(final String line) {
        // 콜드 스타트면 Add-Type 컴파일 시간까지 기다려 줘야 첫 호출이 타임아웃으로 죽지 않는다.
        final long awaitMs = this.properties.commandTimeout().toMillis()
            + (this.isRunning() ? 0L : this.properties.startupTimeout().toMillis());

        final Future<List<String>> future = this.executor.submit(() -> this.exchange(line));
        try {
            return future.get(awaitMs, TimeUnit.MILLISECONDS);
        } catch (final ExecutionException ex) {
            final Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause != null ? cause.getMessage() : ex.getMessage(), cause);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IllegalStateException("Interrupted while talking to the Chrome UIA host", ex);
        } catch (final TimeoutException ex) {
            // 블로킹 readLine 은 인터럽트로 풀리지 않는다 — 프로세스를 죽여 EOF 를 만들어야
            // 고아 task 가 단일 스레드 executor 를 영구 점유하는 것을 막을 수 있다.
            this.stopProcess();
            future.cancel(true);
            throw new IllegalStateException("Chrome UIA host timed out after " + awaitMs + " ms");
        }
    }

    private List<String> exchange(final String line) {
        this.ensureStarted();
        // 스트림을 지역 변수로 붙잡는다 — 타임아웃 경로의 stopProcess()가 필드를 null 로 바꿔도
        // 진행 중인 교환이 NPE 대신 정상적인 EOF 오류로 끝나게 하기 위함이다.
        final BufferedWriter writer = this.toHost;
        final BufferedReader reader = this.fromHost;
        try {
            if (writer == null || reader == null) {
                throw new IOException("Chrome UIA host was stopped");
            }
            writeLine(writer, line);
            return readUntilEnd(reader);
        } catch (final IOException ex) {
            this.stopProcess();
            throw new IllegalStateException("Chrome UIA host communication failed: " + ex.getMessage(), ex);
        }
    }

    private void ensureStarted() {
        if (this.isRunning()) {
            return;
        }
        this.stopProcess();

        final List<String> command = List.of(this.properties.powershellPath(),
            "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", this.ensureScriptExtracted().toString());
        log.info("Starting Chrome UIA host: {}", command);

        final Process started;
        try {
            started = new ProcessBuilder(command).redirectErrorStream(false).start();
        } catch (final IOException ex) {
            throw new IllegalStateException("Failed to start PowerShell ('" + this.properties.powershellPath()
                + "'). Chrome tab control is Windows-only and needs PowerShell on PATH. Reason: "
                + ex.getMessage(), ex);
        }
        this.process = started;
        // 명령은 전부 ASCII 다. PowerShell 이 파이프 stdin 을 OEM 코드페이지로 디코딩하므로
        // 비ASCII 를 보내면 깨진다 — 탭 제목 매칭을 Java 쪽에 둔 이유이기도 하다.
        final BufferedWriter writer = new BufferedWriter(
            new OutputStreamWriter(started.getOutputStream(), StandardCharsets.US_ASCII));
        final BufferedReader reader = new BufferedReader(
            new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8));
        this.toHost = writer;
        this.fromHost = reader;
        drainStderr(started.getErrorStream());
        this.handshake(writer, reader);
    }

    /** PING/PONG 으로 기동을 확인하면서 Add-Type 비용을 여기서 미리 치른다. */
    private void handshake(final BufferedWriter writer, final BufferedReader reader) {
        try {
            writeLine(writer, "PING");
            final List<String> reply = readUntilEnd(reader);
            if (reply.stream().noneMatch(replyLine -> "PONG".equals(replyLine.trim()))) {
                throw new IOException("unexpected handshake reply: " + reply);
            }
        } catch (final IOException ex) {
            this.stopProcess();
            throw new IllegalStateException("Chrome UIA host failed to start: " + ex.getMessage(), ex);
        }
    }

    private static void writeLine(final BufferedWriter writer, final String line) throws IOException {
        writer.write(line);
        writer.write('\n');
        writer.flush();
    }

    private static List<String> readUntilEnd(final BufferedReader reader) throws IOException {
        final List<String> lines = new ArrayList<>();
        while (true) {
            final String line = reader.readLine();
            if (line == null) {
                throw new IOException("Chrome UIA host exited unexpectedly");
            }
            if (END_MARKER.equals(line.trim())) {
                return lines;
            }
            lines.add(line);
        }
    }

    /**
     * 클래스패스의 스크립트를 임시 파일로 꺼낸다 (jar 안의 리소스는 -File 로 실행할 수 없다).
     * Windows PowerShell 5.1 은 BOM 없는 파일을 ANSI 로 읽어 한글 주석이 깨지므로 BOM 을 붙인다.
     */
    private Path ensureScriptExtracted() {
        final Path cached = this.scriptPath;
        if (cached != null && Files.isReadable(cached)) {
            return cached;
        }
        try (final InputStream in = ChromeUiaHost.class.getResourceAsStream(SCRIPT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + SCRIPT_RESOURCE);
            }
            final byte[] body = in.readAllBytes();
            final Path dir = Files.createTempDirectory("namsic-chrome-uia");
            final Path file = dir.resolve(SCRIPT_FILE_NAME);
            try (final OutputStream out = Files.newOutputStream(file)) {
                if (!startsWithBom(body)) {
                    out.write(UTF8_BOM);
                }
                out.write(body);
            }
            file.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            this.scriptPath = file;
            return file;
        } catch (final IOException ex) {
            throw new IllegalStateException("Failed to extract the Chrome UIA host script: " + ex.getMessage(), ex);
        }
    }

    private boolean isRunning() {
        final Process current = this.process;
        return current != null && current.isAlive();
    }

    private synchronized void stopProcess() {
        final Process current = this.process;
        this.process = null;
        this.toHost = null;
        this.fromHost = null;
        if (current == null) {
            return;
        }
        current.destroy();
        try {
            if (!current.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) {
                current.destroyForcibly();
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }

    @PreDestroy
    public void close() {
        final BufferedWriter writer = this.toHost;
        if (writer != null && this.isRunning()) {
            try {
                writer.write("EXIT\n");
                writer.flush();
            } catch (final IOException ex) {
                log.debug("Chrome UIA host EXIT write failed: {}", ex.getMessage());
            }
        }
        this.stopProcess();
        this.executor.shutdownNow();
    }

    private static boolean startsWithBom(final byte[] body) {
        return body.length >= UTF8_BOM.length
            && body[0] == UTF8_BOM[0] && body[1] == UTF8_BOM[1] && body[2] == UTF8_BOM[2];
    }

    private static void drainStderr(final InputStream stderr) {
        final Thread thread = new Thread(() -> {
            try (final BufferedReader reader =
                     new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        log.warn("chrome-uia-host stderr: {}", line);
                    }
                }
            } catch (final IOException ex) {
                log.debug("chrome-uia-host stderr reader ended: {}", ex.getMessage());
            }
        }, "chrome-uia-host-stderr");
        thread.setDaemon(true);
        thread.start();
    }
}
