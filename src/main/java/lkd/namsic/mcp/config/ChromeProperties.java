package lkd.namsic.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 크롬 탭 제어(UI Automation 상주 호스트) 설정. */
@ConfigurationProperties(prefix = "app.chrome")
public record ChromeProperties(
    String powershellPath,
    Duration commandTimeout,
    Duration startupTimeout
) {

    public ChromeProperties {
        if (powershellPath == null || powershellPath.isBlank()) {
            // Windows PowerShell 5.1 은 윈도우에 항상 설치돼 있고 UIAutomationClient 어셈블리를 포함한다.
            // pwsh 7 로 바꾸려면 app.chrome.powershell-path=pwsh 로 지정한다.
            powershellPath = "powershell";
        }
        if (commandTimeout == null || commandTimeout.isNegative() || commandTimeout.isZero()) {
            commandTimeout = Duration.ofSeconds(30);
        }
        if (startupTimeout == null || startupTimeout.isNegative() || startupTimeout.isZero()) {
            // 첫 명령은 Add-Type C# 컴파일 + UIA 어셈블리 로드 비용을 함께 지불한다.
            startupTimeout = Duration.ofSeconds(60);
        }
    }
}
