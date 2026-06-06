package lkd.namsic.mcp.android;

import lkd.namsic.mcp.config.AndroidProperties;
import lkd.namsic.mcp.util.ProcessBuilders;
import lkd.namsic.mcp.util.ProcessBuilders.ProcessResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * adb 호출의 단일 통로. adbPath 주입, {@code -s <serial>} 조립, 타임아웃을 책임진다.
 * 텍스트 명령은 {@link ProcessBuilders}에 위임하고, 바이너리(screencap 등)만 자체 구현으로
 * stdout 원시 바이트를 보존한다 — 텍스트 러너를 거치면 Windows에서 PNG가 CRLF 변환으로 깨진다.
 */
@Slf4j
@Component
public class AdbCommandRunner {

    private final AndroidProperties properties;

    public AdbCommandRunner(final AndroidProperties properties) {
        this.properties = properties;
    }

    public record AdbBinaryResult(int exitCode, byte[] stdout, String stderr, boolean timedOut) {

        public boolean isFail() {
            return this.timedOut || this.exitCode != 0;
        }
    }

    /** 텍스트 출력 adb 명령. serial이 null이면 {@code -s} 없이 실행한다 (adb devices 등). */
    public ProcessResult runText(final String serial, final List<String> adbArgs, final Duration timeout) {
        return ProcessBuilders.runWithTimeout(this.buildCommand(serial, adbArgs), timeout);
    }

    /**
     * 바이너리 출력 adb 명령 (예: {@code exec-out screencap -p}).
     * stdout/stderr를 별도 데몬 스레드로 읽어 파이프 버퍼 데드락을 방지한다.
     */
    public AdbBinaryResult runBinary(final String serial, final List<String> adbArgs, final Duration timeout) {
        final List<String> command = this.buildCommand(serial, adbArgs);
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(false);
        final Process process;
        try {
            process = builder.start();
        } catch (final IOException ex) {
            log.warn("Failed to start adb process: cmd={}, reason={}", command, ex.getMessage());
            return new AdbBinaryResult(-1, new byte[0], ex.getMessage(), false);
        }

        final ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream(1 << 16);
        final ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
        final Thread stdoutReader = streamReader("adb-stdout-reader", process.getInputStream(), stdoutBuf);
        final Thread stderrReader = streamReader("adb-stderr-reader", process.getErrorStream(), stderrBuf);

        try {
            final boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                stdoutReader.join(2_000L);
                stderrReader.join(2_000L);
                return new AdbBinaryResult(-1, stdoutBuf.toByteArray(),
                    stderrBuf.toString(StandardCharsets.UTF_8).trim(), true);
            }
            // 프로세스가 종료됐으므로 파이프 EOF가 보장된다 — 타임아웃 없는 join으로
            // 마지막 버퍼 청크까지 수집해 silent 절단을 막는다.
            stdoutReader.join();
            stderrReader.join();
            return new AdbBinaryResult(process.exitValue(), stdoutBuf.toByteArray(),
                stderrBuf.toString(StandardCharsets.UTF_8).trim(), false);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new AdbBinaryResult(-1, new byte[0], "", true);
        }
    }

    List<String> buildCommand(final String serial, final List<String> adbArgs) {
        final List<String> command = new ArrayList<>(adbArgs.size() + 3);
        command.add(this.properties.adbPath());
        if (serial != null && !serial.isBlank()) {
            command.add("-s");
            command.add(serial);
        }
        command.addAll(adbArgs);
        return command;
    }

    private static Thread streamReader(final String name, final InputStream in, final ByteArrayOutputStream out) {
        final Thread thread = new Thread(() -> {
            try (in) {
                in.transferTo(out);
            } catch (final IOException ex) {
                log.debug("{} read ended: {}", name, ex.getMessage());
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
