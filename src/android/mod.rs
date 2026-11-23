//! Native Android app testing MCP tools (pure ADB control).

mod adb;
mod device;
mod session;
#[cfg(test)]
mod tests;
mod ui;

use std::future::Future;
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use dashmap::DashMap;
use rmcp::handler::server::wrapper::Parameters;
use rmcp::model::CallToolResult;
use rmcp::{schemars, tool, tool_router};
use serde::Deserialize;

use self::adb::{AdbRunner, ProcessAdbRunner};
use self::device::{AndroidDeviceService, ScreenshotResult};
use self::session::{AndroidSession, absolute_string};
use self::ui::UiNode;
use crate::config::AndroidConfig;
use crate::result::{err_text, ok_text, text_and_images};
use crate::server::NamsicServer;
use crate::session::{ProjectSessionRegistry, SESSION_PARAM_DESC};
use crate::util::pixel_sampler::{self, java_trim};

const MAX_OPERATION_BUDGET_MS: i64 = 110_000;
/// screenrecord's own device-side limit (3 minutes).
const MAX_RECORD_DURATION_MS: i32 = 180_000;
/// Estimated per-frame screencap overhead used by the capture_timeline budget.
const CAPTURE_OVERHEAD_MS: i64 = 1_000;
/// Java `System.lineSeparator()`.
const LINE_SEP: &str = if cfg!(windows) { "\r\n" } else { "\n" };

macro_rules! screenshot_after_desc {
    () => {
        "Optional. If set (>=0, max 110000), waits this many ms after the action, then captures a JPEG screenshot \
         returned inline plus its saved file path. Prefer this over a separate android_screenshot call to verify \
         the action result in a single round trip. Omit for no screenshot."
    };
}

macro_rules! target_param_note {
    () => {
        "Targeting priority: (1) selector — substring match against the last android_dump_ui's \
         resource-id/text/content-desc; (2) elementIndex — the [index] from the last dump; (3) raw x,y device \
         pixels. Prefer selector/elementIndex (call android_dump_ui first)."
    };
}

/// Returns early with `isError=true` when the Android session cannot be resolved
/// (Java let these exceptions escape the tool method).
macro_rules! require_session {
    ($self:ident, $sid:expr) => {
        match $self.require_session($sid) {
            Ok(s) => s,
            Err(e) => return err_text(e),
        }
    };
}

// ===== parameters =====

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct SessionOnlyParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct UseDeviceParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(
        description = "Device serial from android_list_devices (e.g. 'emulator-5554'). Optional — omit to auto-select the single connected device."
    )]
    pub serial: Option<String>,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct InstallAppParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(description = "Absolute host path to the APK file")]
    pub apk_path: String,
    #[schemars(description = "Keep app data if already installed (-r). Default: true")]
    pub reinstall: Option<bool>,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct LaunchAppParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(description = "Package name (e.g. 'com.example.app')")]
    pub package_name: String,
    #[schemars(
        description = "Activity to start (e.g. '.MainActivity'). Optional — omit to use the launcher activity."
    )]
    pub activity: Option<String>,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct StopAppParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(description = "Package name to stop")]
    pub package_name: String,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct ListPackagesParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(description = "Only third-party (user-installed) packages. Default: false")]
    pub third_party_only: Option<bool>,
    #[schemars(description = "Package-name substring filter (e.g. 'example')")]
    pub name_filter: Option<String>,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct DumpUiParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(description = "Maximum number of elements to return. Default: 200")]
    pub max_nodes: Option<i32>,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct TapParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(
        description = "Substring matched against resource-id/text/content-desc of the last android_dump_ui (case-insensitive). Takes priority over elementIndex and x,y."
    )]
    pub selector: Option<String>,
    #[schemars(description = "Element [index] from the last android_dump_ui")]
    pub element_index: Option<i32>,
    #[schemars(
        description = "X coordinate in device pixels (used with y when no selector/elementIndex)"
    )]
    pub x: Option<i32>,
    #[schemars(description = "Y coordinate in device pixels")]
    pub y: Option<i32>,
    #[schemars(description = screenshot_after_desc!())]
    pub screenshot_after_ms: Option<i32>,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct LongPressParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(
        description = "Substring matched against resource-id/text/content-desc of the last android_dump_ui (case-insensitive)"
    )]
    pub selector: Option<String>,
    #[schemars(description = "Element [index] from the last android_dump_ui")]
    pub element_index: Option<i32>,
    #[schemars(description = "X coordinate in device pixels")]
    pub x: Option<i32>,
    #[schemars(description = "Y coordinate in device pixels")]
    pub y: Option<i32>,
    #[schemars(description = "Press duration in ms. Default: 800")]
    pub duration_ms: Option<i32>,
    #[schemars(description = screenshot_after_desc!())]
    pub screenshot_after_ms: Option<i32>,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct SwipeParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(description = "Start X in device pixels")]
    pub x1: i32,
    #[schemars(description = "Start Y in device pixels")]
    pub y1: i32,
    #[schemars(description = "End X in device pixels")]
    pub x2: i32,
    #[schemars(description = "End Y in device pixels")]
    pub y2: i32,
    #[schemars(description = "Swipe duration in ms. Default: 300")]
    pub duration_ms: Option<i32>,
    #[schemars(description = screenshot_after_desc!())]
    pub screenshot_after_ms: Option<i32>,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct PressKeyParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(
        description = "Key name (e.g. 'BACK', 'HOME', 'ENTER') or numeric keycode (e.g. '4')"
    )]
    pub key: String,
    #[schemars(description = screenshot_after_desc!())]
    pub screenshot_after_ms: Option<i32>,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct InputTextParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(description = "Text to type")]
    pub text: String,
    #[schemars(description = screenshot_after_desc!())]
    pub screenshot_after_ms: Option<i32>,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct ScreenshotParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(
        description = "Image format: 'jpeg' (default, downscaled, much smaller) or 'png' (lossless full resolution)."
    )]
    pub format: Option<String>,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct LogcatParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(
        description = "Logcat filter spec. Default: '*:E' (errors only). Example: 'ActivityManager:I MyApp:D *:S'"
    )]
    pub filter_spec: Option<String>,
    #[schemars(description = "Maximum number of recent lines. Default: 200, max: 1000")]
    pub tail_lines: Option<i32>,
    #[schemars(
        description = "Clear the log buffer instead of reading (call again afterwards). Default: false"
    )]
    pub clear_first: Option<bool>,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct SetOfflineParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(description = "true = go offline (wifi+data off), false = back online")]
    pub offline: bool,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct NetworkConditionsParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(
        description = "Network speed: 'full', 'gsm', 'edge', '3g', 'lte', or 'up:down' kbps"
    )]
    pub speed: Option<String>,
    #[schemars(description = "Network delay: 'none', 'gprs', 'edge', or 'min:max' ms")]
    pub delay: Option<String>,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct HttpProxyParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(
        description = "Proxy as 'host:port' (e.g. '10.0.2.2:8888'). Omit or ':0' to clear."
    )]
    pub host_port: Option<String>,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct PushFileParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(description = "Absolute host path of the file to push")]
    pub local_path: String,
    #[schemars(description = "Absolute device destination path, e.g. '/sdcard/Download/test.png'")]
    pub device_path: String,
    #[schemars(
        description = "Broadcast a media-scan so gallery apps index the file. Default: true"
    )]
    pub scan_media: Option<bool>,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct CaptureTimelineParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(description = "Capture interval in ms. Default: 1000")]
    pub interval_ms: Option<i32>,
    #[schemars(description = "Total number of frames. Default: 5")]
    pub count: Option<i32>,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct RecordScreenParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(description = "Recording duration in ms (1..180000)")]
    pub duration_ms: i32,
    #[schemars(
        description = "Video bitrate in Mbps (e.g. 4). Optional — omit for the device default."
    )]
    pub bit_rate_mbps: Option<i32>,
}

#[derive(Debug, Default, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct SamplePixelsParams {
    #[schemars(description = SESSION_PARAM_DESC)]
    pub session_id: String,
    #[schemars(
        description = "Semicolon-separated device-pixel points 'x1,y1;x2,y2'. Optional when rect is given."
    )]
    pub points: Option<String>,
    #[schemars(
        description = "Device-pixel rect 'x,y,w,h' to average over. Optional when points is given."
    )]
    pub rect: Option<String>,
}

// ===== service =====

struct TapTarget {
    x: i32,
    y: i32,
    description: String,
}

pub struct AndroidTools {
    registry: Arc<ProjectSessionRegistry>,
    config: AndroidConfig,
    device: AndroidDeviceService,
    sessions: DashMap<String, Arc<AndroidSession>>,
}

impl AndroidTools {
    pub fn new(registry: Arc<ProjectSessionRegistry>, config: AndroidConfig) -> Self {
        let runner = Arc::new(ProcessAdbRunner::new(config.adb_path.clone()));
        Self::with_runner(registry, config, runner)
    }

    fn with_runner(
        registry: Arc<ProjectSessionRegistry>,
        config: AndroidConfig,
        runner: Arc<dyn AdbRunner>,
    ) -> Self {
        Self {
            device: AndroidDeviceService::new(runner, config.clone()),
            registry,
            config,
            sessions: DashMap::new(),
        }
    }

    /// Java `@PreDestroy`: restores IMEs and closes every session. Idempotent.
    pub async fn shutdown(&self) {
        let sessions: Vec<(String, Arc<AndroidSession>)> = self
            .sessions
            .iter()
            .map(|e| (e.key().clone(), e.value().clone()))
            .collect();
        tracing::info!("Closing all Android sessions (count={})", sessions.len());
        for (_, s) in &sessions {
            self.restore_all_imes_quietly(s).await;
            s.close();
        }
        self.sessions.clear();
    }

    fn require_session(&self, sid: &str) -> Result<Arc<AndroidSession>, String> {
        self.registry
            .require(Some(sid))
            .map_err(|e| e.message().to_owned())?;
        self.sessions
            .get(sid)
            .map(|s| s.value().clone())
            .ok_or_else(|| {
                format!("No Android session for sessionId={sid}. Call android_use_device first.")
            })
    }

    // ===== device selection =====

    pub async fn list_devices(&self, p: SessionOnlyParams) -> String {
        tracing::info!(
            "MCP tool invoked: android_list_devices sessionId={}",
            p.session_id
        );
        if let Err(e) = self.registry.require(Some(&p.session_id)) {
            return format!("Error listing devices: {}", e.message());
        }
        match self.device.list_devices().await {
            Err(e) => format!("Error listing devices: {e}"),
            Ok(devices) if devices.is_empty() => {
                "No devices connected. Start an emulator (e.g. from Android Studio) or connect a device with USB \
                 debugging enabled, then call this again."
                    .to_owned()
            }
            Ok(devices) => {
                let mut sb = String::from("Connected devices:\n");
                for d in &devices {
                    sb.push_str("- ");
                    sb.push_str(&d.describe());
                    sb.push('\n');
                }
                sb.push_str("Select one with android_use_device.");
                sb
            }
        }
    }

    pub async fn use_device(&self, p: UseDeviceParams) -> String {
        tracing::info!(
            "MCP tool invoked: android_use_device serial={:?} sessionId={}",
            p.serial,
            p.session_id
        );
        let project = match self.registry.require(Some(&p.session_id)) {
            Ok(project) => project,
            Err(e) => return format!("Error: {}", e.message()),
        };
        let result: Result<String, String> = async {
            let device = self.device.resolve_device(p.serial.as_deref()).await?;
            let session = self
                .sessions
                .entry(p.session_id.clone())
                .or_insert_with(|| {
                    Arc::new(AndroidSession::new(&device.serial, &project.screenshot_dir))
                })
                .value()
                .clone();
            session.set_serial(&device.serial);
            // Record the default IME only for a device not seen yet (blank = earlier read failure,
            // so it self-corrects on rebind instead of pinning '' and restoring via ime reset).
            let need_ime = session.with_state(&device.serial, |st| {
                st.original_ime
                    .as_deref()
                    .is_none_or(|i| i.trim().is_empty())
            });
            if need_ime {
                let ime = session
                    .submit(async { Ok(self.device.current_ime(&device.serial).await) })
                    .await?;
                session.with_state(&device.serial, |st| st.original_ime = Some(ime));
            }
            tracing::info!(
                "Android session bound: sessionId={} device={}",
                p.session_id,
                device.describe()
            );
            Ok(format!(
                "Selected device {}. All android_* tools in this session now target it. Next: android_launch_app \
                 or android_dump_ui / android_screenshot to inspect the screen.",
                device.describe()
            ))
        }
        .await;
        result.unwrap_or_else(|e| format!("Error selecting device: {e}"))
    }

    // ===== apps =====

    pub async fn install_app(&self, p: InstallAppParams) -> CallToolResult {
        tracing::info!(
            "MCP tool invoked: android_install_app apkPath={} sessionId={}",
            p.apk_path,
            p.session_id
        );
        let s = require_session!(self, &p.session_id);
        let apk = abs_normalize(Path::new(&p.apk_path));
        if !apk.is_file() {
            return ok_text(format!("Error: APK file not found: {}", apk.display()));
        }
        let keep_data = p.reinstall.unwrap_or(true);
        let result = s
            .submit(async { self.device.install(&s.serial(), &apk, keep_data).await })
            .await;
        ok_text(match result {
            Ok(output) => format!("Installed {}: {}", file_name(&apk), java_trim(&output)),
            Err(e) => format!("Error installing app: {e}"),
        })
    }

    pub async fn launch_app(&self, p: LaunchAppParams) -> CallToolResult {
        tracing::info!(
            "MCP tool invoked: android_launch_app package={} activity={:?} sessionId={}",
            p.package_name,
            p.activity,
            p.session_id
        );
        let s = require_session!(self, &p.session_id);
        let result = s
            .submit(async {
                self.device
                    .launch_app(&s.serial(), &p.package_name, p.activity.as_deref())
                    .await
            })
            .await;
        ok_text(match result {
            Ok(output) => {
                s.with_active_state(|st| st.last_dump = None);
                if output.trim().is_empty() {
                    format!("Launched {}", p.package_name)
                } else {
                    format!("Launched {}: {}", p.package_name, java_trim(&output))
                }
            }
            Err(e) => format!("Error launching app: {e}"),
        })
    }

    pub async fn stop_app(&self, p: StopAppParams) -> CallToolResult {
        tracing::info!(
            "MCP tool invoked: android_stop_app package={} sessionId={}",
            p.package_name,
            p.session_id
        );
        let s = require_session!(self, &p.session_id);
        let result = s
            .submit(async { self.device.stop_app(&s.serial(), &p.package_name).await })
            .await;
        ok_text(match result {
            Ok(()) => format!("Stopped {}", p.package_name),
            Err(e) => format!("Error stopping app: {e}"),
        })
    }

    pub async fn list_packages(&self, p: ListPackagesParams) -> CallToolResult {
        tracing::info!(
            "MCP tool invoked: android_list_packages sessionId={}",
            p.session_id
        );
        let s = require_session!(self, &p.session_id);
        let third_party = p.third_party_only == Some(true);
        let result = s
            .submit(async {
                self.device
                    .list_packages(&s.serial(), third_party, p.name_filter.as_deref())
                    .await
            })
            .await;
        ok_text(match result {
            Ok(output) if output.trim().is_empty() => "(no matching packages)".to_owned(),
            Ok(output) => output,
            Err(e) => format!("Error listing packages: {e}"),
        })
    }

    // ===== screen inspection =====

    pub async fn dump_ui(&self, p: DumpUiParams) -> CallToolResult {
        tracing::info!(
            "MCP tool invoked: android_dump_ui sessionId={}",
            p.session_id
        );
        let s = require_session!(self, &p.session_id);
        let max = match p.max_nodes {
            Some(n) if n > 0 => n.unsigned_abs() as usize,
            _ => self.config.max_ui_nodes,
        };
        let result = s
            .submit(async { self.device.dump_ui(&s.serial(), max).await })
            .await;
        ok_text(match result {
            Ok(result) => {
                let nodes = Arc::new(result.nodes.clone());
                s.with_active_state(|st| st.last_dump = Some(nodes));
                ui::to_compact_list(&result)
            }
            Err(e) => format!("Error dumping UI: {e}"),
        })
    }

    // ===== interaction =====

    pub async fn tap(&self, p: TapParams) -> CallToolResult {
        tracing::info!(
            "MCP tool invoked: android_tap selector={:?} index={:?} xy=({:?},{:?}) sessionId={}",
            p.selector,
            p.element_index,
            p.x,
            p.y,
            p.session_id
        );
        let s = require_session!(self, &p.session_id);
        let result = async {
            let t = resolve_target(&s, p.selector.as_deref(), p.element_index, p.x, p.y)?;
            self.act_and_maybe_capture(
                &s,
                p.screenshot_after_ms,
                format!("Tapped {} at ({},{})", t.description, t.x, t.y),
                async { self.device.tap(&s.serial(), t.x, t.y).await },
                true,
            )
            .await
        }
        .await;
        result.unwrap_or_else(|e| err_text(format!("Error tapping: {e}")))
    }

    pub async fn long_press(&self, p: LongPressParams) -> CallToolResult {
        tracing::info!(
            "MCP tool invoked: android_long_press selector={:?} index={:?} xy=({:?},{:?}) sessionId={}",
            p.selector,
            p.element_index,
            p.x,
            p.y,
            p.session_id
        );
        let s = require_session!(self, &p.session_id);
        let duration = p.duration_ms.filter(|d| *d > 0).unwrap_or(800);
        let result = async {
            let t = resolve_target(&s, p.selector.as_deref(), p.element_index, p.x, p.y)?;
            self.act_and_maybe_capture(
                &s,
                p.screenshot_after_ms,
                format!(
                    "Long-pressed {} at ({},{}) for {duration}ms",
                    t.description, t.x, t.y
                ),
                async {
                    self.device
                        .swipe(&s.serial(), (t.x, t.y, t.x, t.y), duration)
                        .await
                },
                true,
            )
            .await
        }
        .await;
        result.unwrap_or_else(|e| err_text(format!("Error long-pressing: {e}")))
    }

    pub async fn swipe(&self, p: SwipeParams) -> CallToolResult {
        tracing::info!(
            "MCP tool invoked: android_swipe ({},{})->({},{}) sessionId={}",
            p.x1,
            p.y1,
            p.x2,
            p.y2,
            p.session_id
        );
        let s = require_session!(self, &p.session_id);
        let duration = p.duration_ms.filter(|d| *d > 0).unwrap_or(300);
        let coords = (p.x1, p.y1, p.x2, p.y2);
        self.act_and_maybe_capture(
            &s,
            p.screenshot_after_ms,
            format!(
                "Swiped ({},{}) -> ({},{}) over {duration}ms",
                p.x1, p.y1, p.x2, p.y2
            ),
            async { self.device.swipe(&s.serial(), coords, duration).await },
            true,
        )
        .await
        .unwrap_or_else(|e| err_text(format!("Error swiping: {e}")))
    }

    pub async fn press_key(&self, p: PressKeyParams) -> CallToolResult {
        tracing::info!(
            "MCP tool invoked: android_press_key key={} sessionId={}",
            p.key,
            p.session_id
        );
        let s = require_session!(self, &p.session_id);
        let keycode = device::normalize_keycode(&p.key);
        // Power/sleep/wake keys control screen power themselves — skip the wake guard.
        let power_key = device::is_power_key(&keycode);
        self.act_and_maybe_capture(
            &s,
            p.screenshot_after_ms,
            format!("Pressed {keycode}"),
            async { self.device.press_key(&s.serial(), &keycode).await },
            !power_key,
        )
        .await
        .unwrap_or_else(|e| err_text(format!("Error pressing key: {e}")))
    }

    pub async fn input_text(&self, p: InputTextParams) -> CallToolResult {
        tracing::info!(
            "MCP tool invoked: android_input_text length={} sessionId={}",
            p.text.encode_utf16().count(),
            p.session_id
        );
        let s = require_session!(self, &p.session_id);
        if p.text.is_empty() {
            return err_text("Error: text is required");
        }
        let text = p.text.as_str();
        let len = text.encode_utf16().count();
        let safe_ascii = device::is_safe_ascii_text(text);
        let success_text = if safe_ascii {
            format!("Typed {len} chars")
        } else {
            format!(
                "Typed {len} chars via ADBKeyboard IME (original keyboard is restored on android_close_session)"
            )
        };
        let action = async {
            let serial = s.serial();
            if safe_ascii {
                return self.device.input_ascii_text(&serial, text).await;
            }
            if !s.with_active_state(|st| st.adb_keyboard_active) {
                self.device.ensure_adb_keyboard_active(&serial).await?;
                s.with_active_state(|st| st.adb_keyboard_active = true);
            }
            self.device.input_unicode_via_keyboard(&serial, text).await
        };
        self.act_and_maybe_capture(&s, p.screenshot_after_ms, success_text, action, true)
            .await
            .unwrap_or_else(|e| err_text(format!("Error typing text: {e}")))
    }

    // ===== screenshots / diagnostics =====

    pub async fn screenshot(&self, p: ScreenshotParams) -> CallToolResult {
        tracing::info!(
            "MCP tool invoked: android_screenshot sessionId={} format={:?}",
            p.session_id,
            p.format
        );
        let s = require_session!(self, &p.session_id);
        let jpeg = p
            .format
            .as_deref()
            .is_none_or(|f| !java_trim(f).eq_ignore_ascii_case("png"));
        let filename = format!("{:04}.{}", s.next_seq(), if jpeg { "jpg" } else { "png" });
        let result = s
            .submit(async {
                self.device
                    .screenshot(&s.serial(), s.host_screenshot_dir(), &filename, jpeg)
                    .await
            })
            .await;
        match result {
            Ok(shot) => text_and_image(
                format!(
                    "{} ({})",
                    s.return_path_for(&filename),
                    shot.scale_summary()
                ),
                &shot,
            ),
            Err(e) => {
                tracing::warn!("Android screenshot failed: {e}");
                err_text(format!("Error taking screenshot: {e}"))
            }
        }
    }

    pub async fn logcat(&self, p: LogcatParams) -> CallToolResult {
        tracing::info!(
            "MCP tool invoked: android_logcat sessionId={}",
            p.session_id
        );
        let s = require_session!(self, &p.session_id);
        let spec = p
            .filter_spec
            .as_deref()
            .filter(|f| !f.trim().is_empty())
            .unwrap_or("*:E");
        let tail = p
            .tail_lines
            .filter(|t| *t > 0)
            .map_or(200, |t| t.min(1_000));
        let clear = p.clear_first == Some(true);
        let result = s
            .submit(async { self.device.logcat(&s.serial(), spec, tail, clear).await })
            .await;
        ok_text(result.unwrap_or_else(|e| format!("Error reading logcat: {e}")))
    }

    // ===== network =====

    pub async fn set_offline(&self, p: SetOfflineParams) -> CallToolResult {
        tracing::info!(
            "MCP tool invoked: android_set_offline offline={} sessionId={}",
            p.offline,
            p.session_id
        );
        let s = require_session!(self, &p.session_id);
        let off = p.offline;
        let result = s
            .submit(async { self.device.set_offline(&s.serial(), off).await })
            .await;
        ok_text(match result {
            Ok(()) if off => "Device is now offline (wifi + mobile data disabled)".to_owned(),
            Ok(()) => "Device connectivity restored (wifi + mobile data enabled)".to_owned(),
            Err(e) => format!("Error toggling offline mode: {e}"),
        })
    }

    pub async fn set_network_conditions(&self, p: NetworkConditionsParams) -> CallToolResult {
        tracing::info!(
            "MCP tool invoked: android_set_network_conditions speed={:?} delay={:?} sessionId={}",
            p.speed,
            p.delay,
            p.session_id
        );
        let s = require_session!(self, &p.session_id);
        let speed = p.speed.as_deref();
        let delay = p.delay.as_deref();
        let result = s
            .submit(async {
                self.device
                    .set_network_conditions(&s.serial(), speed, delay)
                    .await
            })
            .await;
        ok_text(match result {
            Ok(()) => {
                let mut msg = String::from("Network conditions applied");
                if let Some(v) = speed.filter(|v| !v.trim().is_empty()) {
                    msg.push_str(" speed=");
                    msg.push_str(java_trim(v));
                }
                if let Some(v) = delay.filter(|v| !v.trim().is_empty()) {
                    msg.push_str(" delay=");
                    msg.push_str(java_trim(v));
                }
                msg.push_str(". Reset with speed='full' delay='none'.");
                msg
            }
            Err(e) => format!("Error setting network conditions: {e}"),
        })
    }

    pub async fn set_http_proxy(&self, p: HttpProxyParams) -> CallToolResult {
        tracing::info!(
            "MCP tool invoked: android_set_http_proxy hostPort={:?} sessionId={}",
            p.host_port,
            p.session_id
        );
        let s = require_session!(self, &p.session_id);
        let host_port = p.host_port.as_deref();
        let clearing = host_port.is_none_or(|h| h.trim().is_empty() || java_trim(h) == ":0");
        let result = s
            .submit(async { self.device.set_http_proxy(&s.serial(), host_port).await })
            .await;
        ok_text(match result {
            Ok(()) if clearing => "Global HTTP proxy cleared".to_owned(),
            Ok(()) => format!(
                "Global HTTP proxy set to {} (make sure the proxy is running and reachable from the device)",
                java_trim(host_port.unwrap_or(""))
            ),
            Err(e) => format!("Error setting HTTP proxy: {e}"),
        })
    }

    // ===== file push =====

    pub async fn push_file(&self, p: PushFileParams) -> CallToolResult {
        tracing::info!(
            "MCP tool invoked: android_push_file local={} device={} sessionId={}",
            p.local_path,
            p.device_path,
            p.session_id
        );
        let s = require_session!(self, &p.session_id);
        if p.local_path.trim().is_empty() {
            return ok_text("Error: localPath is required");
        }
        let local = abs_normalize(Path::new(java_trim(&p.local_path)));
        if !local.is_file() {
            return ok_text(format!("Error: local file not found: {}", local.display()));
        }
        if p.device_path.trim().is_empty() || !java_trim(&p.device_path).starts_with('/') {
            return ok_text(
                "Error: devicePath must be an absolute device path (e.g. /sdcard/Download/test.png)",
            );
        }
        let target = java_trim(&p.device_path);
        let scan = p.scan_media.unwrap_or(true);
        let result = s
            .submit(async {
                self.device
                    .push_file(&s.serial(), &local, target, scan)
                    .await
            })
            .await;
        ok_text(match result {
            Ok(output) => format!(
                "Pushed {} -> {target}{}{}",
                file_name(&local),
                if scan {
                    " (media scan broadcast sent)"
                } else {
                    ""
                },
                if output.trim().is_empty() {
                    String::new()
                } else {
                    format!(": {output}")
                }
            ),
            Err(e) => format!("Error pushing file: {e}"),
        })
    }

    // ===== timeline / recording / pixels =====

    pub async fn capture_timeline(&self, p: CaptureTimelineParams) -> CallToolResult {
        let interval = p.interval_ms.filter(|v| *v > 0).unwrap_or(1000);
        let captures = p.count.filter(|v| *v > 0).unwrap_or(5);
        if (i64::from(interval) + CAPTURE_OVERHEAD_MS) * i64::from(captures)
            > MAX_OPERATION_BUDGET_MS
        {
            return err_text(format!(
                "Error: total timeline duration (including ~{CAPTURE_OVERHEAD_MS}ms screencap overhead per frame) \
                 would exceed 110s (intervalMs={interval} * count={captures}). Reduce either parameter."
            ));
        }
        tracing::info!(
            "MCP tool invoked: android_capture_timeline interval={interval} count={captures} sessionId={}",
            p.session_id
        );
        let s = require_session!(self, &p.session_id);
        let seq = s.next_seq();
        let result = s
            .submit(async {
                let mut collected = Vec::with_capacity(captures.unsigned_abs() as usize);
                for i in 0..captures {
                    if i > 0 {
                        tokio::time::sleep(Duration::from_millis(interval.unsigned_abs().into()))
                            .await;
                    }
                    let shot = self
                        .device
                        .screenshot(
                            &s.serial(),
                            s.host_screenshot_dir(),
                            &format!("{seq:04}_f{i}.jpg"),
                            true,
                        )
                        .await?;
                    collected.push(shot);
                }
                Ok(collected)
            })
            .await;
        match result {
            Ok(shots) => {
                let summary = shots
                    .first()
                    .map(ScreenshotResult::scale_summary)
                    .unwrap_or_default();
                let mut out = format!(
                    "{} frames captured (f0=immediate, then ≈+{interval}ms each; {summary}):",
                    shots.len()
                );
                for shot in &shots {
                    out.push_str(LINE_SEP);
                    out.push_str(&s.return_path_for(&shot.filename));
                }
                let images = shots
                    .iter()
                    .map(|shot| (shot.base64(), shot.mime_type.to_owned()))
                    .collect();
                text_and_images(out, images)
            }
            Err(e) => {
                tracing::warn!("Android capture timeline failed: {e}");
                err_text(format!("Error capturing timeline: {e}"))
            }
        }
    }

    pub async fn record_screen(&self, p: RecordScreenParams) -> CallToolResult {
        tracing::info!(
            "MCP tool invoked: android_record_screen durationMs={} sessionId={}",
            p.duration_ms,
            p.session_id
        );
        let s = require_session!(self, &p.session_id);
        if p.duration_ms <= 0 {
            return ok_text(format!(
                "Error: durationMs is required (1..{MAX_RECORD_DURATION_MS})"
            ));
        }
        let dur = p.duration_ms.min(MAX_RECORD_DURATION_MS);
        let filename = format!("{:04}_rec.mp4", s.next_seq());
        // Must exceed the sum of record (dur + 30s slack) + pull (install timeout) + rm (command timeout).
        let await_ms = u64::from(dur.unsigned_abs())
            + 30_000
            + duration_ms(self.config.install_timeout)
            + duration_ms(self.config.command_timeout)
            + 10_000;
        let result = s
            .submit_with_timeout(
                async {
                    self.device
                        .record_screen(
                            &s.serial(),
                            dur,
                            p.bit_rate_mbps,
                            s.host_screenshot_dir(),
                            &filename,
                        )
                        .await
                },
                await_ms,
            )
            .await;
        ok_text(match result {
            Ok(saved) => format!(
                "Recorded {dur}ms of screen video: {} (MP4 — not analyzable inline; use android_capture_timeline \
                 for model-visible frames)",
                absolute_string(&saved)
            ),
            Err(e) => {
                tracing::warn!("Android screen recording failed: {e}");
                format!("Error recording screen: {e}")
            }
        })
    }

    pub async fn sample_pixels(&self, p: SamplePixelsParams) -> CallToolResult {
        tracing::info!(
            "MCP tool invoked: android_sample_pixels points={:?} rect={:?} sessionId={}",
            p.points,
            p.rect,
            p.session_id
        );
        let s = require_session!(self, &p.session_id);
        let parsed = pixel_sampler::parse_points(p.points.as_deref())
            .and_then(|pts| pixel_sampler::parse_rect(p.rect.as_deref()).map(|rect| (pts, rect)));
        let (points, rect) = match parsed {
            Ok(v) => v,
            Err(e) => return ok_text(format!("Error: {e}")),
        };
        if points.is_empty() && rect.is_none() {
            return ok_text("Error: provide points ('x,y;x,y') and/or rect ('x,y,w,h')");
        }
        let result = s
            .submit(async { self.device.screenshot_raw(&s.serial()).await })
            .await
            .and_then(|image| pixel_sampler::sample_to_json(&image, &points, rect));
        ok_text(match result {
            Ok(json) => json,
            Err(e) => {
                tracing::warn!("Android sample pixels failed: {e}");
                format!("Error sampling pixels: {e}")
            }
        })
    }

    // ===== session =====

    pub async fn close_session(&self, p: SessionOnlyParams) -> String {
        tracing::info!(
            "MCP tool invoked: android_close_session sessionId={}",
            p.session_id
        );
        let Some((_, s)) = self.sessions.remove(&p.session_id) else {
            return "No session to close".to_owned();
        };
        self.restore_all_imes_quietly(&s).await;
        s.close();
        "Android session closed (device binding released; sessionId remains valid for other tools)"
            .to_owned()
    }

    // ===== helpers =====

    /// Restores the IME of every device switched to ADBKeyboard. Call before `close()`.
    async fn restore_all_imes_quietly(&self, s: &AndroidSession) {
        for (serial, original_ime) in s.keyboard_active_devices() {
            let result = s
                .submit(async {
                    self.device
                        .restore_ime(&serial, original_ime.as_deref())
                        .await
                })
                .await;
            if let Err(e) = result {
                tracing::warn!("Failed to restore IME for device {serial}: {e}");
            }
            // Reset even on failure — safer than retrying with a wrong originalIme later.
            s.with_state(&serial, |st| st.adb_keyboard_active = false);
        }
    }

    /// Runs the action on the session queue; with `screenshot_after_ms` the wait and the
    /// capture run in the same task so no other tool call can interleave.
    async fn act_and_maybe_capture<F>(
        &self,
        s: &AndroidSession,
        screenshot_after_ms: Option<i32>,
        success_text: String,
        action: F,
        wake_screen_first: bool,
    ) -> Result<CallToolResult, String>
    where
        F: Future<Output = Result<(), String>>,
    {
        if let Some(error) = screenshot_budget_error(screenshot_after_ms) {
            return Ok(err_text(error));
        }
        let guarded = async {
            if wake_screen_first {
                self.device.ensure_screen_on(&s.serial()).await?;
            }
            action.await
        };
        let Some(after) = screenshot_after_ms else {
            s.submit(guarded).await?;
            return Ok(ok_text(success_text));
        };
        let wait_ms = after.max(0);
        let filename = format!("{:04}.jpg", s.next_seq());
        let shot = s
            .submit(async {
                guarded.await?;
                tokio::time::sleep(Duration::from_millis(wait_ms.unsigned_abs().into())).await;
                self.device
                    .screenshot(&s.serial(), s.host_screenshot_dir(), &filename, true)
                    .await
            })
            .await?;
        Ok(text_and_image(
            format!(
                "{success_text} (screenshot after {wait_ms}ms, {}): {}",
                shot.scale_summary(),
                s.return_path_for(&filename)
            ),
            &shot,
        ))
    }

    #[cfg(test)]
    fn session(&self, sid: &str) -> Option<Arc<AndroidSession>> {
        self.sessions.get(sid).map(|s| s.value().clone())
    }

    #[cfg(test)]
    fn session_count(&self) -> usize {
        self.sessions.len()
    }
}

/// Error text when `screenshot_after_ms` exceeds the operation budget.
fn screenshot_budget_error(screenshot_after_ms: Option<i32>) -> Option<String> {
    match screenshot_after_ms {
        Some(ms) if i64::from(ms) > MAX_OPERATION_BUDGET_MS => Some(format!(
            "Error: screenshotAfterMs ({ms}ms) would exceed the 110s operation budget. Reduce it."
        )),
        _ => None,
    }
}

/// selector -> elementIndex -> x,y priority.
fn resolve_target(
    s: &AndroidSession,
    selector: Option<&str>,
    element_index: Option<i32>,
    x: Option<i32>,
    y: Option<i32>,
) -> Result<TapTarget, String> {
    if let Some(selector) = selector.filter(|v| !v.trim().is_empty()) {
        let dump = s
            .with_active_state(|st| st.last_dump.clone())
            .ok_or("no UI dump cached. Call android_dump_ui first to use selector targeting.")?;
        let matches: Vec<&UiNode> = dump
            .iter()
            .filter(|n| matches_selector(n, selector))
            .collect();
        let Some(chosen) = matches.first() else {
            return Err(format!(
                "no element matches selector '{selector}'. Re-run android_dump_ui — the screen may have changed."
            ));
        };
        let note = if matches.len() > 1 {
            format!(" ({} matched, used [{}])", matches.len(), chosen.index)
        } else {
            String::new()
        };
        return Ok(TapTarget {
            x: chosen.center_x,
            y: chosen.center_y,
            description: format!("element [{}] '{selector}'{note}", chosen.index),
        });
    }
    if let Some(index) = element_index {
        let dump = s.with_active_state(|st| st.last_dump.clone()).ok_or(
            "no UI dump cached. Call android_dump_ui first to use elementIndex targeting.",
        )?;
        let node = usize::try_from(index)
            .ok()
            .and_then(|i| dump.get(i))
            .ok_or_else(|| {
                format!(
                    "elementIndex {index} out of range (0..{}). Re-run android_dump_ui.",
                    dump.len() as i64 - 1
                )
            })?;
        return Ok(TapTarget {
            x: node.center_x,
            y: node.center_y,
            description: format!("element [{index}]"),
        });
    }
    if let (Some(x), Some(y)) = (x, y) {
        return Ok(TapTarget {
            x,
            y,
            description: "coordinates".to_owned(),
        });
    }
    Err("specify selector, elementIndex, or both x and y".to_owned())
}

fn matches_selector(node: &UiNode, selector: &str) -> bool {
    let needle = selector.to_lowercase();
    node.resource_id.to_lowercase().contains(&needle)
        || node.text.to_lowercase().contains(&needle)
        || node.content_desc.to_lowercase().contains(&needle)
}

fn text_and_image(text: String, shot: &ScreenshotResult) -> CallToolResult {
    text_and_images(text, vec![(shot.base64(), shot.mime_type.to_owned())])
}

fn duration_ms(d: Duration) -> u64 {
    u64::try_from(d.as_millis()).unwrap_or(u64::MAX)
}

/// Java `Path.getFileName()` as a string (`"null"` for a root path).
fn file_name(path: &Path) -> String {
    path.file_name()
        .map_or_else(|| "null".to_owned(), |n| n.to_string_lossy().into_owned())
}

/// Java `Paths.get(p).toAbsolutePath().normalize()` (lexical).
fn abs_normalize(path: &Path) -> PathBuf {
    let abs = std::path::absolute(path).unwrap_or_else(|_| path.to_path_buf());
    let mut out = PathBuf::new();
    for component in abs.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                if !matches!(
                    out.components().next_back(),
                    Some(Component::RootDir | Component::Prefix(_)) | None
                ) {
                    out.pop();
                }
            }
            other => out.push(other.as_os_str()),
        }
    }
    out
}

// ===== MCP tool registrations =====

#[tool_router(router = android_router, vis = "pub(crate)")]
impl NamsicServer {
    #[tool(
        name = "android_list_devices",
        description = "List Android devices and emulators visible to adb (adb devices -l): serial, state (device/offline/unauthorized), and model. 'unauthorized' means the RSA debugging prompt must be approved on the device screen. Call this first, then android_use_device with the chosen serial. No other android_* tool works until a device is selected."
    )]
    async fn android_list_devices(&self, Parameters(p): Parameters<SessionOnlyParams>) -> String {
        self.state.android.list_devices(p).await
    }

    #[tool(
        name = "android_use_device",
        description = "Select the target device/emulator for this session and pin all subsequent android_* tools to it (adb -s <serial>). Omit serial to auto-select when exactly one ready device is connected. Records the device's current keyboard (IME) so it can be restored on android_close_session. Must be called before any interaction tool. Each device's state (UI-dump cache, IME) is tracked separately, so you can switch back and forth between two devices (e.g. two test accounts on two emulators) without losing context."
    )]
    async fn android_use_device(&self, Parameters(p): Parameters<UseDeviceParams>) -> String {
        self.state.android.use_device(p).await
    }

    #[tool(
        name = "android_install_app",
        description = "Install an APK from a host absolute path onto the selected device (adb install). reinstall=true (default) keeps existing app data (-r). Returns adb's install output on success."
    )]
    async fn android_install_app(
        &self,
        Parameters(p): Parameters<InstallAppParams>,
    ) -> CallToolResult {
        self.state.android.install_app(p).await
    }

    #[tool(
        name = "android_launch_app",
        description = "Launch an installed app by package name. Optionally pass an activity ('.MainActivity' or fully-qualified) to open a specific screen; otherwise the launcher activity is started. After launching, call android_dump_ui or android_screenshot to inspect the screen."
    )]
    async fn android_launch_app(
        &self,
        Parameters(p): Parameters<LaunchAppParams>,
    ) -> CallToolResult {
        self.state.android.launch_app(p).await
    }

    #[tool(
        name = "android_stop_app",
        description = "Force-stop an app by package name (am force-stop). Use to reset app state before re-launching."
    )]
    async fn android_stop_app(&self, Parameters(p): Parameters<StopAppParams>) -> CallToolResult {
        self.state.android.stop_app(p).await
    }

    #[tool(
        name = "android_list_packages",
        description = "List installed packages on the device (pm list packages). Use thirdPartyOnly=true to hide system packages, and nameFilter to search by package-name substring."
    )]
    async fn android_list_packages(
        &self,
        Parameters(p): Parameters<ListPackagesParams>,
    ) -> CallToolResult {
        self.state.android.list_packages(p).await
    }

    #[tool(
        name = "android_dump_ui",
        description = "Dump the current screen's UI hierarchy (uiautomator) as a compact element list: each line shows [index], text, resource-id, content-desc, class, clickable, bounds and center coordinates in device pixels. Use the index or an id/text/desc substring with android_tap to interact. The result is cached, so android_tap's selector/elementIndex always targets the most recent dump — re-run this after the screen changes. Retries automatically when the screen is not idle. WebView/Flutter/game surfaces may expose no elements; fall back to android_screenshot plus raw-coordinate taps in that case."
    )]
    async fn android_dump_ui(&self, Parameters(p): Parameters<DumpUiParams>) -> CallToolResult {
        self.state.android.dump_ui(p).await
    }

    #[tool(
        name = "android_tap",
        description = concat!(
            "Tap an element or coordinate on the device screen. ",
            target_param_note!(),
            " Set screenshotAfterMs to verify the result inline in one round trip. A tap may change the screen — \
             re-run android_dump_ui before reusing selector/elementIndex, as cached element coordinates go stale."
        )
    )]
    async fn android_tap(&self, Parameters(p): Parameters<TapParams>) -> CallToolResult {
        self.state.android.tap(p).await
    }

    #[tool(
        name = "android_long_press",
        description = concat!(
            "Long-press an element or coordinate (input swipe with identical start/end points). ",
            target_param_note!(),
            " A long-press may change the screen (context menus etc.) — re-run android_dump_ui before reusing \
             selector/elementIndex."
        )
    )]
    async fn android_long_press(
        &self,
        Parameters(p): Parameters<LongPressParams>,
    ) -> CallToolResult {
        self.state.android.long_press(p).await
    }

    #[tool(
        name = "android_swipe",
        description = "Swipe from (x1,y1) to (x2,y2) in device pixels — use for scrolling (swipe up to scroll down), page transitions, and drags. Larger durationMs = slower drag; short duration = fling. After scrolling, re-run android_dump_ui — cached element coordinates are stale."
    )]
    async fn android_swipe(&self, Parameters(p): Parameters<SwipeParams>) -> CallToolResult {
        self.state.android.swipe(p).await
    }

    #[tool(
        name = "android_press_key",
        description = "Press a hardware/navigation key (input keyevent). Accepts a key name (HOME, BACK, ENTER, DEL, TAB, APP_SWITCH, POWER, VOLUME_UP, ...) or a numeric keycode. Note: if the on-screen keyboard is open, the first BACK only closes the keyboard. Keys like BACK/HOME change the screen — re-run android_dump_ui before reusing selector/elementIndex."
    )]
    async fn android_press_key(&self, Parameters(p): Parameters<PressKeyParams>) -> CallToolResult {
        self.state.android.press_key(p).await
    }

    #[tool(
        name = "android_input_text",
        description = "Type text into the currently focused input field. Tap the target field with android_tap first to focus it. Plain ASCII goes via 'adb shell input text'; non-ASCII (e.g. Korean) and special characters are sent through the ADBKeyboard IME (auto-installed from app.android.adb-keyboard-apk when configured). The device's original keyboard is restored on android_close_session. The IME broadcast cannot confirm the text actually landed — verify with screenshotAfterMs or android_dump_ui."
    )]
    async fn android_input_text(
        &self,
        Parameters(p): Parameters<InputTextParams>,
    ) -> CallToolResult {
        self.state.android.input_text(p).await
    }

    #[tool(
        name = "android_screenshot",
        description = "Capture the device screen (adb exec-out screencap) and save it under the project screenshot dir. Returns the image inline (no separate file read needed) plus the saved file path. Defaults to JPEG q80 downscaled to fit the vision-input budget; the response states the device-to-image scale — divide image coordinates by the scale for tap coordinates. Pass format=png for the lossless full-resolution original. To verify an action result, prefer the screenshotAfterMs parameter on the action tool itself."
    )]
    async fn android_screenshot(
        &self,
        Parameters(p): Parameters<ScreenshotParams>,
    ) -> CallToolResult {
        self.state.android.screenshot(p).await
    }

    #[tool(
        name = "android_logcat",
        description = "Dump recent device logs (logcat -d -v threadtime). Default filter '*:E' shows errors only — use e.g. 'MyTag:D *:S' to follow one tag, or '*:W' for warnings and above. Set clearFirst=true to clear the buffer (then reproduce the issue and call again). Use after a crash or unexpected behavior to diagnose."
    )]
    async fn android_logcat(&self, Parameters(p): Parameters<LogcatParams>) -> CallToolResult {
        self.state.android.logcat(p).await
    }

    #[tool(
        name = "android_set_offline",
        description = "Toggle device connectivity by enabling/disabling BOTH wifi and mobile data (svc wifi/data). Works on USB-connected physical devices and emulators. NOT available over a wireless adb connection (serial like '192.168.0.10:5555' or an '_adb-tls-connect' mDNS name) — disabling wifi would sever the adb channel and brick the session; the call is rejected. Use to test offline guards, heartbeat loss, and reconnect flows. Pass offline=false to restore connectivity."
    )]
    async fn android_set_offline(
        &self,
        Parameters(p): Parameters<SetOfflineParams>,
    ) -> CallToolResult {
        self.state.android.set_offline(p).await
    }

    #[tool(
        name = "android_set_network_conditions",
        description = "Emulate network speed/latency via the emulator console (adb emu network speed/delay). EMULATOR ONLY — fails on physical devices (serial must start with 'emulator-'). speed: 'full' (reset), 'gsm', 'edge', '3g', 'lte', or 'up:down' in kbps (e.g. '100:100'). delay: 'none' (reset), 'gprs', 'edge', or 'min:max' in ms (e.g. '500:1000'). Use to observe loading spinners and transient states that vanish at full speed. Reset with speed='full' delay='none'."
    )]
    async fn android_set_network_conditions(
        &self,
        Parameters(p): Parameters<NetworkConditionsParams>,
    ) -> CallToolResult {
        self.state.android.set_network_conditions(p).await
    }

    #[tool(
        name = "android_set_http_proxy",
        description = "Set or clear the device-wide HTTP proxy (settings put global http_proxy). Point it at an external intercepting proxy (e.g. mitmproxy on the host) to inspect or mock API responses — this tool only configures the device; running the proxy is the caller's responsibility. Omit hostPort (or pass ':0') to clear. From an emulator the host machine is reachable as 10.0.2.2. CAVEATS: apps using their own HTTP stack can ignore the global proxy, and HTTPS interception additionally requires the proxy CA to be trusted on the device (user-added CAs are distrusted by default since API 24). Request mocking on Android always needs an external proxy — there is no pure-ADB equivalent."
    )]
    async fn android_set_http_proxy(
        &self,
        Parameters(p): Parameters<HttpProxyParams>,
    ) -> CallToolResult {
        self.state.android.set_http_proxy(p).await
    }

    #[tool(
        name = "android_push_file",
        description = "Push a host file onto the device (adb push) — e.g. a test image/audio file for upload and file-picker scenarios. scanMedia=true (default) broadcasts MEDIA_SCANNER_SCAN_FILE afterwards so gallery/picker apps see the file immediately. For upload scenarios the file must be on-device first, then picked through the app's own UI."
    )]
    async fn android_push_file(&self, Parameters(p): Parameters<PushFileParams>) -> CallToolResult {
        self.state.android.push_file(p).await
    }

    #[tool(
        name = "android_capture_timeline",
        description = "Capture a sequence of screenshots WITHOUT any input: f0 immediately, then `count-1` more frames ≈`intervalMs` apart, all returned inline plus saved file paths. Use to observe ongoing animations (spinners, toasts fading, transitions) that a single screenshot misses. Each screencap itself takes several hundred ms on-device, so actual frame spacing drifts beyond nominal — treat intervalMs as a lower bound."
    )]
    async fn android_capture_timeline(
        &self,
        Parameters(p): Parameters<CaptureTimelineParams>,
    ) -> CallToolResult {
        self.state.android.capture_timeline(p).await
    }

    #[tool(
        name = "android_record_screen",
        description = "Record the device screen to an MP4 file (screenrecord), pull it to the host, and return the saved file path. durationMs max 180000 (screenrecord's own limit). NOTE: the MP4 cannot be analyzed inline by the model — prefer android_capture_timeline for frames the model can see; use this when a human will review the video or as archival evidence. Recording occupies the session for the whole duration."
    )]
    async fn android_record_screen(
        &self,
        Parameters(p): Parameters<RecordScreenParams>,
    ) -> CallToolResult {
        self.state.android.record_screen(p).await
    }

    #[tool(
        name = "android_sample_pixels",
        description = "Sample exact pixel colors from a full-resolution screenshot for quantitative color checks (highlight colors, state-dependent tints). Coordinates are DEVICE pixels — the same space as android_tap x,y and android_dump_ui bounds (NOT the downscaled android_screenshot image). Returns JSON: per-point #RRGGBBAA for points='x1,y1;x2,y2;...' and/or the average #RRGGBB over rect='x,y,w,h'."
    )]
    async fn android_sample_pixels(
        &self,
        Parameters(p): Parameters<SamplePixelsParams>,
    ) -> CallToolResult {
        self.state.android.sample_pixels(p).await
    }

    #[tool(
        name = "android_close_session",
        description = "Close the Android session: restores the device's original keyboard (if ADBKeyboard was activated) and releases the device binding. The sessionId itself stays valid; call android_use_device again to resume Android testing."
    )]
    async fn android_close_session(&self, Parameters(p): Parameters<SessionOnlyParams>) -> String {
        self.state.android.close_session(p).await
    }
}
