package lkd.namsic.mcp.llm;

import lkd.namsic.mcp.config.LocalLlmProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class LocalLlmClient {
    private final LocalLlmProperties properties;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public LocalLlmClient(LocalLlmProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public boolean healthy() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(this.properties.baseUrl().resolve("/health"))
            .timeout(Duration.ofSeconds(5)).GET().build();
        return this.http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    }

    public JsonNode complete(String system, List<Map<String, Object>> content)
        throws IOException, InterruptedException {
        Map<String, Object> payload = Map.of(
            "model", this.properties.model(),
            "messages", List.of(Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", content)),
            "temperature", 0, "max_tokens", 1024,
            "chat_template_kwargs", Map.of("enable_thinking", false),
            "response_format", Map.of("type", "json_object"));
        HttpRequest request = HttpRequest.newBuilder(this.properties.baseUrl().resolve("/v1/chat/completions"))
            .timeout(this.properties.requestTimeout()).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(this.json.writeValueAsString(payload))).build();
        HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            // Backend response bodies may contain the submitted prompt or image data.
            throw new IOException("Local LLM HTTP " + response.statusCode());
        }
        JsonNode body = this.json.readTree(response.body());
        if (!"stop".equals(body.path("choices").path(0).path("finish_reason").asString())) {
            throw new IOException("Local LLM returned an incomplete response");
        }
        return body;
    }
}
