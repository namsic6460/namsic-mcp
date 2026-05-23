package lkd.namsic.mcp.browser;

import jakarta.annotation.PreDestroy;
import lkd.namsic.mcp.config.BrowserProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class BrowserServerService {

    private static final Map<String, String> ALLOWED_BROWSER_TYPES = Map.of(
        "chromium", "chromium",
        "firefox", "firefox",
        "webkit", "webkit"
    );

    private final BrowserProperties properties;
    private final Map<String, BrowserProcess> processes = new ConcurrentHashMap<>();

    record BrowserProcess(Process process, String wsEndpoint) {}

    public BrowserServerService(BrowserProperties properties) {
        this.properties = properties;
    }

    public String startBrowserServer(String sessionId) {
        BrowserProcess existing = this.processes.get(sessionId);
        if (existing != null && existing.process().isAlive()) {
            log.info("Reusing existing browser server for sessionId={}", sessionId);
            return existing.wsEndpoint();
        }

        String nodePath = this.properties.nodePath();
        String configuredType = this.properties.browserType();
        String browserType = ALLOWED_BROWSER_TYPES.get(configuredType.toLowerCase());
        if (browserType == null) {
            throw new IllegalStateException("Unsupported browser type: " + configuredType
                + " (allowed: chromium, firefox, webkit)");
        }
        Duration timeout = this.properties.startupTimeout();
        boolean headless = Boolean.TRUE.equals(this.properties.headless());

        String launchArgsJson = this.buildLaunchArgsJson();
        String script = String.format(
            "const { %s } = require('playwright');"
                + " %s.launchServer({ headless: %s%s })"
                + ".then(s => { console.log(s.wsEndpoint()); })",
            browserType, browserType, headless, launchArgsJson
        );

        try {
            ProcessBuilder pb = new ProcessBuilder(nodePath, "-e", script);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            String wsEndpoint = this.readWsEndpoint(process, timeout).orElseThrow(() -> {
                process.destroyForcibly();
                return new IllegalStateException("Browser server did not return ws endpoint within " + timeout);
            });

            this.processes.put(sessionId, new BrowserProcess(process, wsEndpoint));
            log.info("Browser server started for sessionId={}, endpoint={}", sessionId, wsEndpoint);
            return wsEndpoint;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to start browser server", ex);
        }
    }

    public void stopBrowserServer(String sessionId) {
        BrowserProcess bp = this.processes.remove(sessionId);
        if (bp != null && bp.process().isAlive()) {
            bp.process().destroyForcibly();
            log.info("Browser server stopped for sessionId={}", sessionId);
        }
    }

    @PreDestroy
    public void destroyAll() {
        log.info("Destroying all browser server processes (count={})", this.processes.size());
        for (Map.Entry<String, BrowserProcess> entry : this.processes.entrySet()) {
            Process process = entry.getValue().process();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        this.processes.clear();
    }

    private Optional<String> readWsEndpoint(Process process, Duration timeout) {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line != null && line.startsWith("ws://")) {
                        return Optional.of(line.trim());
                    }
                }
                if (!process.isAlive()) {
                    break;
                }
                TimeUnit.MILLISECONDS.sleep(100);
            }
        } catch (IOException ex) {
            log.error("Error reading browser server output", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while reading browser server output", ex);
        }
        return Optional.empty();
    }

    String buildLaunchArgsJson() {
        if (CollectionUtils.isEmpty(this.properties.launchArgs())) {
            return "";
        }
        StringBuilder sb = new StringBuilder(", args: [");
        boolean first = true;
        for (String arg : this.properties.launchArgs()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escapeJsonString(arg)).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String escapeJsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    Map<String, BrowserProcess> getProcesses() {
        return this.processes;
    }
}
