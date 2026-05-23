package lkd.namsic.mcp.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * 브라우저 MCP 도구의 URL 입력을 검증해 SSRF / 내부망 metadata 접근을 차단한다.
 *
 * <p>LLM은 신뢰할 수 없는 URL을 전달할 수 있으므로 스킴·호스트·포트 단에서 필터링한다.
 * dev server(기본 10000번대 포트)는 모두 허용하고, 클라우드 metadata 엔드포인트와
 * 대표 백엔드 서비스 포트만 차단해 실 사용성과 방어를 양립시킨다.
 */
public final class BrowserUrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private static final Set<String> BLOCKED_HOSTS = Set.of(
        "169.254.169.254",          // AWS / OpenStack / Azure IMDS
        "metadata.google.internal", // GCP
        "metadata",                 // generic
        "100.100.100.200"           // Alibaba Cloud ECS metadata
    );

    private static final Set<Integer> BLOCKED_PORTS = Set.of(
        22, 23, 25, 110, 143, 389, 445, 465, 587, 636, 993, 995,
        1433, 1521, 2049, 2375, 2376,
        3306, 3389, 5432, 5672, 5984, 6379,
        7000, 7001,
        8086, 9042, 9092, 9200, 9300,
        11211, 15672,
        27017, 27018, 27019
    );

    private BrowserUrlValidator() {
    }

    public static ValidationResult validate(final String url) {
        if (url == null || url.isBlank()) {
            return ValidationResult.deny("URL is empty");
        }

        final URI uri;
        try {
            uri = new URI(url.trim());
        } catch (final URISyntaxException ex) {
            return ValidationResult.deny("malformed URL: " + ex.getMessage());
        }

        final String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            return ValidationResult.deny("scheme not allowed: " + scheme);
        }

        final String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return ValidationResult.deny("host is missing");
        }

        final String hostLower = host.toLowerCase();
        if (BLOCKED_HOSTS.contains(hostLower)) {
            return ValidationResult.deny("host blocked by policy: " + host);
        }

        if (hostLower.equals("[::1]") || hostLower.startsWith("[fe80:") || hostLower.startsWith("[fc") || hostLower.startsWith("[fd")) {
            return ValidationResult.deny("IPv6 loopback/private range blocked: " + host);
        }

        final int port = uri.getPort();
        if (port != -1) {
            if (port < 1024 && port != 80 && port != 443) {
                return ValidationResult.deny("privileged port blocked: " + port);
            }
            if (BLOCKED_PORTS.contains(port)) {
                return ValidationResult.deny("port blocked by policy: " + port);
            }
        }

        return ValidationResult.allow();
    }

    public record ValidationResult(boolean allowed, String reason) {
        public static ValidationResult allow() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult deny(final String reason) {
            return new ValidationResult(false, reason);
        }
    }
}
