package lkd.namsic.mcp.session;

import lkd.namsic.mcp.session.ProjectSessionRegistry.Project;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectInitTool {

    private final ProjectSessionRegistry registry;

    @Tool(name = "project_init", description = "Initialize a project-scoped session. "
        + "Returns a UUID sessionId that MUST be passed to every subsequent browser_* and dev_server_* tool call. "
        + "The projectName is used as the screenshot subdirectory name under the screenshot base dir. "
        + "Calling project_init again with the same projectName is idempotent and returns the existing sessionId. "
        + "Dev servers are registered independently under app.dev-servers (use dev_server_list to see them).")
    public String projectInit(
        @ToolParam(description = "Project name used as the screenshot subdirectory name. "
            + "Characters outside [A-Za-z0-9._-] are replaced with '_'.") final String projectName
    ) {
        log.info("MCP tool invoked: project_init projectName={}", projectName);
        final Project project;
        try {
            project = this.registry.init(projectName);
        } catch (final IllegalArgumentException ex) {
            return "Error initializing project session: " + ex.getMessage();
        }

        return "sessionId: " + project.sessionId() + '\n'
            + "project: " + project.projectName() + '\n'
            + "screenshotDir: " + project.screenshotDir().toAbsolutePath() + "\n\n"
            + "Pass this sessionId to every browser_* and dev_server_* tool call in this conversation. "
            + "Call dev_server_list to see registered dev servers.";
    }
}
