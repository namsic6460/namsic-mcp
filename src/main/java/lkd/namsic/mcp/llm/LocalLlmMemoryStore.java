package lkd.namsic.mcp.llm;

import lkd.namsic.mcp.config.LocalLlmMemoryProperties;
import lkd.namsic.mcp.session.ProjectSessionRegistry;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class LocalLlmMemoryStore {
    public record Entry(String id, long revision, String title, String content, List<String> tags,
                        String scope, String status, String evidence, String createdAt, String updatedAt) {}
    public record Document(int schemaVersion, String project, List<Entry> entries) {}
    private final Path directory;
    private final ProjectSessionRegistry registry;
    private final JsonMapper json = JsonMapper.builder().build();

    public LocalLlmMemoryStore(LocalLlmMemoryProperties properties, ProjectSessionRegistry registry) {
        this.directory = properties.directory().toAbsolutePath().normalize();
        this.registry = registry;
    }

    public synchronized Entry save(String sessionId, String id, long expectedRevision, String title,
                                    String content, List<String> tags, String scope, String status,
                                    String evidence) throws IOException {
        text(title, "title", 200, true);
        text(content, "content", 8000, true);
        text(scope, "scope", 1000, true);
        text(evidence, "evidence", 2000, "confirmed".equals(status));
        if (!List.of("candidate", "confirmed", "archived").contains(status == null ? "" : status)) {
            throw new IllegalArgumentException("status must be candidate, confirmed or archived");
        }
        List<String> checkedTags = tags == null ? List.of() : List.copyOf(tags);
        if (checkedTags.size() > 20) throw new IllegalArgumentException("At most 20 tags");
        for (String tag : checkedTags) text(tag, "tag", 100, true);
        String memoryId = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        return this.locked(sessionId, (path, document) -> {
            List<Entry> entries = new ArrayList<>(document.entries());
            Entry old = entries.stream().filter(entry -> entry.id().equals(memoryId)).findFirst().orElse(null);
            if (old == null && id != null && !id.isBlank()) throw new IllegalArgumentException("Unknown memory ID");
            if (expectedRevision != (old == null ? 0 : old.revision())) {
                throw new IllegalStateException("Memory revision conflict; fetch latest before retrying");
            }
            if (old == null && entries.size() >= 1000) throw new IllegalStateException("Project memory limit is 1000");
            String now = Instant.now().toString();
            Entry entry = new Entry(memoryId, expectedRevision + 1, title, content, checkedTags, scope, status,
                evidence == null ? "" : evidence, old == null ? now : old.createdAt(), now);
            entries.removeIf(value -> value.id().equals(memoryId));
            entries.add(entry);
            this.write(path, new Document(1, document.project(), entries));
            return entry;
        });
    }

    public synchronized Entry get(String sessionId, String id) throws IOException {
        return this.locked(sessionId, (_, doc) -> require(doc, id));
    }

    public synchronized Map<String, Object> search(String sessionId, String query, String status,
                                                    int offset, int limit) throws IOException {
        text(query, "query", 1000, false);
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("offset >= 0, limit 1..100");
        if (!List.of("candidate", "confirmed", "archived", "all").contains(status)) {
            throw new IllegalArgumentException("Invalid status filter");
        }
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return this.locked(sessionId, (_, doc) -> {
            List<Entry> matches = doc.entries().stream()
                .filter(entry -> "all".equals(status) || status.equals(entry.status()))
                .filter(entry -> needle.isEmpty() || (entry.title() + " " + entry.content() + " "
                    + entry.scope() + " " + String.join(" ", entry.tags())).toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(Entry::updatedAt).reversed().thenComparing(Entry::id)).toList();
            return Map.of("total", matches.size(), "offset", offset,
                "entries", matches.stream().skip(offset).limit(limit).toList());
        });
    }

    public synchronized Map<String, Object> delete(String sessionId, String id, long expectedRevision) throws IOException {
        return this.locked(sessionId, (path, doc) -> {
            Entry entry = require(doc, id);
            if (entry.revision() != expectedRevision) throw new IllegalStateException("Memory revision conflict");
            List<Entry> entries = new ArrayList<>(doc.entries());
            entries.removeIf(value -> value.id().equals(id));
            this.write(path, new Document(1, doc.project(), entries));
            return Map.of("deleted", id, "revision", expectedRevision);
        });
    }

    public synchronized Map<String, Object> context(String sessionId, List<String> ids) throws IOException {
        if (ids == null || ids.isEmpty() || ids.size() > 10 || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("Select 1..10 distinct confirmed memory IDs");
        }
        return this.locked(sessionId, (_, doc) -> {
            List<Entry> selected = new ArrayList<>();
            for (String id : ids) {
                Entry entry = require(doc, id);
                if (!"confirmed".equals(entry.status())) {
                    throw new IllegalArgumentException("Only confirmed memories can be composed");
                }
                selected.add(entry);
            }
            String reference = this.json.writeValueAsString(Map.of(
                "notice", "Reference cases only, not current evidence. Check scope and applicability.",
                "memories", selected));
            if (reference.length() > 16000) throw new IllegalArgumentException("Selected memory exceeds 16000 characters; select fewer");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("referenceMemory", reference);
            result.put("characters", reference.length());
            result.put("selected", selected.stream().map(entry -> Map.of("id", entry.id(), "revision", entry.revision())).toList());
            return result;
        });
    }

    private <T> T locked(String sessionId, Operation<T> operation) throws IOException {
        String project = this.registry.require(sessionId).projectName();
        Files.createDirectories(this.directory);
        String key = digest(project);
        Path path = this.directory.resolve(key + ".json");
        // Serialize multiple MCP processes as well as concurrent requests.
        try (FileChannel channel = FileChannel.open(this.directory.resolve(key + ".lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            if (!lock.isValid()) throw new IOException("Memory lock unavailable");
            Document document;
            if (Files.exists(path)) {
                if (Files.size(path) > 64 * 1024 * 1024) throw new IOException("Memory store exceeds size limit");
                document = this.json.readValue(Files.readString(path), Document.class);
                if (document.schemaVersion() != 1 || !project.equals(document.project()) || document.entries() == null) {
                    throw new IOException("Invalid memory store; refusing to overwrite");
                }
            } else {
                document = new Document(1, project, List.of());
            }
            return operation.apply(path, document);
        }
    }

    private void write(Path path, Document document) throws IOException {
        Path temporary = Files.createTempFile(this.directory, "memory-", ".tmp");
        try {
            Files.writeString(temporary, this.json.writeValueAsString(document), StandardCharsets.UTF_8);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Entry require(Document document, String id) {
        return document.entries().stream().filter(entry -> entry.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown memory ID in this project"));
    }

    private static void text(String value, String field, int max, boolean required) {
        if ((required && (value == null || value.isBlank())) || (value != null && value.length() > max)) {
            throw new IllegalArgumentException(field + " is required when applicable and must be <= " + max + " characters");
        }
    }

    private static String digest(String project) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(project.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private interface Operation<T> {
        T apply(Path path, Document document) throws IOException;
    }
}
