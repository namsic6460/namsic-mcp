package lkd.namsic.mcp.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class ProcessBuilders {

    public record ProcessResult(int exitCode, String stdout, boolean timedOut) {
        public boolean success() {
            return !this.timedOut && this.exitCode == 0;
        }
    }

    private ProcessBuilders() {
    }

    public static ProcessResult runWithTimeout(List<String> command, Duration timeout) {
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        final Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            log.warn("Failed to start process: cmd={}, reason={}", command, ex.getMessage());
            return new ProcessResult(-1, "", false);
        }

        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!finished) {
                process.destroyForcibly();
                return new ProcessResult(-1, output, true);
            }
            return new ProcessResult(process.exitValue(), output, false);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new ProcessResult(-1, "", true);
        } catch (IOException ex) {
            log.warn("Failed to read process output: cmd={}, reason={}", command, ex.getMessage());
            return new ProcessResult(-1, "", false);
        }
    }
}
