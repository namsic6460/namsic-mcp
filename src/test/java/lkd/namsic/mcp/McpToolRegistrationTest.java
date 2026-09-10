package lkd.namsic.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 도구가 정확히 한 번씩 등록되는지 지킨다.
 * <p>
 * {@code @McpTool} 어노테이션 스캐너 경로와 McpToolConfig 의 {@code @Tool} 경로는 별개라,
 * 같은 빈을 양쪽에 올리면 이름 중복으로 등록 충돌이 난다 — 그 사고를 여기서 잡는다.
 */
@SpringBootTest
class McpToolRegistrationTest {

    @Autowired
    ApplicationContext applicationContext;

    private List<String> registeredToolNames() {
        final ResolvableType listType =
            ResolvableType.forClassWithGenerics(List.class, SyncToolSpecification.class);
        final List<String> names = new ArrayList<>();
        for (final String beanName : this.applicationContext.getBeanNamesForType(listType)) {
            @SuppressWarnings("unchecked")
            final List<SyncToolSpecification> specs =
                (List<SyncToolSpecification>) this.applicationContext.getBean(beanName);
            specs.forEach(spec -> names.add(spec.tool().name()));
        }
        return names;
    }

    @Test
    void registersEveryToolExactlyOnce() {
        List<String> names = this.registeredToolNames();
        Set<String> unique = new HashSet<>(names);

        assertEquals(unique.size(), names.size(),
            () -> "duplicate MCP tool registration among " + names);
    }

    @Test
    void registersSessionAndChromeTools() {
        List<String> names = this.registeredToolNames();

        assertTrue(names.contains("project_init"), () -> names.toString());
        assertTrue(names.contains("chrome_list_tabs"), () -> names.toString());
        assertTrue(names.contains("chrome_activate_tab"), () -> names.toString());
        assertTrue(names.contains("android_use_device"), () -> names.toString());
    }
}
