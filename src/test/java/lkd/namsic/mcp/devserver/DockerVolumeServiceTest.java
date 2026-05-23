package lkd.namsic.mcp.devserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerVolumeServiceTest {

    @Test
    void assertSafeSubPathAcceptsSimplePaths() {
        assertDoesNotThrow(() -> DockerVolumeService.assertSafeSubPath("project"));
        assertDoesNotThrow(() -> DockerVolumeService.assertSafeSubPath("dev-cache/npm"));
        assertDoesNotThrow(() -> DockerVolumeService.assertSafeSubPath("a_b.c-1"));
    }

    @Test
    void assertSafeSubPathRejectsPathTraversal() {
        assertThrows(IllegalArgumentException.class,
            () -> DockerVolumeService.assertSafeSubPath("../etc"));
        assertThrows(IllegalArgumentException.class,
            () -> DockerVolumeService.assertSafeSubPath("foo/../bar"));
    }

    @Test
    void assertSafeSubPathRejectsAbsolutePaths() {
        assertThrows(IllegalArgumentException.class,
            () -> DockerVolumeService.assertSafeSubPath("/etc/passwd"));
    }

    @Test
    void assertSafeSubPathRejectsShellMetaChars() {
        assertThrows(IllegalArgumentException.class,
            () -> DockerVolumeService.assertSafeSubPath("foo;rm -rf /"));
        assertThrows(IllegalArgumentException.class,
            () -> DockerVolumeService.assertSafeSubPath("foo bar"));
        assertThrows(IllegalArgumentException.class,
            () -> DockerVolumeService.assertSafeSubPath("foo$bar"));
    }

    @Test
    void assertSafeSubPathRejectsBlank() {
        assertThrows(IllegalArgumentException.class,
            () -> DockerVolumeService.assertSafeSubPath(""));
        assertThrows(IllegalArgumentException.class,
            () -> DockerVolumeService.assertSafeSubPath(null));
    }

    @Test
    void normalizeHostPathProducesAbsolutePath() {
        String normalized = DockerVolumeService.normalizeHostPath(".");
        assertTrue(normalized.length() > 1, "should produce an absolute path: " + normalized);
    }

    @Test
    void normalizeHostPathRejectsBlank() {
        assertThrows(IllegalArgumentException.class,
            () -> DockerVolumeService.normalizeHostPath(""));
        assertThrows(IllegalArgumentException.class,
            () -> DockerVolumeService.normalizeHostPath(null));
    }

    @Test
    void resolveVolumeNameAndNetworkNameAreSanitized() {
        DockerVolumeService svc = new DockerVolumeService();
        String vol = svc.resolveVolumeName("a/b:c*");
        String net = svc.resolveNetworkName("a/b:c*");
        assertEquals("namsic-workspace-a_b_c_", vol);
        assertEquals("namsic-net-a_b_c_", net);
    }
}
