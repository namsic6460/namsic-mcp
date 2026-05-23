package lkd.namsic.mcp.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 개발 환경(dev)에서 Vault userpass 인증을 지원하는 {@link EnvironmentPostProcessor}입니다.
 * <p>
 * {@code spring.config.import: vault://} (ConfigData 방식)보다 먼저 실행되어,
 * Vault userpass API로 토큰을 발급받은 후 {@code spring.cloud.vault.token} 프로퍼티를 주입합니다.
 * 이후 Spring Cloud Vault는 기본 TOKEN 인증으로 해당 토큰을 사용합니다.
 * </p>
 *
 * <h3>환경별 Vault 인증 전략</h3>
 * <ul>
 *   <li><b>dev</b>: 환경변수 {@code VAULT_USERNAME}/{@code VAULT_PASSWORD} → 이 클래스가 userpass 토큰 발급</li>
 *   <li><b>staging/prod</b>: {@code spring.cloud.vault.authentication=AWS_IAM} →
 *       Spring Cloud Vault 네이티브 AWS IAM 인증 사용 (이 클래스는 자동 스킵)</li>
 * </ul>
 *
 * <p>
 * {@code META-INF/spring.factories}에 등록되어 Spring Boot가 자동으로 로드합니다.
 * </p>
 *
 * @see <a href="https://developer.hashicorp.com/vault/docs/auth/userpass">Vault Userpass Auth Method</a>
 * @see <a href="https://developer.hashicorp.com/vault/docs/auth/aws">Vault AWS Auth Method</a>
 */
public class VaultConfig implements EnvironmentPostProcessor, Ordered {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    /**
     * 여러 후보 값 중 실제 사용 가능한 첫 번째 값을 반환합니다.
     * {@code null}, 빈 문자열, 미해결 플레이스홀더({@code ${...}})는 무시합니다.
     *
     * @param candidates 후보 값 목록
     * @return 유효한 첫 번째 값, 없으면 {@code null}
     */
    private static String firstResolved(String... candidates) {
        for (String value : candidates) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty() || (trimmed.startsWith("${") && trimmed.endsWith("}"))) {
                continue;
            }
            return trimmed;
        }
        return null;
    }

    /**
     * ConfigDataEnvironmentPostProcessor의 order는 {@code Ordered.HIGHEST_PRECEDENCE + 10}.
     * 그보다 먼저 실행되어야 하므로 +5로 설정합니다.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // staging/prod 환경에서 AWS_IAM 등 네이티브 인증이 설정된 경우 스킵
        String existingAuth = environment.getProperty("spring.cloud.vault.authentication");
        if (existingAuth != null && !existingAuth.isBlank()) {
            return;
        }

        // EnvironmentPostProcessor 단계에서는 application.yml의 placeholder가 아직 resolve되지 않을 수 있으므로
        // 미해결 플레이스홀더(${...})를 null로 처리하여 안전하게 스킵합니다.
        String vaultUri = firstResolved(
            environment.getProperty("VAULT_URI"),
            environment.getProperty("spring.cloud.vault.uri"),
            "https://vault.namsic.be"
        );
        String username = firstResolved(
            environment.getProperty("VAULT_USERNAME"),
            environment.getProperty("spring.cloud.vault.userpass.username")
        );
        String password = firstResolved(
            environment.getProperty("VAULT_PASSWORD"),
            environment.getProperty("spring.cloud.vault.userpass.password")
        );

        if (vaultUri == null || username == null || password == null) {
            return;
        }

        String encodedUsername = URLEncoder.encode(username, StandardCharsets.UTF_8);
        String loginUrl = vaultUri + "/v1/auth/userpass/login/" + encodedUsername;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        RestTemplate restTemplate = new RestTemplate(factory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> requestBody = Map.of("password", password);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(loginUrl, HttpMethod.POST, request,
                    new org.springframework.core.ParameterizedTypeReference<>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> auth = (Map<String, Object>) response.getBody().get("auth");

                if (auth != null) {
                    Object tokenValue = auth.get("client_token");

                    if (tokenValue instanceof String clientToken && !clientToken.isBlank()) {
                        environment.getPropertySources().addFirst(
                            new MapPropertySource("vaultUserpassToken",
                                Map.of("spring.cloud.vault.token", clientToken))
                        );
                        return;
                    }
                }
            }

            throw new IllegalStateException("Failed to authenticate with Vault userpass: Invalid response");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to authenticate with Vault userpass for user: " + username, e);
        }
    }
}
