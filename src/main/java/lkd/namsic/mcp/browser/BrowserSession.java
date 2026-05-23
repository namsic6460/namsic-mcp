package lkd.namsic.mcp.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
class BrowserSession {

    private static final int LOG_CAP = 1000;
    private static final long EXECUTOR_AWAIT_MS = 120_000L;

    final ExecutorService executor;
    Playwright playwright;
    Browser browser;
    BrowserContext context;
    Page page;
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

    void close() {
        try {
            this.executor.submit(() -> {
                closeQuietly(this.page);
                closeQuietly(this.context);
                closeQuietly(this.browser);
                closeQuietly(this.playwright);
            }).get(30, TimeUnit.SECONDS);
        } catch (final Exception ex) {
            log.warn("BrowserSession close timeout/error", ex);
        } finally {
            this.executor.shutdownNow();
        }
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
