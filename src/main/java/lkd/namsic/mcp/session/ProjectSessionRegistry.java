package lkd.namsic.mcp.session;

import lkd.namsic.mcp.config.BrowserProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ProjectSessionRegistry {

    private final Path screenshotBaseDir;

    private final Map<String, Project> byId = new ConcurrentHashMap<>();
    private final Map<String, String> byName = new ConcurrentHashMap<>();

    public ProjectSessionRegistry(BrowserProperties browserProperties) {
        this.screenshotBaseDir = browserProperties.screenshotBaseDir();
    }

    public record Project(String sessionId, String projectName, Path screenshotDir, Instant createdAt) {
    }

    public Project init(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            throw new IllegalArgumentException("projectName is required");
        }
        String normalized = sanitize(projectName);
        String existingId = this.byName.get(normalized);
        if (existingId != null) {
            Project existing = this.byId.get(existingId);
            if (existing != null) {
                return existing;
            }
        }
        String sessionId = UUID.randomUUID().toString();
        Path dir = this.screenshotBaseDir.resolve(normalized);
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create screenshot directory: " + dir, ex);
        }
        Project project = new Project(sessionId, normalized, dir, Instant.now());
        this.byId.put(sessionId, project);
        this.byName.put(normalized, sessionId);
        return project;
    }

    public Project require(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("sessionId is required. Call project_init first to obtain one.");
        }
        Project project = this.byId.get(sessionId);
        if (project == null) {
            throw new IllegalStateException(
                "Unknown sessionId=" + sessionId + ". Call project_init first to obtain a valid session.");
        }
        return project;
    }

    public Project remove(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        Project project = this.byId.remove(sessionId);
        if (project != null) {
            this.byName.remove(project.projectName());
        }
        return project;
    }

    public Map<String, Project> getProjects() {
        return this.byId;
    }

    public static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "default";
        }
        String replaced = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return replaced.isBlank() ? "default" : replaced;
    }
}
