package lkd.namsic.mcp.llm;

import lkd.namsic.mcp.config.LocalLlmMemoryProperties;
import lkd.namsic.mcp.config.SessionProperties;
import lkd.namsic.mcp.session.ProjectSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LocalLlmMemoryStoreTest {
    @TempDir Path temp;
    private LocalLlmMemoryStore store;
    private String session;

    @BeforeEach
    void setup() {
        ProjectSessionRegistry registry = new ProjectSessionRegistry(new SessionProperties(this.temp.resolve("shots")));
        this.session = registry.init("project-a").sessionId();
        this.store = new LocalLlmMemoryStore(new LocalLlmMemoryProperties(this.temp.resolve("memory")), registry);
    }

    private LocalLlmMemoryStore.Entry save(String status, String evidence) throws Exception {
        return this.store.save(this.session, null, 0, "설정창 Scroll", "넘침이 없으면 스크롤은 미검증",
            List.of("설정", "overflow"), "SettingsDialog / Android / version 1", status, evidence);
    }

    @Test
    void persistsAcrossRegistryRestartAndSeparatesProjects() throws Exception {
        LocalLlmMemoryStore.Entry entry = this.save("confirmed", "run/shot.png: 재검수 확인");
        ProjectSessionRegistry fresh = new ProjectSessionRegistry(new SessionProperties(this.temp.resolve("shots")));
        LocalLlmMemoryStore reloaded = new LocalLlmMemoryStore(new LocalLlmMemoryProperties(this.temp.resolve("memory")), fresh);
        String newSession = fresh.init("project-a").sessionId();
        assertNotEquals(this.session, newSession);
        assertEquals(entry, reloaded.get(newSession, entry.id()));
        String other = fresh.init("project-b").sessionId();
        assertThrows(IllegalArgumentException.class, () -> reloaded.get(other, entry.id()));
        assertEquals(0, reloaded.search(other, "", "all", 0, 20).get("total"));
    }

    @Test
    void requiresEvidenceAndOnlyComposesConfirmedEntries() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> this.save("confirmed", ""));
        LocalLlmMemoryStore.Entry candidate = this.save("candidate", "");
        assertThrows(IllegalArgumentException.class, () -> this.store.context(this.session, List.of(candidate.id())));
        LocalLlmMemoryStore.Entry confirmed = this.store.save(this.session, candidate.id(), 1, candidate.title(),
            candidate.content(), candidate.tags(), candidate.scope(), "confirmed", "proof / inspected");
        Map<String, Object> context = this.store.context(this.session, List.of(confirmed.id()));
        assertTrue(context.get("referenceMemory").toString().contains("미검증"));
        assertTrue(context.get("referenceMemory").toString().contains("proof / inspected"));
        assertEquals(List.of(Map.of("id", confirmed.id(), "revision", 2L)), context.get("selected"));
        assertThrows(IllegalArgumentException.class, () -> this.store.context(this.session,
            List.of(confirmed.id(), confirmed.id())));
    }

    @Test
    void rejectsStaleUpdatesAndDeletion() throws Exception {
        LocalLlmMemoryStore.Entry original = this.save("candidate", "");
        LocalLlmMemoryStore.Entry updated = this.store.save(this.session, original.id(), 1, original.title(),
            "new", original.tags(), original.scope(), "candidate", "");
        assertEquals(2, updated.revision());
        assertThrows(IllegalStateException.class, () -> this.store.save(this.session, original.id(), 1,
            "stale", "stale", List.of(), "all", "candidate", ""));
        assertThrows(IllegalStateException.class, () -> this.store.delete(this.session, original.id(), 1));
        this.store.delete(this.session, original.id(), 2);
        assertThrows(IllegalArgumentException.class, () -> this.store.get(this.session, original.id()));
    }

    @Test
    void searchesTagsCaseInsensitivelyWithStatusAndPagination() throws Exception {
        this.save("confirmed", "proof");
        this.save("candidate", "");
        this.save("archived", "");
        assertEquals(1, this.store.search(this.session, "OVERFLOW", "confirmed", 0, 10).get("total"));
        Map<String, Object> all = this.store.search(this.session, "", "all", 1, 1);
        assertEquals(3, all.get("total"));
        assertEquals(1, ((List<?>) all.get("entries")).size());
        assertThrows(IllegalArgumentException.class, () -> this.store.search(this.session, "", "all", -1, 10));
        assertThrows(IllegalArgumentException.class, () -> this.store.search(this.session, "", "all", 0, 101));
    }

    @Test
    void refusesCorruptStoreWithoutOverwritingIt() throws Exception {
        this.save("candidate", "");
        Path path;
        try (Stream<Path> paths = Files.list(this.temp.resolve("memory"))) {
            path = paths.filter(value -> value.toString().endsWith(".json")).findFirst().orElseThrow();
        }
        Files.writeString(path, "{broken");
        assertThrows(Exception.class, () -> this.save("candidate", ""));
        assertEquals("{broken", Files.readString(path));
    }

    @Test
    void boundsContextWithoutSilentlyTruncating() throws Exception {
        LocalLlmMemoryStore.Entry first = this.store.save(this.session, null, 0, "a", "x".repeat(8000),
            List.of(), "all", "confirmed", "proof");
        LocalLlmMemoryStore.Entry second = this.store.save(this.session, null, 0, "b", "x".repeat(8000),
            List.of(), "all", "confirmed", "proof");
        assertThrows(IllegalArgumentException.class, () -> this.store.context(this.session, List.of(first.id(), second.id())));
    }

    @Test
    void concurrentRevisionUpdatesDoNotLoseChanges() throws Exception {
        LocalLlmMemoryStore.Entry entry = this.save("candidate", "");
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = pool.invokeAll(List.of(
                () -> this.updateOnce(entry), () -> this.updateOnce(entry)));
            int success = 0;
            for (Future<Boolean> result : results) if (result.get()) success++;
            assertEquals(1, success);
            assertEquals(2, this.store.get(this.session, entry.id()).revision());
        }
    }

    private boolean updateOnce(LocalLlmMemoryStore.Entry entry) throws Exception {
        try {
            this.store.save(this.session, entry.id(), 1, entry.title(), "new", entry.tags(),
                entry.scope(), "candidate", "");
            return true;
        } catch (IllegalStateException ex) {
            return false;
        }
    }
}
