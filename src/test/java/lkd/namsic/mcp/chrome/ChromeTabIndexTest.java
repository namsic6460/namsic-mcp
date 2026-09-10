package lkd.namsic.mcp.chrome;

import lkd.namsic.mcp.chrome.ChromeTabIndex.ChromeTab;
import lkd.namsic.mcp.chrome.ChromeTabIndex.ChromeWindow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChromeTabIndexTest {

    /** 실제 chrome-uia-host.ps1 출력 형태 (탭 이름에 크롬이 붙이는 접미사 포함). */
    private static List<String> sampleLines() {
        return List.of(
            "W\t132886\tNormal\tExample Domain - Chrome",
            "T\t132886\t0\t0\tDreamMakerClient - ✅Claude 그룹에 속함 - 메모리 사용량 - 238MB",
            "T\t132886\t1\t1\tExample Domain - ⌛Claude 그룹에 속함",
            "W\t66560\tMinimized\tGitHub - Chrome",
            "T\t66560\t0\t1\tGitHub"
        );
    }

    @Test
    void parsesWindowsAndTabs() {
        ChromeTabIndex index = ChromeTabIndex.parse(sampleLines());

        assertEquals(2, index.windows().size());
        assertTrue(index.errors().isEmpty(), index.errors().toString());

        ChromeWindow first = index.windows().getFirst();
        assertEquals(132886L, first.hwnd());
        assertEquals("Normal", first.visualState());
        assertEquals("Example Domain - Chrome", first.title());
        assertFalse(first.minimized());
        assertEquals(2, first.tabs().size());

        ChromeTab active = first.tabs().get(1);
        assertEquals(1, active.index());
        assertTrue(active.selected());
        assertEquals(132886L, active.hwnd());

        assertFalse(first.tabs().getFirst().selected());
        assertTrue(index.windows().get(1).minimized());
        assertEquals(3, index.allTabs().size());
    }

    @Test
    void matchesPageTitleAsPrefixOfAccessibilityName() {
        ChromeTabIndex index = ChromeTabIndex.parse(sampleLines());

        List<ChromeTab> matches = index.match("Example Domain");

        assertEquals(1, matches.size());
        assertEquals(132886L, matches.getFirst().hwnd());
        assertEquals(1, matches.getFirst().index());
    }

    @Test
    void matchIsCaseInsensitiveAndTrimmed() {
        ChromeTabIndex index = ChromeTabIndex.parse(sampleLines());
        assertEquals(1, index.match("  gitHUB  ").size());
    }

    @Test
    void exactMatchWinsOverPrefixAndSubstring() {
        ChromeTabIndex index = ChromeTabIndex.parse(List.of(
            "W\t1\tNormal\tw",
            "T\t1\t0\t0\tReport",
            "T\t1\t1\t0\tReport - memory 12MB",
            "T\t1\t2\t0\tQuarterly Report archive"
        ));

        List<ChromeTab> matches = index.match("Report");

        assertEquals(1, matches.size());
        assertEquals(0, matches.getFirst().index());
    }

    @Test
    void prefixMatchWinsOverSubstring() {
        ChromeTabIndex index = ChromeTabIndex.parse(List.of(
            "W\t1\tNormal\tw",
            "T\t1\t0\t0\tReport - memory 12MB",
            "T\t1\t1\t0\tQuarterly Report archive"
        ));

        List<ChromeTab> matches = index.match("Report");

        assertEquals(1, matches.size());
        assertEquals(0, matches.getFirst().index());
    }

    @Test
    void returnsEveryCandidateWhenAmbiguousAtSameTier() {
        ChromeTabIndex index = ChromeTabIndex.parse(List.of(
            "W\t1\tNormal\tw1",
            "T\t1\t0\t0\tDashboard - memory 10MB",
            "W\t2\tNormal\tw2",
            "T\t2\t0\t0\tDashboard - memory 30MB"
        ));

        List<ChromeTab> matches = index.match("Dashboard");

        assertEquals(2, matches.size());
        assertEquals(1L, matches.getFirst().hwnd());
        assertEquals(2L, matches.get(1).hwnd());
    }

    @Test
    void matchReturnsEmptyForBlankOrNullQuery() {
        ChromeTabIndex index = ChromeTabIndex.parse(sampleLines());
        assertTrue(index.match("   ").isEmpty());
        assertTrue(index.match(null).isEmpty());
    }

    @Test
    void collectsHostErrorRecords() {
        ChromeTabIndex index = ChromeTabIndex.parse(List.of(
            "W\t1\tNormal\tw1",
            "ERR\ttab strip not found for hwnd 1"
        ));

        assertEquals(1, index.windows().size());
        assertTrue(index.windows().getFirst().tabs().isEmpty());
        assertEquals(List.of("tab strip not found for hwnd 1"), index.errors());
    }

    @Test
    void reportsMalformedRecordsWithoutLosingValidOnes() {
        ChromeTabIndex index = ChromeTabIndex.parse(List.of(
            "W\tnot-a-number\tNormal\tbroken",
            "T\t1\t0\t0\torphan tab",
            "W\t7\tNormal\tgood",
            "T\t7\tnope\t0\tbad index",
            "T\t7\t0\t1\tfine",
            "X\tunknown"
        ));

        assertEquals(1, index.windows().size());
        assertEquals(7L, index.windows().getFirst().hwnd());
        assertEquals(1, index.windows().getFirst().tabs().size());
        assertEquals("fine", index.windows().getFirst().tabs().getFirst().name());
        assertEquals(4, index.errors().size(), index.errors().toString());
    }

    @Test
    void parseHandlesNullAndEmptyInput() {
        assertTrue(ChromeTabIndex.parse(null).windows().isEmpty());
        assertTrue(ChromeTabIndex.parse(List.of()).windows().isEmpty());
        assertTrue(ChromeTabIndex.parse(List.of("", "   ")).windows().isEmpty());
    }

    @Test
    void rendersTabsWithActiveMarker() {
        String rendered = ChromeTabIndex.parse(sampleLines()).render();

        assertTrue(rendered.contains("Chrome windows (2):"), rendered);
        assertTrue(rendered.contains("hwnd=132886 state=Normal"), rendered);
        assertTrue(rendered.contains("state=Minimized"), rendered);
        assertTrue(rendered.contains("[1] * Example Domain"), rendered);
        assertTrue(rendered.contains("[0]   DreamMakerClient"), rendered);
    }

    @Test
    void rendersEmptyAndWarnings() {
        assertTrue(ChromeTabIndex.parse(List.of()).render().contains("No Chrome windows found"));

        String rendered = ChromeTabIndex.parse(List.of("ERR\tboom")).render();
        assertTrue(rendered.contains("Warnings:"), rendered);
        assertTrue(rendered.contains("boom"), rendered);
    }
}
