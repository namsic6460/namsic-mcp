package lkd.namsic.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;

@ConfigurationProperties(prefix = "app.local-llm.memory")
public record LocalLlmMemoryProperties(Path directory) {
    public LocalLlmMemoryProperties {
        if (directory == null) directory = Path.of(System.getProperty("user.home"), ".namsic-mcp", "llm-memory");
    }
}
