package lkd.namsic.mcp.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Bumps the last-activity timestamp for the MCP transport session referenced by an incoming
 * HTTP request. The MCP spec uses the {@code Mcp-Session-Id} header (case-insensitive) to
 * carry the session identifier on every POST / GET / DELETE to the streamable HTTP endpoint;
 * this filter watches it on every request so {@link McpSessionConfig#cleanupStaleSessions()}
 * can apply an idle timeout instead of a hard wall-clock lifetime.
 */
@Component
public class McpSessionActivityFilter extends OncePerRequestFilter {

    private static final String MCP_SESSION_ID_HEADER = "Mcp-Session-Id";

    private final McpSessionConfig sessionConfig;

    public McpSessionActivityFilter(McpSessionConfig sessionConfig) {
        this.sessionConfig = sessionConfig;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String sessionId = request.getHeader(MCP_SESSION_ID_HEADER);
        if (sessionId != null && !sessionId.isBlank()) {
            this.sessionConfig.recordActivity(sessionId);
        }
        chain.doFilter(request, response);
    }
}
