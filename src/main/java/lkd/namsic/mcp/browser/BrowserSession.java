package lkd.namsic.mcp.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.FileChooser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.ScreenshotScale;
import com.microsoft.playwright.options.ScreenshotType;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Slf4j
class BrowserSession {

    private static final int LOG_CAP = 1000;
    private static final long EXECUTOR_AWAIT_MS = 120_000L;
    private static final int JPEG_QUALITY = 80;

    record Shot(byte[] bytes, String filename, String mimeType) {

        String base64() {
            return Base64.getEncoder().encodeToString(this.bytes());
        }
    }

    /**
     * 세션 내 탭 1개 = (Page + 소속 BrowserContext). 같은 컨텍스트의 탭끼리는 쿠키를 공유하고,
     * browser_new_context로 만든 탭은 독립 쿠키(시크릿 창처럼)를 가진다.
     * route/CDP/file chooser 상태는 탭 단위로 관리한다.
     */
    static final class Tab {

        final String tabId;
        final Page page;
        final BrowserContext context;
        /** browser_unroute를 위한 패턴 → 등록 핸들러 목록. executor 스레드에서만 접근한다. */
        final Map<String, List<Consumer<Route>>> routes = new LinkedHashMap<>();
        /** lazy 생성된 CDP 세션 (network conditions / screencast 용). executor 스레드에서만 접근. */
        CDPSession cdp;
        /** browser_expect_file_chooser로 arming된 핸들러 — 이 탭의 page에 바인딩된다. */
        volatile Consumer<FileChooser> armedFileChooser;

        Tab(final String tabId, final Page page, final BrowserContext context) {
            this.tabId = tabId;
            this.page = page;
            this.context = context;
        }
    }

    final ExecutorService executor;
    Playwright playwright;
    Browser browser;
    /** 활성 탭의 컨텍스트 미러 — 기존 도구들이 직접 참조한다. {@link #setActive}가 갱신. */
    BrowserContext context;
    /** 활성 탭의 페이지 미러 — 기존 도구들이 직접 참조한다. {@link #setActive}가 갱신. */
    Page page;
    /** 탭 레지스트리 (등록 순서 유지). executor 스레드에서만 변경/순회한다. */
    final Map<String, Tab> tabs = new LinkedHashMap<>();
    final AtomicInteger tabSeq = new AtomicInteger();
    /** 컨텍스트 → "ctx-N" 안정 라벨 (browser_list_tabs 그룹 표시용). executor 스레드 전용. */
    private final Map<BrowserContext, String> contextLabels = new LinkedHashMap<>();
    private final AtomicInteger ctxSeq = new AtomicInteger();
    volatile Tab activeTab;
    /** createSession이 만든 최초 컨텍스트 — closeTabInThread가 아닌 close()가 닫기를 책임진다. */
    BrowserContext initialContext;
    final List<String> consoleLogs = Collections.synchronizedList(new ArrayList<>());
    final List<String> pageErrors = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger seq = new AtomicInteger();
    final Path hostScreenshotDir;

    BrowserSession(final String sessionId, final Path hostScreenshotDir) {
        this.hostScreenshotDir = hostScreenshotDir;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "browser-session-" + sessionId);
            t.setDaemon(true);
            return t;
        });
    }

    <T> T submit(final Callable<T> task) {
        try {
            return this.executor.submit(task).get(EXECUTOR_AWAIT_MS, TimeUnit.MILLISECONDS);
        } catch (final ExecutionException ex) {
            final Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause != null ? cause : ex);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        } catch (final TimeoutException ex) {
            throw new RuntimeException("Browser operation timed out after " + EXECUTOR_AWAIT_MS + " ms", ex);
        }
    }

    /** 활성 탭을 교체하고 기존 도구들이 참조하는 page/context 미러를 갱신한다. executor 스레드에서 호출. */
    void setActive(final Tab tab) {
        this.activeTab = tab;
        this.page = tab.page;
        this.context = tab.context;
    }

    /** 새 탭을 채번/등록한다. executor 스레드에서 호출. */
    Tab registerTab(final Page newPage, final BrowserContext owner) {
        final Tab tab = new Tab("tab-" + this.tabSeq.incrementAndGet(), newPage, owner);
        this.tabs.put(tab.tabId, tab);
        return tab;
    }

    /** 컨텍스트의 안정적인 "ctx-N" 라벨을 반환한다 (browser_list_tabs 그룹 표시용). executor 스레드 전용. */
    String contextLabelFor(final BrowserContext ctx) {
        return this.contextLabels.computeIfAbsent(ctx, _ -> "ctx-" + this.ctxSeq.incrementAndGet());
    }

    /** 탭의 콘솔/에러 리스너를 세션 레벨 로그에 탭 접두사를 붙여 연결한다. */
    void attachPageListeners(final Tab tab) {
        tab.page.onConsoleMessage(msg ->
            this.appendLog("[" + tab.tabId + "][" + msg.type() + "] " + msg.text()));
        tab.page.onPageError(err -> this.appendError("[" + tab.tabId + "] " + err));
    }

    /**
     * 탭을 닫는다. executor 스레드에서 호출해야 한다. 마지막 남은 탭은 닫을 수 없다 — 활성
     * 페이지가 사라지면 기존 도구가 전부 깨지므로 browser_close_session을 쓰게 한다.
     * 닫힌 탭이 활성이었다면 남은 탭 중 첫 번째로 전환하고, 해당 컨텍스트의 마지막 탭이었다면
     * (최초 컨텍스트가 아닌 한) 컨텍스트도 함께 정리한다.
     *
     * @return 정리 후의 활성 탭
     */
    Tab closeTabInThread(final String tabId) {
        final Tab tab = this.tabs.get(tabId);
        if (tab == null) {
            throw new IllegalStateException("no such tab: " + tabId + ". Check browser_list_tabs.");
        }
        if (this.tabs.size() == 1) {
            throw new IllegalStateException(
                "cannot close the last remaining tab; use browser_close_session instead.");
        }
        detachCdpQuietly(tab);
        closeQuietly(tab.page);
        this.tabs.remove(tabId);
        if (this.activeTab == tab) {
            this.setActive(this.tabs.values().iterator().next());
        }
        final boolean contextStillUsed = this.tabs.values().stream().anyMatch(t -> t.context == tab.context);
        if (!contextStillUsed && tab.context != this.initialContext) {
            closeQuietly(tab.context);
            this.contextLabels.remove(tab.context);
        }
        return this.activeTab;
    }

    void close() {
        boolean cleanedInThread = false;
        try {
            this.executor.submit(() -> {
                final List<BrowserContext> contexts = new ArrayList<>();
                for (final Tab tab : this.tabs.values()) {
                    detachCdpQuietly(tab);
                    closeQuietly(tab.page);
                    if (!contexts.contains(tab.context)) {
                        contexts.add(tab.context);
                    }
                }
                this.tabs.clear();
                contexts.forEach(BrowserSession::closeQuietly);
                if (this.initialContext != null && !contexts.contains(this.initialContext)) {
                    closeQuietly(this.initialContext);
                }
                closeQuietly(this.browser);
                closeQuietly(this.playwright);
            }).get(30, TimeUnit.SECONDS);
            cleanedInThread = true;
        } catch (final Exception ex) {
            log.warn("BrowserSession close timeout/error", ex);
        } finally {
            this.executor.shutdownNow();
        }
        if (!cleanedInThread) {
            // 장시간 작업(record_video 등)이 executor를 점유하면 위 정리 작업은 큐에서 시작도 못 한 채
            // shutdownNow로 폐기된다 — 인터럽트된 작업이 끝나길 잠시 기다린 뒤 호출 스레드에서
            // browser/playwright를 직접 닫아 Playwright 드라이버 프로세스 잔존을 막는다.
            try {
                //noinspection ResultOfMethodCallIgnored
                this.executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            closeQuietly(this.browser);
            closeQuietly(this.playwright);
        }
    }

    private static void detachCdpQuietly(final Tab tab) {
        if (tab.cdp == null) return;
        try {
            tab.cdp.detach();
        } catch (final RuntimeException ex) {
            log.warn("CDP detach failed for {}", tab.tabId, ex);
        }
        tab.cdp = null;
    }

    private static void closeQuietly(final AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (final Exception e) {
            log.warn("close failed: {}", c.getClass().getSimpleName(), e);
        }
    }

    String returnPathFor(final String filename) {
        return this.hostScreenshotDir.resolve(filename).toAbsolutePath().toString();
    }

    /**
     * Captures the current viewport and writes it to {@link #hostScreenshotDir}. MUST be called
     * on the session executor thread (i.e. inside {@link #submit}); never submits itself, so
     * callers can compose it atomically with other page operations in a single task.
     */
    Shot captureInThread(final String filename, final boolean jpeg) {
        final Page.ScreenshotOptions opts = new Page.ScreenshotOptions()
            .setPath(this.hostScreenshotDir.resolve(filename))
            .setFullPage(false)
            .setScale(ScreenshotScale.CSS);
        final String mimeType;
        if (jpeg) {
            opts.setType(ScreenshotType.JPEG).setQuality(JPEG_QUALITY);
            mimeType = "image/jpeg";
        } else {
            opts.setType(ScreenshotType.PNG);
            mimeType = "image/png";
        }
        return new Shot(this.page.screenshot(opts), filename, mimeType);
    }

    void appendLog(final String entry) {
        synchronized (this.consoleLogs) {
            if (this.consoleLogs.size() >= LOG_CAP) this.consoleLogs.removeFirst();
            this.consoleLogs.add(entry);
        }
    }

    void appendError(final String entry) {
        synchronized (this.pageErrors) {
            if (this.pageErrors.size() >= LOG_CAP) this.pageErrors.removeFirst();
            this.pageErrors.add(entry);
        }
    }
}
