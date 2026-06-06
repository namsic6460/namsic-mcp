package lkd.namsic.mcp.android;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 세션별 안드로이드 기기 상태 홀더 (BrowserSession 대응).
 * 모든 adb 호출을 세션당 단일 데몬 스레드로 직렬화해 dump↔tap 경합을 차단한다.
 */
@Slf4j
class AndroidSession {

    /**
     * 가장 긴 개별 adb 타임아웃(installTimeout 기본 120s)과
     * actAndMaybeCapture의 액션+대기(최대 110s)+캡처(commandTimeout 20s) 조합보다
     * 커야 정상 동작이 submit 타임아웃에 걸리지 않는다.
     */
    private static final long EXECUTOR_AWAIT_MS = 150_000L;

    /**
     * 기기별 상태 — android_use_device로 기기를 오가도 (예: 2계정 = 기기 2대 시나리오)
     * 각 기기의 dump 캐시와 IME 컨텍스트가 보존된다.
     */
    static final class DeviceState {

        /** 직전 android_dump_ui 결과 — android_tap의 selector/elementIndex 타게팅 대상. */
        volatile List<UiNode> lastDump;
        /** 해당 기기를 처음 바인딩할 때 기록한 기본 IME — close 시 복원용. */
        volatile String originalIme;
        /** ADBKeyboard IME로 전환했는지 (전환했을 때만 복원 수행). */
        volatile boolean adbKeyboardActive;
    }

    final ExecutorService executor;
    final Path hostScreenshotDir;
    final AtomicInteger seq = new AtomicInteger();

    /** android_use_device로 고정된 기기 serial. 이후 모든 adb 호출이 {@code -s serial}로 실행된다. */
    volatile String serial;
    /** serial별 기기 상태 — close 시 전 기기 IME 복원을 위해 누적 유지한다. */
    final Map<String, DeviceState> deviceStates = new ConcurrentHashMap<>();

    AndroidSession(final String sessionId, final Path hostScreenshotDir) {
        this.hostScreenshotDir = hostScreenshotDir;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "android-session-" + sessionId);
            t.setDaemon(true);
            return t;
        });
    }

    /** 활성 기기의 상태. android_use_device 이전에 호출하면 예외. */
    DeviceState activeState() {
        final String current = this.serial;
        if (current == null) {
            throw new IllegalStateException("no device bound. Call android_use_device first.");
        }
        return this.stateOf(current);
    }

    DeviceState stateOf(final String deviceSerial) {
        return this.deviceStates.computeIfAbsent(deviceSerial, _ -> new DeviceState());
    }

    <T> T submit(final Callable<T> task) {
        return this.submitWithTimeout(task, EXECUTOR_AWAIT_MS);
    }

    /**
     * 기본 await(150s)보다 오래 걸리는 작업(예: android_record_screen 최대 180s)용.
     * awaitMs는 작업의 실제 상한 + 여유 시간으로 호출자가 계산해 넘긴다.
     */
    <T> T submitWithTimeout(final Callable<T> task, final long awaitMs) {
        final Future<T> future = this.executor.submit(task);
        try {
            return future.get(awaitMs, TimeUnit.MILLISECONDS);
        } catch (final ExecutionException ex) {
            final Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause != null ? cause : ex);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new RuntimeException(ex);
        } catch (final TimeoutException ex) {
            // cancel(true)로 인터럽트하면 ProcessBuilders/AdbCommandRunner가 adb 프로세스를
            // destroyForcibly로 정리한다 — 고아 task가 단일 스레드 executor를 점유해
            // 이후 도구 호출이 연쇄 타임아웃 나는 것을 방지.
            future.cancel(true);
            throw new RuntimeException("Android operation timed out after " + awaitMs + " ms", ex);
        }
    }

    void close() {
        this.executor.shutdownNow();
        try {
            if (!this.executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("AndroidSession executor did not terminate in time");
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    String returnPathFor(final String filename) {
        return this.hostScreenshotDir.resolve(filename).toAbsolutePath().toString();
    }
}
