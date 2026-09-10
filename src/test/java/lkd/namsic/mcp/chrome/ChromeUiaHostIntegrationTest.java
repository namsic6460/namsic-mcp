package lkd.namsic.mcp.chrome;

import lkd.namsic.mcp.chrome.ChromeTabIndex.ChromeTab;
import lkd.namsic.mcp.chrome.ChromeTabIndex.ChromeWindow;
import lkd.namsic.mcp.config.ChromeProperties;
import lkd.namsic.mcp.config.SessionProperties;
import lkd.namsic.mcp.session.ProjectSessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 실제로 실행 중인 크롬에 붙어 상주 PowerShell 호스트의 배관(리소스 추출 → 기동 → 핸드셰이크 →
 * 명령/응답)을 검증한다. 크롬이 떠 있어야 하고 윈도우에서만 의미가 있으므로 기본 비활성이다.
 * <p>
 * 실행: {@code ./gradlew test -PchromeUiaIt=true --tests '*ChromeUiaHostIntegrationTest*'}
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "chrome.uia.it", matches = "true")
class ChromeUiaHostIntegrationTest {

    @TempDir
    Path tempDir;

    private static ChromeUiaHost newHost() {
        return new ChromeUiaHost(new ChromeProperties(null, null, null));
    }

    @Test
    void listsRealChromeTabsAndReusesTheResidentProcess() {
        ChromeUiaHost host = newHost();
        try {
            ChromeTabIndex index = ChromeTabIndex.parse(host.listTabs());

            assertTrue(index.errors().isEmpty(), () -> "host errors: " + index.errors());
            assertFalse(index.windows().isEmpty(), "Chrome must be running for this test");
            assertFalse(index.allTabs().isEmpty(), "Chrome must have at least one tab");

            ChromeWindow window = index.windows().getFirst();
            assertNotNull(window.visualState());
            assertTrue(window.hwnd() != 0L, "window handle must be resolved");
            assertTrue(index.windows().stream().anyMatch(w -> w.tabs().stream().anyMatch(ChromeTab::selected)),
                "exactly one tab per window should be marked selected");

            // 두 번째 호출은 이미 뜬 프로세스를 재사용하므로 Add-Type 비용이 없어야 한다.
            long startedAt = System.nanoTime();
            host.listTabs();
            long warmMs = (System.nanoTime() - startedAt) / 1_000_000L;
            assertTrue(warmMs < 5_000L, () -> "warm LIST took " + warmMs + " ms — process was not reused");
        } finally {
            host.close();
        }
    }

    @Test
    void activatingTheAlreadySelectedTabSucceeds() {
        ChromeUiaHost host = newHost();
        try {
            ChromeTabIndex index = ChromeTabIndex.parse(host.listTabs());
            Optional<ChromeTab> selected = index.allTabs().stream().filter(ChromeTab::selected).findFirst();
            assertTrue(selected.isPresent(), "Chrome must have an active tab");

            // 이미 선택된 탭을 다시 선택하고 포커스도 뺏지 않아, 사용자가 보는 화면을 바꾸지 않는다.
            ChromeTab target = selected.get();
            List<String> reply = host.activateTab(target.hwnd(), target.index(), false);

            assertTrue(reply.stream().anyMatch(line -> line.startsWith("OK\t")),
                () -> "expected an OK record but got " + reply);
        } finally {
            host.close();
        }
    }

    /** 제목 매칭 → 활성화 → 반영 확인까지, MCP 도구 레이어를 포함한 전 구간 왕복. */
    @Test
    void activatesABackgroundTabByTitleThroughTheMcpTool() {
        ChromeUiaHost host = newHost();
        try {
            ChromeTabIndex before = ChromeTabIndex.parse(host.listTabs());
            Optional<ChromeTab> background = before.allTabs().stream().filter(tab -> !tab.selected()).findFirst();
            assumeTrue(background.isPresent(), "needs at least one background Chrome tab");
            Optional<ChromeTab> previouslySelected = before.allTabs().stream()
                .filter(tab -> tab.selected() && tab.hwnd() == background.get().hwnd()).findFirst();

            ProjectSessionRegistry registry = new ProjectSessionRegistry(new SessionProperties(this.tempDir));
            ChromeMcpTools tools = new ChromeMcpTools(host, registry);
            String sessionId = registry.init("chrome-it").sessionId();

            // 접근성 이름 전체를 주면 완전 일치라 반드시 유일하게 잡힌다.
            // bringToFront=false 로 OS 포커스는 건드리지 않는다.
            ChromeTab target = background.get();
            String result = tools.chromeActivateTab(sessionId, target.name(), null, null, false);
            assertTrue(result.startsWith("Activated tab: "), result);

            ChromeTabIndex after = ChromeTabIndex.parse(host.listTabs());
            assertTrue(after.allTabs().stream()
                    .anyMatch(tab -> tab.hwnd() == target.hwnd() && tab.index() == target.index() && tab.selected()),
                () -> "target tab should now be selected:\n" + after.render());

            previouslySelected.ifPresent(tab -> host.activateTab(tab.hwnd(), tab.index(), false));
        } finally {
            host.close();
        }
    }

    @Test
    void reportsErrorForUnknownWindowHandle() {
        ChromeUiaHost host = newHost();
        try {
            List<String> reply = host.activateTab(1L, 0, false);

            assertTrue(reply.stream().anyMatch(line -> line.startsWith("ERR\tno chrome window with hwnd 1")),
                () -> "expected an ERR record but got " + reply);
        } finally {
            host.close();
        }
    }
}
