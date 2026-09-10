package lkd.namsic.mcp.chrome;

import lkd.namsic.mcp.chrome.ChromeTabIndex.ChromeTab;
import lkd.namsic.mcp.session.ProjectSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 크롬 탭 활성화 MCP 도구 (Windows UI Automation 기반).
 * <p>
 * 브라우저 자동화 확장(Claude in Chrome 등)은 백그라운드 탭에서도 스크린샷·클릭·JS 실행이 되지만,
 * 그 탭은 {@code document.visibilityState === 'hidden'} 이라 requestAnimationFrame 이 완전히 멈추고
 * 타이머가 약 1Hz 로 스로틀된다. 캔버스/WebGL/차트/애니메이션 UI 는 이때 첫 프레임에 얼어붙은 채로
 * 찍힌다. 확장에는 탭을 앞으로 가져오는 수단이 없어서, OS 레벨에서 탭 스트립을 직접 선택한다.
 * <p>
 * {@code @McpTool} 어노테이션 스캐너로 자동 등록된다 — McpToolConfig.toolObjects 에 넣으면
 * 이중 등록 충돌이 나므로 절대 추가하지 말 것.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChromeMcpTools {

    private static final String SESSION_PARAM_DESC =
        "Session ID returned by project_init. Required; obtain it by calling project_init with a project name first.";

    private static final String OK_PREFIX = "OK\t";
    private static final String ERR_PREFIX = "ERR\t";

    private final ChromeUiaHost host;
    private final ProjectSessionRegistry projectSessionRegistry;

    @McpTool(name = "chrome_list_tabs", description = "List every tab of every Chrome window running on this "
        + "machine, with its window handle (hwnd), tab index, and which tab is currently active. "
        + "Use it to find the tab you want before calling chrome_activate_tab. "
        + "Tab names are Chrome's accessibility labels — the page title followed by Chrome's own suffixes "
        + "(tab-group name, memory usage) — so match against the page-title part. "
        + "Windows-only: this reads the Chrome tab strip through Windows UI Automation.")
    public String chromeListTabs(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId
    ) {
        log.info("MCP tool invoked: chrome_list_tabs sessionId={}", sessionId);
        try {
            this.projectSessionRegistry.require(sessionId);
        } catch (final IllegalStateException ex) {
            return "Error: " + ex.getMessage();
        }
        try {
            return ChromeTabIndex.parse(this.host.listTabs()).render();
        } catch (final RuntimeException ex) {
            return "Error listing Chrome tabs: " + ex.getMessage();
        }
    }

    @McpTool(name = "chrome_activate_tab", description = "Bring a Chrome tab to the front: select it in its "
        + "window's tab strip, restoring the window first if it is minimized. "
        + "WHY THIS IS NEEDED: a tab that is not the active tab of its window reports "
        + "document.visibilityState='hidden', which freezes requestAnimationFrame entirely and throttles "
        + "setTimeout/setInterval to about 1Hz. Screenshots of such a tab still succeed but show a stale "
        + "frame, so canvas, WebGL, chart and animated UI look frozen or unrendered, and code that waits on "
        + "timers appears to do nothing. Static pages are unaffected. Call this before screenshotting or "
        + "driving any page whose rendering depends on animation frames, and whenever a page seems stuck. "
        + "Target the tab with `title`, or address it exactly with `hwnd` + `tabIndex` from chrome_list_tabs. "
        + "If several tabs share a title, make the target unique first by setting document.title to a marker "
        + "in that tab, then match the marker. Windows-only (Windows UI Automation).")
    public String chromeActivateTab(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "Tab title to match against the tab's accessibility name, "
            + "case-insensitive. Exact match wins, then prefix, then substring. "
            + "Optional when both hwnd and tabIndex are given.", required = false) final String title,
        @McpToolParam(description = "Window handle from chrome_list_tabs. Use with tabIndex to address a tab "
            + "exactly, bypassing title matching.", required = false) final Long hwnd,
        @McpToolParam(description = "Tab index within the window, from chrome_list_tabs. Use with hwnd.",
            required = false) final Integer tabIndex,
        @McpToolParam(description = "Also raise the Chrome window to the foreground, which takes OS focus. "
            + "Default: true. Set false to switch the tab within its window without stealing focus — less "
            + "intrusive, but a fully covered Chrome window may still be treated as hidden by Chrome's "
            + "occlusion tracking.", required = false) final Boolean bringToFront
    ) {
        log.info("MCP tool invoked: chrome_activate_tab title={} hwnd={} tabIndex={} sessionId={}",
            title, hwnd, tabIndex, sessionId);
        try {
            this.projectSessionRegistry.require(sessionId);
        } catch (final IllegalStateException ex) {
            return "Error: " + ex.getMessage();
        }

        final boolean toFront = !Boolean.FALSE.equals(bringToFront);
        try {
            if (hwnd != null && tabIndex != null) {
                return this.activate(hwnd, tabIndex, toFront);
            }
            if (title == null || title.isBlank()) {
                return "Error: specify title, or both hwnd and tabIndex (get them from chrome_list_tabs).";
            }

            final ChromeTabIndex index = ChromeTabIndex.parse(this.host.listTabs());
            final List<ChromeTab> matches = index.match(title);
            if (matches.isEmpty()) {
                return "No Chrome tab matches \"" + title + "\".\n\n" + index.render();
            }
            if (matches.size() > 1) {
                return ambiguousMessage(title, matches);
            }
            final ChromeTab target = matches.getFirst();
            return this.activate(target.hwnd(), target.index(), toFront);
        } catch (final RuntimeException ex) {
            return "Error activating Chrome tab: " + ex.getMessage();
        }
    }

    private String activate(final long hwnd, final int tabIndex, final boolean bringToFront) {
        for (final String line : this.host.activateTab(hwnd, tabIndex, bringToFront)) {
            if (line.startsWith(OK_PREFIX)) {
                return "Activated tab: " + line.substring(OK_PREFIX.length()) + '\n'
                    + "It is now visible — requestAnimationFrame and timers run at full speed.";
            }
            if (line.startsWith(ERR_PREFIX)) {
                return "Error activating Chrome tab: " + line.substring(ERR_PREFIX.length());
            }
        }
        return "Error activating Chrome tab: unexpected reply from the Chrome UIA host.";
    }

    private static String ambiguousMessage(final String title, final List<ChromeTab> matches) {
        final StringBuilder sb = new StringBuilder("\"").append(title).append("\" matches ")
            .append(matches.size()).append(" tabs. Narrow the title, or pass hwnd + tabIndex:\n");
        for (final ChromeTab tab : matches) {
            sb.append("  hwnd=").append(tab.hwnd())
                .append(" tabIndex=").append(tab.index())
                .append("  ").append(tab.name()).append('\n');
        }
        return sb.toString();
    }
}
