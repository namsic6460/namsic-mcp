package lkd.namsic.mcp.chrome;

import lkd.namsic.mcp.config.SessionProperties;
import lkd.namsic.mcp.session.ProjectSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChromeMcpToolsTest {

    private static final String UNKNOWN_SID = "00000000-0000-0000-0000-000000000000";

    private static final List<String> TWO_TABS = List.of(
        "W\t132886\tNormal\tExample Domain - Chrome",
        "T\t132886\t0\t1\tDreamMakerClient - 메모리 사용량 - 238MB",
        "T\t132886\t1\t0\tExample Domain - Claude 그룹에 속함"
    );

    @TempDir
    Path tempScreenshotBase;

    private ChromeUiaHost host;
    private ChromeMcpTools tools;
    private String sessionId;

    @BeforeEach
    void setUp() {
        ProjectSessionRegistry registry = new ProjectSessionRegistry(new SessionProperties(this.tempScreenshotBase));
        this.host = mock(ChromeUiaHost.class);
        this.tools = new ChromeMcpTools(this.host, registry);
        this.sessionId = registry.init("chrome-test").sessionId();
    }

    // ===== 세션 게이트 =====

    @Test
    void listTabsRequiresKnownSession() {
        String result = this.tools.chromeListTabs(UNKNOWN_SID);

        assertTrue(result.startsWith("Error: Unknown sessionId"), result);
        verify(this.host, never()).listTabs();
    }

    @Test
    void activateTabRequiresKnownSession() {
        String result = this.tools.chromeActivateTab(UNKNOWN_SID, "Example Domain", null, null, null);

        assertTrue(result.startsWith("Error: Unknown sessionId"), result);
        verify(this.host, never()).listTabs();
    }

    // ===== 목록 =====

    @Test
    void listTabsRendersHostOutput() {
        when(this.host.listTabs()).thenReturn(TWO_TABS);

        String result = this.tools.chromeListTabs(this.sessionId);

        assertTrue(result.contains("Chrome windows (1):"), result);
        assertTrue(result.contains("[0] * DreamMakerClient"), result);
        assertTrue(result.contains("[1]   Example Domain"), result);
    }

    @Test
    void listTabsReportsHostFailure() {
        when(this.host.listTabs()).thenThrow(new IllegalStateException("Chrome UIA host timed out after 90000 ms"));

        String result = this.tools.chromeListTabs(this.sessionId);

        assertTrue(result.startsWith("Error listing Chrome tabs: "), result);
        assertTrue(result.contains("timed out"), result);
    }

    // ===== 제목 매칭 활성화 =====

    @Test
    void activatesUniqueTitleMatchAndBringsWindowToFrontByDefault() {
        when(this.host.listTabs()).thenReturn(TWO_TABS);
        when(this.host.activateTab(132886L, 1, true))
            .thenReturn(List.of("OK\tExample Domain - Claude 그룹에 속함"));

        String result = this.tools.chromeActivateTab(this.sessionId, "Example Domain", null, null, null);

        assertTrue(result.startsWith("Activated tab: Example Domain"), result);
        assertTrue(result.contains("requestAnimationFrame"), result);
        verify(this.host).activateTab(132886L, 1, true);
    }

    @Test
    void honoursBringToFrontFalse() {
        when(this.host.listTabs()).thenReturn(TWO_TABS);
        when(this.host.activateTab(132886L, 1, false)).thenReturn(List.of("OK\tExample Domain"));

        this.tools.chromeActivateTab(this.sessionId, "Example Domain", null, null, false);

        verify(this.host).activateTab(132886L, 1, false);
    }

    @Test
    void reportsAmbiguousTitleWithoutActivating() {
        when(this.host.listTabs()).thenReturn(List.of(
            "W\t1\tNormal\tw1",
            "T\t1\t0\t0\tDashboard - memory 10MB",
            "W\t2\tNormal\tw2",
            "T\t2\t3\t0\tDashboard - memory 30MB"
        ));

        String result = this.tools.chromeActivateTab(this.sessionId, "Dashboard", null, null, null);

        assertTrue(result.contains("matches 2 tabs"), result);
        assertTrue(result.contains("hwnd=1 tabIndex=0"), result);
        assertTrue(result.contains("hwnd=2 tabIndex=3"), result);
        verify(this.host, never()).activateTab(anyLong(), anyInt(), anyBoolean());
    }

    @Test
    void reportsNoMatchWithFullTabListing() {
        when(this.host.listTabs()).thenReturn(TWO_TABS);

        String result = this.tools.chromeActivateTab(this.sessionId, "Nonexistent", null, null, null);

        assertTrue(result.startsWith("No Chrome tab matches \"Nonexistent\""), result);
        assertTrue(result.contains("Chrome windows (1):"), result);
        verify(this.host, never()).activateTab(anyLong(), anyInt(), anyBoolean());
    }

    @Test
    void requiresTitleOrExplicitTarget() {
        String result = this.tools.chromeActivateTab(this.sessionId, "  ", null, null, null);

        assertTrue(result.startsWith("Error: specify title, or both hwnd and tabIndex"), result);
        verify(this.host, never()).listTabs();
    }

    @Test
    void partialExplicitTargetFallsBackToTitleRequirement() {
        String result = this.tools.chromeActivateTab(this.sessionId, null, 132886L, null, null);

        assertTrue(result.startsWith("Error: specify title, or both hwnd and tabIndex"), result);
        verify(this.host, never()).listTabs();
    }

    // ===== 명시적 좌표 활성화 =====

    @Test
    void explicitHwndAndIndexSkipTitleLookup() {
        when(this.host.activateTab(4242L, 2, true)).thenReturn(List.of("OK\tSome Tab"));

        String result = this.tools.chromeActivateTab(this.sessionId, null, 4242L, 2, null);

        assertTrue(result.startsWith("Activated tab: Some Tab"), result);
        verify(this.host, never()).listTabs();
        verify(this.host).activateTab(4242L, 2, true);
    }

    @Test
    void explicitTargetWinsOverTitle() {
        when(this.host.activateTab(4242L, 2, true)).thenReturn(List.of("OK\tSome Tab"));

        this.tools.chromeActivateTab(this.sessionId, "Example Domain", 4242L, 2, null);

        verify(this.host, never()).listTabs();
        verify(this.host).activateTab(4242L, 2, true);
    }

    // ===== 호스트 오류 전달 =====

    @Test
    void surfacesHostErrorRecord() {
        when(this.host.activateTab(9L, 0, true))
            .thenReturn(List.of("ERR\ttab index 0 out of range (window has 0 tabs)"));

        String result = this.tools.chromeActivateTab(this.sessionId, null, 9L, 0, null);

        assertTrue(result.startsWith("Error activating Chrome tab: tab index 0 out of range"), result);
    }

    @Test
    void surfacesUnexpectedHostReply() {
        when(this.host.activateTab(9L, 0, true)).thenReturn(List.of());

        String result = this.tools.chromeActivateTab(this.sessionId, null, 9L, 0, null);

        assertTrue(result.contains("unexpected reply"), result);
    }

    @Test
    void surfacesHostException() {
        when(this.host.activateTab(9L, 0, true))
            .thenThrow(new IllegalStateException("Failed to start PowerShell ('powershell')"));

        String result = this.tools.chromeActivateTab(this.sessionId, null, 9L, 0, null);

        assertTrue(result.startsWith("Error activating Chrome tab: Failed to start PowerShell"), result);
        assertFalse(result.contains("Activated"), result);
    }

    // ===== 다이얼로그 =====

    private static final List<String> CONFIRM_DIALOG = List.of(
        "D\t655724\t0\texample.com 내용:",
        "X\t655724\t0\t정말 창을 닫으시겠습니까?",
        "B\t655724\t0\t0\t1\t확인",
        "B\t655724\t0\t1\t1\t취소"
    );

    private static final List<String> TWO_DIALOGS = List.of(
        "D\t1\t0\tfirst",
        "B\t1\t0\t0\t1\tOK",
        "D\t2\t0\tsecond",
        "B\t2\t0\t0\t1\tLeave",
        "B\t2\t0\t1\t1\tCancel"
    );

    @Test
    void dialogToolsRequireKnownSession() {
        String listed = this.tools.chromeListDialogs(UNKNOWN_SID);
        String dismissed = this.tools.chromeDismissDialog(UNKNOWN_SID, "accept", null, null, null);

        assertTrue(listed.startsWith("Error: Unknown sessionId"), listed);
        assertTrue(dismissed.startsWith("Error: Unknown sessionId"), dismissed);
        verify(this.host, never()).listDialogs();
    }

    @Test
    void listDialogsRendersHostOutput() {
        when(this.host.listDialogs()).thenReturn(CONFIRM_DIALOG);

        String result = this.tools.chromeListDialogs(this.sessionId);

        assertTrue(result.contains("Chrome dialogs (1):"), result);
        assertTrue(result.contains("buttons: [확인] [취소]"), result);
    }

    @Test
    void listDialogsReportsHostFailure() {
        when(this.host.listDialogs()).thenThrow(new IllegalStateException("Chrome UIA host timed out"));

        String result = this.tools.chromeListDialogs(this.sessionId);

        assertTrue(result.startsWith("Error listing Chrome dialogs: "), result);
    }

    @Test
    void acceptPressesFirstButtonOfTheOnlyDialog() {
        when(this.host.listDialogs()).thenReturn(CONFIRM_DIALOG);
        when(this.host.clickDialogButton(655724L, 0, 0)).thenReturn(List.of("OK\t확인"));

        String result = this.tools.chromeDismissDialog(this.sessionId, "Accept", null, null, null);

        assertTrue(result.startsWith("Pressed '확인' on dialog \"example.com 내용:\""), result);
        assertTrue(result.contains("정말 창을 닫으시겠습니까?"), result);
        verify(this.host).clickDialogButton(655724L, 0, 0);
    }

    @Test
    void cancelPressesLastButton() {
        when(this.host.listDialogs()).thenReturn(CONFIRM_DIALOG);
        when(this.host.clickDialogButton(655724L, 0, 1)).thenReturn(List.of("OK\t취소"));

        String result = this.tools.chromeDismissDialog(this.sessionId, "cancel", null, null, null);

        assertTrue(result.startsWith("Pressed '취소'"), result);
    }

    @Test
    void buttonNameOverridesAction() {
        when(this.host.listDialogs()).thenReturn(CONFIRM_DIALOG);
        when(this.host.clickDialogButton(655724L, 0, 1)).thenReturn(List.of("OK\t취소"));

        this.tools.chromeDismissDialog(this.sessionId, "accept", "취소", null, null);

        verify(this.host).clickDialogButton(655724L, 0, 1);
    }

    @Test
    void unknownButtonNameListsTheDialogWithoutPressing() {
        when(this.host.listDialogs()).thenReturn(CONFIRM_DIALOG);

        String result = this.tools.chromeDismissDialog(this.sessionId, null, "Leave", null, null);

        assertTrue(result.startsWith("No button matches \"Leave\""), result);
        assertTrue(result.contains("[확인] [취소]"), result);
        verify(this.host, never()).clickDialogButton(anyLong(), anyInt(), anyInt());
    }

    @Test
    void dismissRequiresActionOrButton() {
        String missing = this.tools.chromeDismissDialog(this.sessionId, null, " ", null, null);
        String invalid = this.tools.chromeDismissDialog(this.sessionId, "yes", null, null, null);

        assertTrue(missing.startsWith("Error: specify action"), missing);
        assertTrue(invalid.startsWith("Error: specify action"), invalid);
        verify(this.host, never()).listDialogs();
    }

    @Test
    void dismissWithNoDialogExplainsBackgroundTabs() {
        when(this.host.listDialogs()).thenReturn(List.of());

        String result = this.tools.chromeDismissDialog(this.sessionId, "accept", null, null, null);

        assertTrue(result.startsWith("No Chrome dialog is open."), result);
        assertTrue(result.contains("chrome_activate_tab"), result);
        verify(this.host, never()).clickDialogButton(anyLong(), anyInt(), anyInt());
    }

    @Test
    void severalDialogsNeedAnAddress() {
        when(this.host.listDialogs()).thenReturn(TWO_DIALOGS);

        String result = this.tools.chromeDismissDialog(this.sessionId, "accept", null, null, null);

        assertTrue(result.startsWith("2 dialogs are open."), result);
        verify(this.host, never()).clickDialogButton(anyLong(), anyInt(), anyInt());
    }

    @Test
    void addressPicksOneOfSeveralDialogs() {
        when(this.host.listDialogs()).thenReturn(TWO_DIALOGS);
        when(this.host.clickDialogButton(2L, 0, 1)).thenReturn(List.of("OK\tCancel"));

        String result = this.tools.chromeDismissDialog(this.sessionId, "cancel", null, 2L, 0);

        assertTrue(result.startsWith("Pressed 'Cancel' on dialog \"second\""), result);
    }

    @Test
    void unknownAddressListsOpenDialogs() {
        when(this.host.listDialogs()).thenReturn(TWO_DIALOGS);

        String result = this.tools.chromeDismissDialog(this.sessionId, "accept", null, 9L, 0);

        assertTrue(result.startsWith("No Chrome dialog at hwnd=9 dialogIndex=0."), result);
        assertTrue(result.contains("Chrome dialogs (2):"), result);
    }

    @Test
    void dismissSurfacesHostErrorRecordAndException() {
        when(this.host.listDialogs()).thenReturn(CONFIRM_DIALOG);
        when(this.host.clickDialogButton(655724L, 0, 0))
            .thenReturn(List.of("ERR\tdialog index 0 out of range (window has 0 dialogs)"))
            .thenThrow(new IllegalStateException("Chrome UIA host timed out"));

        String errorRecord = this.tools.chromeDismissDialog(this.sessionId, "accept", null, null, null);
        String exception = this.tools.chromeDismissDialog(this.sessionId, "accept", null, null, null);

        assertTrue(errorRecord.startsWith("Error dismissing Chrome dialog: dialog index 0 out of range"), errorRecord);
        assertTrue(exception.startsWith("Error dismissing Chrome dialog: Chrome UIA host timed out"), exception);
    }
}
