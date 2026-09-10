package lkd.namsic.mcp.chrome;

import lkd.namsic.mcp.config.ChromeProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChromePropertiesTest {

    @Test
    void appliesDefaultsWhenAllNull() {
        ChromeProperties props = new ChromeProperties(null, null, null);

        assertEquals("powershell", props.powershellPath());
        assertEquals(Duration.ofSeconds(30), props.commandTimeout());
        assertEquals(Duration.ofSeconds(60), props.startupTimeout());
    }

    @Test
    void keepsExplicitValues() {
        ChromeProperties props = new ChromeProperties("pwsh", Duration.ofSeconds(5), Duration.ofSeconds(15));

        assertEquals("pwsh", props.powershellPath());
        assertEquals(Duration.ofSeconds(5), props.commandTimeout());
        assertEquals(Duration.ofSeconds(15), props.startupTimeout());
    }

    @Test
    void blankOrNonPositiveValuesFallBackToDefaults() {
        ChromeProperties props = new ChromeProperties("   ", Duration.ZERO, Duration.ofSeconds(-1));

        assertEquals("powershell", props.powershellPath());
        assertEquals(Duration.ofSeconds(30), props.commandTimeout());
        assertEquals(Duration.ofSeconds(60), props.startupTimeout());
    }
}
