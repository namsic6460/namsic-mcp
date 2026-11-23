//! Per-session Android device state holder.
//!
//! All adb I/O of one session is serialized through a FIFO async mutex so a
//! dump and a tap can never interleave.

use std::collections::HashMap;
use std::future::Future;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use tokio_util::sync::CancellationToken;

use super::ui::UiNode;

/// Must exceed the longest single adb timeout (install 120s) and the
/// action + wait (<=110s) + capture (20s) combination.
pub const EXECUTOR_AWAIT_MS: u64 = 150_000;

/// Per-device state, kept across `android_use_device` switches.
#[derive(Debug, Clone, Default)]
pub struct DeviceState {
    /// Last `android_dump_ui` result — target of selector/elementIndex taps.
    pub last_dump: Option<Arc<Vec<UiNode>>>,
    /// Default IME recorded on first bind (restored on close). `None` = not recorded.
    pub original_ime: Option<String>,
    /// Whether the ADBKeyboard IME was activated (restore only then).
    pub adb_keyboard_active: bool,
}

#[derive(Debug)]
struct Inner {
    serial: String,
    device_states: HashMap<String, DeviceState>,
}

pub struct AndroidSession {
    op_lock: tokio::sync::Mutex<()>,
    closed: CancellationToken,
    host_screenshot_dir: PathBuf,
    seq: AtomicU32,
    inner: Mutex<Inner>,
}

impl AndroidSession {
    pub fn new(serial: &str, host_screenshot_dir: &Path) -> Self {
        Self {
            op_lock: tokio::sync::Mutex::new(()),
            closed: CancellationToken::new(),
            host_screenshot_dir: host_screenshot_dir.to_path_buf(),
            seq: AtomicU32::new(0),
            inner: Mutex::new(Inner {
                serial: serial.to_owned(),
                device_states: HashMap::new(),
            }),
        }
    }

    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Serial pinned by `android_use_device`; every adb call uses `-s serial`.
    pub fn serial(&self) -> String {
        self.lock().serial.clone()
    }

    pub fn set_serial(&self, serial: &str) {
        serial.clone_into(&mut self.lock().serial);
    }

    pub fn host_screenshot_dir(&self) -> &Path {
        &self.host_screenshot_dir
    }

    pub fn next_seq(&self) -> u32 {
        self.seq.fetch_add(1, Ordering::SeqCst) + 1
    }

    /// Runs `f` on the active device's state.
    pub fn with_active_state<T>(&self, f: impl FnOnce(&mut DeviceState) -> T) -> T {
        let mut inner = self.lock();
        let serial = inner.serial.clone();
        f(inner.device_states.entry(serial).or_default())
    }

    /// Runs `f` on a specific device's state (created on demand).
    pub fn with_state<T>(&self, serial: &str, f: impl FnOnce(&mut DeviceState) -> T) -> T {
        f(self
            .lock()
            .device_states
            .entry(serial.to_owned())
            .or_default())
    }

    /// `(serial, originalIme)` of every device whose ADBKeyboard is active.
    pub fn keyboard_active_devices(&self) -> Vec<(String, Option<String>)> {
        let mut v: Vec<_> = self
            .lock()
            .device_states
            .iter()
            .filter(|(_, st)| st.adb_keyboard_active)
            .map(|(serial, st)| (serial.clone(), st.original_ime.clone()))
            .collect();
        v.sort();
        v
    }

    pub async fn submit<T, F>(&self, task: F) -> Result<T, String>
    where
        F: Future<Output = Result<T, String>>,
    {
        self.submit_with_timeout(task, EXECUTOR_AWAIT_MS).await
    }

    /// Runs `task` exclusively for this session. The await budget covers queueing
    /// plus execution; on timeout the task future is dropped, which kills its adb child.
    pub async fn submit_with_timeout<T, F>(&self, task: F, await_ms: u64) -> Result<T, String>
    where
        F: Future<Output = Result<T, String>>,
    {
        if self.closed.is_cancelled() {
            return Err("Android session is closed".to_owned());
        }
        let work = async {
            let _guard = self.op_lock.lock().await;
            task.await
        };
        tokio::select! {
            r = tokio::time::timeout(Duration::from_millis(await_ms), work) => match r {
                Ok(v) => v,
                Err(_) => Err(format!("Android operation timed out after {await_ms} ms")),
            },
            () = self.closed.cancelled() => Err("Android session is closed".to_owned()),
        }
    }

    /// Aborts in-flight and future operations (Java `executor.shutdownNow()`).
    pub fn close(&self) {
        self.closed.cancel();
    }

    /// Absolute host path string for a file in the screenshot dir.
    pub fn return_path_for(&self, filename: &str) -> String {
        absolute_string(&self.host_screenshot_dir.join(filename))
    }
}

pub fn absolute_string(path: &Path) -> String {
    std::path::absolute(path)
        .unwrap_or_else(|_| path.to_path_buf())
        .display()
        .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn submit_serializes_tasks() {
        let s = Arc::new(AndroidSession::new("emu", Path::new(".")));
        let log = Arc::new(Mutex::new(Vec::new()));
        let a = {
            let (s, log) = (s.clone(), log.clone());
            tokio::spawn(async move {
                s.submit(async {
                    log.lock().unwrap().push("a-start");
                    tokio::time::sleep(Duration::from_millis(50)).await;
                    log.lock().unwrap().push("a-end");
                    Ok(())
                })
                .await
            })
        };
        tokio::time::sleep(Duration::from_millis(10)).await;
        let b = {
            let (s, log) = (s.clone(), log.clone());
            tokio::spawn(async move {
                s.submit(async {
                    log.lock().unwrap().push("b");
                    Ok(())
                })
                .await
            })
        };
        a.await.unwrap().unwrap();
        b.await.unwrap().unwrap();
        assert_eq!(*log.lock().unwrap(), vec!["a-start", "a-end", "b"]);
    }

    #[tokio::test(start_paused = true)]
    async fn submit_times_out_with_java_message() {
        let s = AndroidSession::new("emu", Path::new("."));
        let r: Result<(), String> = s
            .submit_with_timeout(
                async {
                    tokio::time::sleep(Duration::from_secs(10)).await;
                    Ok(())
                },
                1_000,
            )
            .await;
        assert_eq!(r.unwrap_err(), "Android operation timed out after 1000 ms");
    }

    #[tokio::test]
    async fn closed_session_rejects_tasks() {
        let s = AndroidSession::new("emu", Path::new("."));
        s.close();
        let r: Result<(), String> = s.submit(async { Ok(()) }).await;
        assert!(r.is_err());
    }

    #[test]
    fn device_state_is_tracked_per_serial() {
        let s = AndroidSession::new("a", Path::new("."));
        s.with_active_state(|st| st.adb_keyboard_active = true);
        s.set_serial("b");
        assert!(!s.with_active_state(|st| st.adb_keyboard_active));
        s.set_serial("a");
        assert!(s.with_active_state(|st| st.adb_keyboard_active));
        assert_eq!(s.keyboard_active_devices(), vec![("a".to_owned(), None)]);
        assert_eq!(s.next_seq(), 1);
        assert_eq!(s.next_seq(), 2);
    }
}
