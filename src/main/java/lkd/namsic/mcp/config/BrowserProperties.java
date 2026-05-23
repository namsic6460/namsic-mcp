package lkd.namsic.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app.browser")
public record BrowserProperties(
    String nodePath,
    String browserType,
    List<String> launchArgs,
    Duration startupTimeout,
    Boolean headless,
    Integer viewportWidth,
    Integer viewportHeight,
    Double deviceScaleFactor,
    Duration navigationTimeout,
    Path screenshotBaseDir
) {

    public BrowserProperties {
        if (nodePath == null || nodePath.isBlank()) {
            nodePath = "node";
        }
        if (browserType == null || browserType.isBlank()) {
            browserType = "chromium";
        }
        if (launchArgs == null) {
            launchArgs = List.of();
        }
        if (startupTimeout == null) {
            startupTimeout = Duration.ofSeconds(30);
        }
        if (headless == null) {
            headless = false;
        }
        if (viewportWidth == null || viewportWidth <= 0) {
            viewportWidth = 1920;
        }
        if (viewportHeight == null || viewportHeight <= 0) {
            viewportHeight = 1080;
        }
        if (deviceScaleFactor == null || deviceScaleFactor <= 0) {
            deviceScaleFactor = 1.0;
        }
        if (navigationTimeout == null) {
            navigationTimeout = Duration.ofSeconds(60);
        }
        if (screenshotBaseDir == null) {
            screenshotBaseDir = Paths.get(System.getProperty("user.home"), ".namsic-mcp", "screenshots");
        }
    }
}
