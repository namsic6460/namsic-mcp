package lkd.namsic.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app.local-llm")
public record LocalLlmProperties(URI baseUrl, String model, Duration requestTimeout,
                                 Integer queueCapacity, Integer maxRetainedJobs,
                                 Duration retention, List<Path> imageRoots) {
    public LocalLlmProperties {
        if (baseUrl == null) baseUrl = URI.create("http://127.0.0.1:9000");
        if (!"http".equals(baseUrl.getScheme()) || baseUrl.getUserInfo() != null
            || baseUrl.getQuery() != null || baseUrl.getFragment() != null
            || !(baseUrl.getPath().isEmpty() || "/".equals(baseUrl.getPath()))
            || !List.of("127.0.0.1", "localhost", "[::1]", "::1").contains(baseUrl.getHost())) {
            throw new IllegalArgumentException("app.local-llm.base-url must be a loopback HTTP origin");
        }
        if (model == null || model.isBlank()) model = "qwen3.8-27b";
        if (requestTimeout == null) requestTimeout = Duration.ofMinutes(3);
        if (queueCapacity == null) queueCapacity = 8;
        if (maxRetainedJobs == null) maxRetainedJobs = 100;
        if (retention == null) retention = Duration.ofMinutes(30);
        if (requestTimeout.isNegative() || requestTimeout.isZero() || queueCapacity < 1
            || maxRetainedJobs < queueCapacity + 1 || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("Invalid local LLM limits");
        }
        imageRoots = imageRoots == null ? List.of() : List.copyOf(imageRoots);
    }
}
