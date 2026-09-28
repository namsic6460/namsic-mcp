package lkd.namsic.mcp.llm;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@RequiredArgsConstructor
public class LocalLlmMcpTools {
    private final LocalLlmService service;
    private final JsonMapper json = JsonMapper.builder().build();

    @McpTool(name = "local_llm_status", description = "Check local Qwen health and queue. No inference is submitted.")
    public CallToolResult status() {
        return this.respond(this.service::status);
    }

    @McpTool(name = "local_llm_vision", description = "Queue image observation on local Qwen. Returns jobId immediately; "
        + "poll local_llm_result. One or two PNG/JPEG paths on the MCP host, before then after. "
        + "Paths must be in the project screenshot directory or configured image-roots. "
        + "Manager selects referenceMemory; it is background, not evidence. No device actions or final defect verdict.")
    public CallToolResult vision(
        @McpToolParam(description = "Session from project_init") String sessionId,
        @McpToolParam(description = "One or two absolute image paths") List<String> imagePaths,
        @McpToolParam(description = "What to observe") String task,
        @McpToolParam(description = "Selected reference rules/cases; omit if unnecessary", required = false) String referenceMemory) {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("task", task);
        context.put("referenceMemory", referenceMemory == null ? "" : referenceMemory);
        return this.respond(() -> this.service.submit(sessionId, imagePaths, context, false));
    }

    @McpTool(name = "local_llm_triage", description = "Queue checklist image triage on local Qwen. Returns jobId; "
        + "poll local_llm_result. ok means no triage concern, suspect means requires review, NOT a defect. "
        + "Do not mark checklist complete from this result. Preserve unverified axes; manager handles final judgment.")
    public CallToolResult triage(
        @McpToolParam(description = "Session from project_init") String sessionId,
        @McpToolParam(description = "One or two absolute PNG/JPEG paths, before then after") List<String> imagePaths,
        @McpToolParam(description = "Exact checklist evidence item ID") String itemId,
        @McpToolParam(description = "Expected behavior") String expected,
        @McpToolParam(description = "Collector result, e.g. OK or PARTIAL") String collectorResult,
        @McpToolParam(description = "Actual collector observations") String collectorNote,
        @McpToolParam(description = "Selected reference rules/cases", required = false) String referenceMemory) {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("itemId", itemId);
        context.put("expected", expected);
        context.put("collectorResult", collectorResult);
        context.put("collectorNote", collectorNote);
        context.put("referenceMemory", referenceMemory == null ? "" : referenceMemory);
        return this.respond(() -> this.service.submit(sessionId, imagePaths, context, true));
    }

    @McpTool(name = "local_llm_result", description = "Get queued/running/completed/failed local LLM job. "
        + "Poll at 2-5 second intervals. Completed output includes token usage and queue/execution timings. "
        + "Jobs are session-scoped, retained for 30 minutes by default, and lost on server restart. "
        + "Manager must save evidence to its run directory. Failed jobs require retry or another model.")
    public CallToolResult result(
        @McpToolParam(description = "Same project session that submitted the job") String sessionId,
        @McpToolParam(description = "Job ID returned by vision/triage") String jobId) {
        return this.respond(() -> this.service.result(sessionId, jobId));
    }

    private CallToolResult respond(Callable<Map<String, Object>> action) {
        try {
            return CallToolResult.builder().addTextContent(this.json.writeValueAsString(action.call())).build();
        } catch (Exception ex) {
            return CallToolResult.builder().isError(true)
                .addTextContent("Local LLM tool error: " + ex.getMessage()).build();
        }
    }
}
