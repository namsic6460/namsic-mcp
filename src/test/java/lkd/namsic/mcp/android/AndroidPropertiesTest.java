package lkd.namsic.mcp.android;

import lkd.namsic.mcp.config.AndroidProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AndroidPropertiesTest {

    @Test
    void appliesDefaultsWhenAllNull() {
        AndroidProperties props = new AndroidProperties(null, null, null, null, null, null, null);

        assertEquals("adb", props.adbPath());
        assertEquals(Duration.ofSeconds(20), props.commandTimeout());
        assertEquals(Duration.ofSeconds(120), props.installTimeout());
        assertEquals(Duration.ofSeconds(15), props.dumpTimeout());
        assertNull(props.adbKeyboardApk());
        assertEquals(200, props.maxUiNodes());
        assertEquals(1280, props.screenshotMaxDimension());
    }

    @Test
    void keepsExplicitValues() {
        AndroidProperties props = new AndroidProperties(
            "C:\\sdk\\adb.exe", Duration.ofSeconds(5), Duration.ofSeconds(60), Duration.ofSeconds(10),
            "C:\\apk\\ADBKeyboard.apk", 50, 720
        );

        assertEquals("C:\\sdk\\adb.exe", props.adbPath());
        assertEquals(Duration.ofSeconds(5), props.commandTimeout());
        assertEquals("C:\\apk\\ADBKeyboard.apk", props.adbKeyboardApk());
        assertEquals(50, props.maxUiNodes());
        assertEquals(720, props.screenshotMaxDimension());
    }

    @Test
    void blankAdbPathFallsBackToDefault() {
        AndroidProperties props = new AndroidProperties("  ", null, null, null, null, -1, 0);
        assertEquals("adb", props.adbPath());
        assertEquals(200, props.maxUiNodes());
        assertEquals(1280, props.screenshotMaxDimension());
    }
}
