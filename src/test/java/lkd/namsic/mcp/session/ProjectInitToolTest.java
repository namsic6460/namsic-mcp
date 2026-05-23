package lkd.namsic.mcp.session;

import lkd.namsic.mcp.config.BrowserProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectInitToolTest {

    @TempDir
    Path tempScreenshotBase;

    private ProjectInitTool tool;

    @BeforeEach
    void setUp() {
        BrowserProperties browserProps = new BrowserProperties(
            null, null, null, null, null, null, null, null, null, this.tempScreenshotBase);
        ProjectSessionRegistry registry = new ProjectSessionRegistry(browserProps);
        this.tool = new ProjectInitTool(registry);
    }

    @Test
    void returnsSessionIdAndCreatesScreenshotDir() {
        String out = this.tool.projectInit("my-project");
        assertTrue(out.contains("sessionId: "), out);
        assertTrue(out.contains("project: my-project"), out);
        assertTrue(out.contains("screenshotDir: "), out);
    }

    @Test
    void sanitizesProjectName() {
        String out = this.tool.projectInit("unsafe/proj:name");
        assertTrue(out.contains("project: unsafe_proj_name"), out);
    }

    @Test
    void rejectsBlankProjectName() {
        String out = this.tool.projectInit("");
        assertTrue(out.startsWith("Error initializing project session"), out);
    }

    @Test
    void idempotentForSameProjectName() {
        String first = this.tool.projectInit("same-proj");
        String second = this.tool.projectInit("same-proj");
        assertEquals(extractSessionId(first), extractSessionId(second));
    }

    @Test
    void differentProjectNamesGetDifferentSessionIds() {
        String a = this.tool.projectInit("project-a");
        String b = this.tool.projectInit("project-b");
        assertNotEquals(extractSessionId(a), extractSessionId(b));
    }

    private static String extractSessionId(String out) {
        for (String line : out.split("\\n")) {
            if (line.startsWith("sessionId: ")) {
                return line.substring("sessionId: ".length()).trim();
            }
        }
        throw new AssertionError("No sessionId line in: " + out);
    }
}
