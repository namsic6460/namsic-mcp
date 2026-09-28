package lkd.namsic.mcp.llm;

import com.sun.net.httpserver.HttpServer;
import lkd.namsic.mcp.config.LocalLlmProperties;
import lkd.namsic.mcp.config.SessionProperties;
import lkd.namsic.mcp.session.ProjectSessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LocalLlmServiceTest {
    @TempDir Path temp;
    private HttpServer server;
    private LocalLlmService service;
    private ProjectSessionRegistry registry;
    private String session;
    private Path image;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean block;
    private volatile String finish = "stop";
    private volatile String reply = "{\"itemId\":\"case-1\",\"status\":\"suspect\",\"reason\":\"스크롤 미검증\",\"unverified\":[\"scroll\"]}";

    @BeforeEach
    void setUp() throws Exception {
        this.registry = new ProjectSessionRegistry(new SessionProperties(this.temp.resolve("screenshots")));
        this.session = this.registry.init("test").sessionId();
        this.image = this.registry.require(this.session).screenshotDir().resolve("shot.png");
        ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", this.image.toFile());
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        this.server.createContext("/v1/chat/completions", exchange -> {
            this.received.set(this.json.readTree(exchange.getRequestBody().readAllBytes()));
            this.entered.countDown();
            try {
                if (this.block && !this.release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                byte[] body = this.json.writeValueAsBytes(Map.of("choices",
                    List.of(Map.of("finish_reason", this.finish, "message", Map.of("content", this.reply))),
                    "usage", Map.of("prompt_tokens", 100, "completion_tokens", 20)));
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        this.server.start();
        this.service = this.newService(URI.create("http://127.0.0.1:" + this.server.getAddress().getPort()), Duration.ofSeconds(3));
    }

    private LocalLlmService newService(URI url, Duration timeout) {
        LocalLlmProperties properties = new LocalLlmProperties(url, null, timeout, 1, 10, null, List.of());
        return new LocalLlmService(properties, new LocalLlmClient(properties), this.registry);
    }

    @AfterEach
    void tearDown() {
        this.release.countDown();
        this.service.close();
        this.server.stop(0);
    }

    private Map<String, String> context() {
        return Map.of("itemId", "case-1", "expected", "설정창 스크롤", "collectorResult", "PARTIAL",
            "collectorNote", "넘침 발생하지 않음", "referenceMemory", "미검증은 결함이 아님");
    }

    private String submit() throws Exception {
        return (String) this.service.submit(this.session, List.of(this.image.toString()), this.context(), true).get("jobId");
    }

    private Map<String, Object> await(String id) throws Exception {
        return this.await(id, 8);
    }
    private Map<String, Object> await(String id, int seconds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, Object> result = this.service.result(this.session, id);
            if (List.of("completed", "failed").contains(result.get("state"))) return result;
            Thread.sleep(10);
        }
        throw new AssertionError("Job did not complete");
    }

    @Test
    void sendsImagesAndSelectedMemoryAndRetainsUnverifiedResult() throws Exception {
        Map<String, Object> result = this.await(this.submit());
        assertEquals("completed", result.get("state"));
        JsonNode output = (JsonNode) result.get("output");
        assertEquals("suspect", output.path("status").asString());
        assertFalse(output.path("unverified").isEmpty());
        JsonNode content = this.received.get().path("messages").path(1).path("content");
        assertTrue(content.path(0).path("text").asString().contains("미검증은 결함이 아님"));
        assertTrue(content.path(1).path("image_url").path("url").asString().startsWith("data:image/png;base64,"));
        assertFalse(this.received.get().path("chat_template_kwargs").path("enable_thinking").asBoolean());
        assertEquals(100, ((JsonNode) result.get("usage")).path("prompt_tokens").asInt());
        assertTrue((boolean) this.service.status().get("healthy"));
    }

    @Test
    void serializesJobsBoundsQueueAndIsolatesResults() throws Exception {
        this.block = true;
        String first = this.submit();
        assertTrue(this.entered.await(3, TimeUnit.SECONDS));
        String second = this.submit();
        assertEquals("queued", this.service.result(this.session, second).get("state"));
        assertThrows(IllegalStateException.class, this::submit);
        String otherSession = this.registry.init("other").sessionId();
        assertThrows(IllegalArgumentException.class, () -> this.service.result(otherSession, first));
        this.release.countDown();
        assertEquals("completed", this.await(first).get("state"));
        assertEquals("completed", this.await(second).get("state"));
    }

    @Test
    void rejectsImagesOutsideAllowedRootsAndNonImages() throws Exception {
        Path outside = this.temp.resolve("outside.png");
        Files.copy(this.image, outside);
        assertThrows(IllegalArgumentException.class, () ->
            this.service.submit(this.session, List.of(outside.toString()), this.context(), true));
        Files.writeString(this.image, "not an image", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, this::submit);
    }

    @Test
    void incompleteOrInvalidResponsesNeverBecomeSuccess() throws Exception {
        this.finish = "length";
        assertEquals("failed", this.await(this.submit()).get("state"));
        this.finish = "stop";
        this.reply = "{\"itemId\":\"wrong\",\"status\":\"ok\",\"reason\":\"ok\",\"unverified\":[]}";
        assertEquals("failed", this.await(this.submit()).get("state"));
        this.reply = "{\"itemId\":\"case-1\",\"status\":\"ok\",\"reason\":\"ok\",\"unverified\":[]}";
        assertEquals("failed", this.await(this.submit()).get("state"));
    }

    @Test
    void timedOutJobFailsAndReleasesWorker() throws Exception {
        this.service.close();
        this.service = this.newService(URI.create("http://127.0.0.1:" + this.server.getAddress().getPort()),
            Duration.ofMillis(150));
        this.block = true;
        assertEquals("failed", this.await(this.submit()).get("state"));
        this.release.countDown();
        this.block = false;
        assertEquals("completed", this.await(this.submit()).get("state"));
    }

    @Test
    void visionValidatesDifferentSchema() throws Exception {
        this.reply = "{\"screen\":\"홈\",\"observed\":[\"홈 제목\"],\"unverified\":[\"소리\"]}";
        String id = (String) this.service.submit(this.session, List.of(this.image.toString()),
            Map.of("task", "화면 판독", "referenceMemory", ""), false).get("jobId");
        assertEquals("completed", this.await(id).get("state"));
    }

    @Test
    void rejectsRemoteBackendConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> this.newService(URI.create("https://example.com"),
            Duration.ofSeconds(1)));
    }

    @Test
    @EnabledIfSystemProperty(named = "local.llm.it", matches = "true")
    void liveLocalQwenRoundTrip() throws Exception {
        this.service.close();
        this.service = this.newService(URI.create("http://127.0.0.1:9000"), Duration.ofSeconds(120));
        String fixture = System.getProperty("local.llm.image", "");
        if (!fixture.isBlank()) Files.copy(Path.of(fixture), this.image, StandardCopyOption.REPLACE_EXISTING);
        String id = (String) this.service.submit(this.session, List.of(this.image.toString()),
            Map.of("task", "보이는 색만 짧게 관찰하라", "referenceMemory", ""), false).get("jobId");
        Map<String, Object> result = this.await(id, 130);
        assertEquals("completed", result.get("state"), result.toString());
        System.out.println("Local Qwen smoke result: " + result);
    }
}
