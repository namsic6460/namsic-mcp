package lkd.namsic.mcp.config;

import lkd.namsic.mcp.browser.BrowserMcpTools;
import lkd.namsic.mcp.devserver.DevServerMcpTools;
import lkd.namsic.mcp.session.ProjectInitTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider namsicToolCallbackProvider(
        ProjectInitTool projectInitTool,
        BrowserMcpTools browserMcpTools,
        DevServerMcpTools devServerMcpTools
    ) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(projectInitTool, browserMcpTools, devServerMcpTools)
            .build();
    }
}
