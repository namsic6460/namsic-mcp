package lkd.namsic.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/** 프로젝트 세션 공통 설정 (스크린샷 저장 루트). */
@ConfigurationProperties(prefix = "app.session")
public record SessionProperties(
    Path screenshotBaseDir
) {

    public SessionProperties {
        if (screenshotBaseDir == null) {
            screenshotBaseDir = Paths.get(System.getProperty("user.home"), ".namsic-mcp", "screenshots");
        }
    }
}
