package lkd.namsic.mcp.config;

import lkd.namsic.mcp.devserver.DevServerMcpTools;
import lkd.namsic.mcp.session.ProjectInitTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolConfig {

    // BrowserMcpTools는 @McpTool 어노테이션 스캐너로 별도 등록된다 (CallToolResult 반환을 위해
    // @Tool 경로를 쓰지 않음). 여기 toolObjects에 다시 넣으면 이름 dedup으로 이중 등록 충돌이 난다.
    @Bean
    public ToolCallbackProvider namsicToolCallbackProvider(
        ProjectInitTool projectInitTool,
        DevServerMcpTools devServerMcpTools
    ) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(projectInitTool, devServerMcpTools)
            .build();
    }
}
