package lkd.namsic.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.android")
public record AndroidProperties(
    String adbPath,
    Duration commandTimeout,
    Duration installTimeout,
    Duration dumpTimeout,
    String adbKeyboardApk,
    Integer maxUiNodes,
    Integer screenshotMaxDimension
) {

    public AndroidProperties {
        if (adbPath == null || adbPath.isBlank()) {
            adbPath = "adb";
        }
        if (commandTimeout == null || commandTimeout.isNegative() || commandTimeout.isZero()) {
            commandTimeout = Duration.ofSeconds(20);
        }
        if (installTimeout == null || installTimeout.isNegative() || installTimeout.isZero()) {
            installTimeout = Duration.ofSeconds(120);
        }
        if (dumpTimeout == null || dumpTimeout.isNegative() || dumpTimeout.isZero()) {
            dumpTimeout = Duration.ofSeconds(15);
        }
        if (maxUiNodes == null || maxUiNodes <= 0) {
            maxUiNodes = 200;
        }
        if (screenshotMaxDimension == null || screenshotMaxDimension <= 0) {
            screenshotMaxDimension = 1280;
        }
        // adbKeyboardApk는 null 허용 — 미설정 시 비ASCII 텍스트 입력 불가 (도구가 명확한 에러로 안내)
    }
}
