package lkd.namsic.mcp.chrome;

import lkd.namsic.mcp.chrome.ChromeDialogIndex.ChromeDialog;
import lkd.namsic.mcp.chrome.ChromeDialogIndex.DialogButton;
import lkd.namsic.mcp.chrome.ChromeTabIndex.ChromeTab;
import lkd.namsic.mcp.session.ProjectSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 크롬 탭 활성화·네이티브 다이얼로그 응답 MCP 도구 (Windows UI Automation 기반).
 * <p>
 * 다이얼로그 쪽: alert/confirm 같은 네이티브 다이얼로그가 뜨면 페이지 JS 가 멈춰 확장의 CDP 호출이
 * 타임아웃으로 죽고, 누가 버튼을 눌러 주기 전까지 계속 실패한다. 확장에는 이를 누를 수단이 없어서
 * 탭 활성화와 같은 방식으로 OS 레벨에서 버튼을 누른다.
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
    private static final String ACTION_ACCEPT = "accept";
    private static final String ACTION_CANCEL = "cancel";

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

    @McpTool(name = "chrome_list_dialogs", description = "List the native dialogs currently open in Chrome "
        + "windows — JavaScript alert/confirm/prompt, 'Leave site?' (beforeunload) and other Chrome "
        + "confirmation dialogs — with their message text and buttons. "
        + "WHY THIS IS NEEDED: while such a dialog is open the page's JavaScript is blocked, so browser "
        + "automation calls on that tab (click, screenshot, script, close) hang until they time out and keep "
        + "failing until the dialog is answered; the automation extension itself cannot answer it. "
        + "Call this when a browser tool times out or reports an unresponsive renderer, then answer the "
        + "dialog with chrome_dismiss_dialog. "
        + "A dialog raised by a background tab is not shown (and not listed) until that tab is selected — "
        + "use chrome_activate_tab on the tab first. Non-blocking bubbles (translate, save password) are "
        + "not listed. Windows-only (Windows UI Automation).")
    public String chromeListDialogs(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId
    ) {
        log.info("MCP tool invoked: chrome_list_dialogs sessionId={}", sessionId);
        try {
            this.projectSessionRegistry.require(sessionId);
        } catch (final IllegalStateException ex) {
            return "Error: " + ex.getMessage();
        }
        try {
            return ChromeDialogIndex.parse(this.host.listDialogs()).render();
        } catch (final RuntimeException ex) {
            return "Error listing Chrome dialogs: " + ex.getMessage();
        }
    }

    @McpTool(name = "chrome_dismiss_dialog", description = "Answer a native Chrome dialog (JavaScript "
        + "alert/confirm/prompt, 'Leave site?' and similar) by pressing one of its buttons, which unblocks "
        + "the page and lets hung browser automation calls work again. "
        + "Choose the button with `action` — 'accept' presses the first button (OK / Leave), 'cancel' the "
        + "last (Cancel; for a one-button alert that is the OK button) — or name it exactly with `button` "
        + "as shown by chrome_list_dialogs. Decide deliberately: accepting a confirm() lets the page carry "
        + "out whatever it asked about. "
        + "With a single open dialog no address is needed; if several are open, pass `hwnd` and "
        + "`dialogIndex` from chrome_list_dialogs. If no dialog is found but a browser tool is hanging, the "
        + "dialog belongs to a background tab: select that tab with chrome_activate_tab and retry. "
        + "Windows-only (Windows UI Automation).")
    public String chromeDismissDialog(
        @McpToolParam(description = SESSION_PARAM_DESC) final String sessionId,
        @McpToolParam(description = "'accept' or 'cancel'. Required unless `button` is given.",
            required = false) final String action,
        @McpToolParam(description = "Button label to press, case-insensitive, as listed by "
            + "chrome_list_dialogs. Overrides `action`.", required = false) final String button,
        @McpToolParam(description = "Window handle from chrome_list_dialogs. Only needed when several "
            + "dialogs are open.", required = false) final Long hwnd,
        @McpToolParam(description = "Dialog index within the window, from chrome_list_dialogs. Only needed "
            + "when several dialogs are open.", required = false) final Integer dialogIndex
    ) {
        log.info("MCP tool invoked: chrome_dismiss_dialog action={} button={} hwnd={} dialogIndex={} sessionId={}",
            action, button, hwnd, dialogIndex, sessionId);
        try {
            this.projectSessionRegistry.require(sessionId);
        } catch (final IllegalStateException ex) {
            return "Error: " + ex.getMessage();
        }

        final boolean byName = button != null && !button.isBlank();
        final boolean accept = ACTION_ACCEPT.equalsIgnoreCase(action == null ? "" : action.trim());
        final boolean cancel = ACTION_CANCEL.equalsIgnoreCase(action == null ? "" : action.trim());
        if (!byName && !accept && !cancel) {
            return "Error: specify action ('accept' or 'cancel'), or button (a label from chrome_list_dialogs).";
        }

        try {
            final ChromeDialogIndex index = ChromeDialogIndex.parse(this.host.listDialogs());
            final List<ChromeDialog> candidates = index.select(hwnd, dialogIndex);
            if (candidates.isEmpty()) {
                return index.dialogs().isEmpty()
                    ? index.render()
                    : "No Chrome dialog at hwnd=" + hwnd + " dialogIndex=" + dialogIndex + ".\n\n" + index.render();
            }
            if (candidates.size() > 1) {
                return candidates.size() + " dialogs are open. Pass hwnd + dialogIndex to pick one:\n\n"
                    + index.render();
            }

            final ChromeDialog dialog = candidates.getFirst();
            final DialogButton target;
            if (byName) {
                final List<DialogButton> matches = dialog.matchButtons(button);
                if (matches.size() != 1) {
                    return (matches.isEmpty() ? "No button matches \"" : "Several buttons match \"")
                        + button + "\" in this dialog:\n" + dialog.describe();
                }
                target = matches.getFirst();
            } else {
                final Optional<DialogButton> chosen = accept ? dialog.acceptButton() : dialog.cancelButton();
                if (chosen.isEmpty()) {
                    return "Error dismissing Chrome dialog: the dialog has no buttons:\n" + dialog.describe();
                }
                target = chosen.get();
            }
            return this.dismiss(dialog, target);
        } catch (final RuntimeException ex) {
            return "Error dismissing Chrome dialog: " + ex.getMessage();
        }
    }

    private String dismiss(final ChromeDialog dialog, final DialogButton button) {
        for (final String line : this.host.clickDialogButton(dialog.hwnd(), dialog.index(), button.index())) {
            if (line.startsWith(OK_PREFIX)) {
                return "Pressed '" + line.substring(OK_PREFIX.length()) + "' on dialog \"" + dialog.title() + "\""
                    + (dialog.texts().isEmpty() ? "" : " (" + String.join(" / ", dialog.texts()) + ")") + ".\n"
                    + "The page is unblocked — retry the browser call that was hanging.";
            }
            if (line.startsWith(ERR_PREFIX)) {
                return "Error dismissing Chrome dialog: " + line.substring(ERR_PREFIX.length());
            }
        }
        return "Error dismissing Chrome dialog: unexpected reply from the Chrome UIA host.";
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
