package lkd.namsic.mcp.chrome;

import lkd.namsic.mcp.chrome.ChromeDialogIndex.ChromeDialog;
import lkd.namsic.mcp.chrome.ChromeDialogIndex.DialogButton;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChromeDialogIndexTest {

    /** 실제 chrome-uia-host.ps1 출력 형태 (confirm 다이얼로그 실측). */
    private static List<String> confirmLines() {
        return List.of(
            "D\t655724\t0\texample.com 내용:",
            "X\t655724\t0\t정말 창을 닫으시겠습니까?",
            "B\t655724\t0\t0\t1\t확인",
            "B\t655724\t0\t1\t1\t취소"
        );
    }

    @Test
    void parsesDialogTextAndButtons() {
        ChromeDialogIndex index = ChromeDialogIndex.parse(confirmLines());

        assertTrue(index.errors().isEmpty(), index.errors().toString());
        assertEquals(1, index.dialogs().size());

        ChromeDialog dialog = index.dialogs().getFirst();
        assertEquals(655724L, dialog.hwnd());
        assertEquals(0, dialog.index());
        assertEquals("example.com 내용:", dialog.title());
        assertEquals(List.of("정말 창을 닫으시겠습니까?"), dialog.texts());
        assertEquals(List.of(new DialogButton(0, true, "확인"), new DialogButton(1, true, "취소")), dialog.buttons());
    }

    @Test
    void acceptIsFirstButtonAndCancelIsLast() {
        ChromeDialog dialog = ChromeDialogIndex.parse(confirmLines()).dialogs().getFirst();

        assertEquals("확인", dialog.acceptButton().orElseThrow().name());
        assertEquals("취소", dialog.cancelButton().orElseThrow().name());
    }

    @Test
    void singleButtonAlertUsesTheSameButtonForBothActions() {
        ChromeDialog dialog = ChromeDialogIndex.parse(List.of(
            "D\t1\t0\texample.com says",
            "B\t1\t0\t0\t1\tOK"
        )).dialogs().getFirst();

        assertEquals("OK", dialog.acceptButton().orElseThrow().name());
        assertEquals("OK", dialog.cancelButton().orElseThrow().name());
    }

    @Test
    void matchButtonsPrefersExactOverSubstring() {
        ChromeDialog dialog = ChromeDialogIndex.parse(List.of(
            "D\t1\t0\tLeave site?",
            "B\t1\t0\t0\t1\tLeave",
            "B\t1\t0\t1\t1\tLeave later"
        )).dialogs().getFirst();

        assertEquals(List.of(new DialogButton(0, true, "Leave")), dialog.matchButtons(" leave "));
        assertEquals(2, dialog.matchButtons("lea").size());
        assertTrue(dialog.matchButtons("nope").isEmpty());
        assertTrue(dialog.matchButtons("  ").isEmpty());
    }

    @Test
    void parsesSeveralDialogsAndSelectsByAddress() {
        ChromeDialogIndex index = ChromeDialogIndex.parse(List.of(
            "D\t1\t0\tfirst",
            "B\t1\t0\t0\t1\tOK",
            "D\t2\t0\tsecond",
            "B\t2\t0\t0\t0\tOK"
        ));

        assertEquals(2, index.dialogs().size());
        assertEquals(2, index.select(null, null).size());
        assertEquals("second", index.select(2L, null).getFirst().title());
        assertEquals("second", index.select(2L, 0).getFirst().title());
        assertTrue(index.select(3L, null).isEmpty());
        assertFalse(index.dialogs().get(1).buttons().getFirst().enabled());
    }

    @Test
    void collectsMalformedAndErrorRecords() {
        ChromeDialogIndex index = ChromeDialogIndex.parse(List.of(
            "X\t1\t0\torphan text",
            "D\tnot-a-number\t0\ttitle",
            "D\t1\t0\tok",
            "B\t1\t0\tx\t1\tbad index",
            "ERR\tsomething failed",
            "???"
        ));

        assertEquals(1, index.dialogs().size());
        assertEquals(5, index.errors().size());
        assertTrue(index.errors().contains("something failed"), index.errors().toString());
    }

    @Test
    void rendersEmptyListingWithBackgroundTabHint() {
        String rendered = ChromeDialogIndex.parse(List.of()).render();

        assertTrue(rendered.startsWith("No Chrome dialog is open."), rendered);
        assertTrue(rendered.contains("chrome_activate_tab"), rendered);
    }

    @Test
    void rendersDialogListing() {
        String rendered = ChromeDialogIndex.parse(confirmLines()).render();

        assertTrue(rendered.contains("Chrome dialogs (1):"), rendered);
        assertTrue(rendered.contains("hwnd=655724 dialogIndex=0 \"example.com 내용:\""), rendered);
        assertTrue(rendered.contains("정말 창을 닫으시겠습니까?"), rendered);
        assertTrue(rendered.contains("buttons: [확인] [취소]"), rendered);
    }
}
