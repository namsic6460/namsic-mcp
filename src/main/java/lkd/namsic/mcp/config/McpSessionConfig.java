package lkd.namsic.mcp.config;

import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@Slf4j
public class McpSessionConfig implements SmartLifecycle {

    private static final Duration SESSION_MAX_IDLE = Duration.ofHours(3);

    @Nullable
    private final McpServerTransportProviderBase transportProvider;

    @Nullable
    private final Map<String, McpStreamableServerSession> sessionsRef;

    /** Last time a request bearing this session ID was observed (or first-seen if never used yet). */
    private final ConcurrentHashMap<String, Instant> sessionLastActivity = new ConcurrentHashMap<>();

    private volatile boolean running;

    public McpSessionConfig(@Nullable McpServerTransportProviderBase transportProvider) {
        this.transportProvider = transportProvider;
        this.sessionsRef = this.extractSessionsMap(transportProvider);
    }

    /**
     * Bump the last-activity timestamp for the given MCP transport session. Called by the
     * activity filter on every HTTP request that carries an {@code Mcp-Session-Id} header so
     * the idle-timeout cleanup can distinguish active sessions from abandoned ones.
     */
    public void recordActivity(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        this.sessionLastActivity.put(sessionId, Instant.now());
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void cleanupStaleSessions() {
        if (this.sessionsRef == null) {
            return;
        }

        Instant now = Instant.now();

        // Seed last-activity for newly-observed sessions so brand-new connections that haven't
        // sent a request yet still get the full idle window before being culled.
        for (String sessionId : this.sessionsRef.keySet()) {
            this.sessionLastActivity.putIfAbsent(sessionId, now);
        }

        this.sessionLastActivity.keySet().removeIf(id -> !this.sessionsRef.containsKey(id));

        for (Map.Entry<String, Instant> entry : this.sessionLastActivity.entrySet()) {
            String sessionId = entry.getKey();
            Duration idle = Duration.between(entry.getValue(), now);

            if (idle.compareTo(SESSION_MAX_IDLE) <= 0) {
                continue;
            }

            McpStreamableServerSession session = this.sessionsRef.get(sessionId);
            if (session == null) {
                continue;
            }

            log.info("Closing idle MCP session {} (idle={}m)", sessionId, idle.toMinutes());
            try {
                session.closeGracefully().block(Duration.ofSeconds(5));
            } catch (RuntimeException ex) {
                log.debug("Failed to close idle MCP session {} gracefully, forcing close", sessionId, ex);
                try {
                    session.close();
                } catch (RuntimeException forceEx) {
                    log.debug("Force close of MCP session {} also failed", sessionId, forceEx);
                }
            }
            this.sessionsRef.remove(sessionId);
            this.sessionLastActivity.remove(sessionId);
        }
    }

    @Override
    public void start() {
        this.running = true;
    }

    @Override
    public void stop() {
        if (this.transportProvider != null) {
            log.info("Closing MCP SSE sessions gracefully...");
            try {
                this.transportProvider.closeGracefully().block(Duration.ofSeconds(10));
            } catch (RuntimeException ex) {
                log.debug("Failed to close MCP sessions gracefully during shutdown", ex);
            }
            log.info("MCP SSE sessions closed.");
        }
        this.running = false;
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private Map<String, McpStreamableServerSession> extractSessionsMap(
        @Nullable McpServerTransportProviderBase provider
    ) {
        if (!(provider instanceof WebMvcStreamableServerTransportProvider)) {
            return null;
        }
        try {
            Field field = WebMvcStreamableServerTransportProvider.class.getDeclaredField("sessions");
            field.setAccessible(true);
            return (Map<String, McpStreamableServerSession>) field.get(provider);
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            log.warn("Failed to access MCP sessions map via reflection; session cleanup disabled", ex);
            return null;
        }
    }
}
