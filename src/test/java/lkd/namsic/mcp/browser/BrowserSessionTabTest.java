package lkd.namsic.mcp.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Tab 자료구조/활성 미러/closeTab 정리 규칙 — Playwright 없이 mock Page/Context로 검증. */
class BrowserSessionTabTest {

    @TempDir
    Path tempDir;

    private BrowserSession newSession() {
        return new BrowserSession("tab-test", this.tempDir);
    }

    @Test
    void registerTabAssignsMonotonicIdsAndSetActiveMirrorsPageAndContext() {
        BrowserSession s = this.newSession();
        Page p1 = mock(Page.class);
        Page p2 = mock(Page.class);
        BrowserContext ctx = mock(BrowserContext.class);

        BrowserSession.Tab t1 = s.registerTab(p1, ctx);
        BrowserSession.Tab t2 = s.registerTab(p2, ctx);
        assertEquals("tab-1", t1.tabId);
        assertEquals("tab-2", t2.tabId);

        s.setActive(t1);
        assertSame(t1, s.activeTab);
        assertSame(p1, s.page);
        assertSame(ctx, s.context);

        s.setActive(t2);
        assertSame(t2, s.activeTab);
        assertSame(p2, s.page);
    }

    @Test
    void contextLabelIsStablePerContext() {
        BrowserSession s = this.newSession();
        BrowserContext ctxA = mock(BrowserContext.class);
        BrowserContext ctxB = mock(BrowserContext.class);

        assertEquals("ctx-1", s.contextLabelFor(ctxA));
        assertEquals("ctx-2", s.contextLabelFor(ctxB));
        assertEquals("ctx-1", s.contextLabelFor(ctxA));
    }

    @Test
    void closeTabRejectsUnknownTab() {
        BrowserSession s = this.newSession();
        BrowserContext ctx = mock(BrowserContext.class);
        s.setActive(s.registerTab(mock(Page.class), ctx));
        s.registerTab(mock(Page.class), ctx);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> s.closeTabInThread("tab-99"));
        assertTrue(ex.getMessage().contains("no such tab"), ex.getMessage());
    }

    @Test
    void closeTabRejectsLastRemainingTab() {
        BrowserSession s = this.newSession();
        BrowserSession.Tab only = s.registerTab(mock(Page.class), mock(BrowserContext.class));
        s.setActive(only);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> s.closeTabInThread(only.tabId));
        assertTrue(ex.getMessage().contains("last remaining tab"), ex.getMessage());
    }

    @Test
    void closeActiveTabSwitchesToFirstRemainingAndDisposesOrphanContext() {
        BrowserSession s = this.newSession();
        Page p1 = mock(Page.class);
        Page p2 = mock(Page.class);
        BrowserContext ctxA = mock(BrowserContext.class);
        BrowserContext ctxB = mock(BrowserContext.class);
        s.initialContext = ctxA;
        BrowserSession.Tab t1 = s.registerTab(p1, ctxA);
        BrowserSession.Tab t2 = s.registerTab(p2, ctxB);
        s.setActive(t2);

        BrowserSession.Tab newActive = s.closeTabInThread(t2.tabId);

        assertSame(t1, newActive);
        assertSame(t1, s.activeTab);
        assertSame(p1, s.page);
        assertEquals(1, s.tabs.size());
        verify(p2).close();
        // ctxB의 마지막 탭이었으므로 컨텍스트도 정리, 최초 컨텍스트(ctxA)는 보존
        verify(ctxB).close();
        verify(ctxA, never()).close();
    }

    @Test
    void closeNonActiveTabKeepsActiveAndNeverClosesInitialContext() {
        BrowserSession s = this.newSession();
        Page p1 = mock(Page.class);
        BrowserContext ctxA = mock(BrowserContext.class);
        BrowserContext ctxB = mock(BrowserContext.class);
        s.initialContext = ctxA;
        BrowserSession.Tab t1 = s.registerTab(p1, ctxA);
        BrowserSession.Tab t2 = s.registerTab(mock(Page.class), ctxB);
        s.setActive(t2);

        BrowserSession.Tab stillActive = s.closeTabInThread(t1.tabId);

        assertSame(t2, stillActive);
        assertSame(t2, s.activeTab);
        verify(p1).close();
        // initialContext의 마지막 탭을 닫아도 컨텍스트는 close_session/destroy가 책임진다
        verify(ctxA, never()).close();
        verify(ctxB, never()).close();
    }
}
