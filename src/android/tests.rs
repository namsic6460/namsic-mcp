//! Tool-layer tests (port of AndroidMcpToolsTest) against a scripted adb fake.

use std::sync::Arc;

use super::adb::fake::{FakeAdb, ok};
use super::session::DeviceState;
use super::*;
use crate::result::{first_text, is_error};
use crate::session::Project;

const UNKNOWN_SID: &str = "00000000-0000-0000-0000-000000000000";
const LATIN_IME: &str = "com.android.inputmethod.latin/.LatinIME";
const EMU: &str = "emulator-5554";

struct Fixture {
    _dir: tempfile::TempDir,
    registry: Arc<ProjectSessionRegistry>,
    adb: Arc<FakeAdb>,
    tools: AndroidTools,
}

fn fixture() -> Fixture {
    let dir = tempfile::tempdir().unwrap();
    let registry = Arc::new(ProjectSessionRegistry::new(dir.path()));
    let adb = Arc::new(FakeAdb::new());
    let tools = AndroidTools::with_runner(registry.clone(), AndroidConfig::default(), adb.clone());
    Fixture {
        _dir: dir,
        registry,
        adb,
        tools,
    }
}

fn devices_output(lines: &[&str]) -> String {
    format!("List of devices attached\n{}\n", lines.join("\n"))
}

fn node(index: usize, text: &str, id: &str, bounds: [i32; 4]) -> UiNode {
    UiNode {
        index,
        text: text.to_owned(),
        resource_id: id.to_owned(),
        content_desc: String::new(),
        class_name: "android.widget.Button".to_owned(),
        clickable: true,
        left: bounds[0],
        top: bounds[1],
        right: bounds[2],
        bottom: bounds[3],
        center_x: (bounds[0] + bounds[2]) / 2,
        center_y: (bounds[1] + bounds[3]) / 2,
    }
}

fn sid(project: &Project) -> String {
    project.session_id.clone()
}

impl Fixture {
    /// Session bound to emulator-5554 via android_use_device.
    async fn bind_device(&self) -> Arc<Project> {
        let project = self.registry.init(Some("android-test")).unwrap();
        self.adb.on(
            None,
            "devices -l",
            vec![ok(&devices_output(&[
                "emulator-5554\tdevice model:sdk_gphone64",
            ]))],
        );
        self.adb.on(
            Some(EMU),
            "shell settings get secure default_input_method",
            vec![ok(LATIN_IME)],
        );
        self.adb.on(
            Some(EMU),
            "shell dumpsys power",
            vec![ok("Display Power: state=ON")],
        );
        let result = self
            .tools
            .use_device(UseDeviceParams {
                session_id: sid(&project),
                serial: None,
            })
            .await;
        assert!(
            result.starts_with("Selected device emulator-5554"),
            "{result}"
        );
        project
    }

    fn state(&self, project: &Project) -> DeviceState {
        self.tools
            .session(&project.session_id)
            .unwrap()
            .with_active_state(|st| st.clone())
    }

    fn set_dump(&self, project: &Project, nodes: Vec<UiNode>) -> Arc<Vec<UiNode>> {
        let dump = Arc::new(nodes);
        let d = dump.clone();
        self.tools
            .session(&project.session_id)
            .unwrap()
            .with_active_state(|st| st.last_dump = Some(d));
        dump
    }
}

// ===== session gate =====

#[tokio::test]
async fn use_device_returns_error_without_init() {
    let f = fixture();
    let result = f
        .tools
        .use_device(UseDeviceParams {
            session_id: UNKNOWN_SID.into(),
            serial: None,
        })
        .await;
    assert!(result.starts_with("Error: Unknown sessionId"), "{result}");
}

#[tokio::test]
async fn list_devices_returns_error_with_blank_session_id() {
    let f = fixture();
    let result = f.tools.list_devices(SessionOnlyParams::default()).await;
    assert!(result.contains("sessionId is required"), "{result}");
    assert!(result.starts_with("Error listing devices: "), "{result}");
}

#[tokio::test]
async fn list_devices_formats_devices() {
    let f = fixture();
    let project = f.registry.init(Some("p")).unwrap();
    f.adb.on(
        None,
        "devices -l",
        vec![
            ok("List of devices attached"),
            ok(&devices_output(&[
                "emulator-5554\tdevice model:M",
                "X\toffline",
            ])),
        ],
    );
    let params = || SessionOnlyParams {
        session_id: sid(&project),
    };
    assert_eq!(
        f.tools.list_devices(params()).await,
        "No devices connected. Start an emulator (e.g. from Android Studio) or connect a device with USB \
         debugging enabled, then call this again."
    );
    assert_eq!(
        f.tools.list_devices(params()).await,
        "Connected devices:\n- emulator-5554 (state=device, model=M)\n- X (state=offline)\nSelect one with \
         android_use_device."
    );
}

#[tokio::test]
async fn tap_fails_without_init() {
    let f = fixture();
    let result = f
        .tools
        .tap(TapParams {
            session_id: UNKNOWN_SID.into(),
            x: Some(10),
            y: Some(20),
            ..Default::default()
        })
        .await;
    assert!(is_error(&result));
    assert!(
        first_text(&result).contains("project_init"),
        "{}",
        first_text(&result)
    );
}

#[tokio::test]
async fn screenshot_fails_without_use_device() {
    let f = fixture();
    let project = f.registry.init(Some("no-device")).unwrap();
    let result = f
        .tools
        .screenshot(ScreenshotParams {
            session_id: sid(&project),
            format: None,
        })
        .await;
    assert!(is_error(&result));
    assert_eq!(
        first_text(&result),
        format!(
            "No Android session for sessionId={}. Call android_use_device first.",
            project.session_id
        )
    );
}

// ===== tap targeting guards =====

#[tokio::test]
async fn tap_rejects_when_no_target_specified() {
    let f = fixture();
    let project = f.bind_device().await;
    let result = f
        .tools
        .tap(TapParams {
            session_id: sid(&project),
            ..Default::default()
        })
        .await;
    assert!(is_error(&result));
    assert_eq!(
        first_text(&result),
        "Error tapping: specify selector, elementIndex, or both x and y"
    );
}

#[tokio::test]
async fn tap_selector_requires_cached_dump() {
    let f = fixture();
    let project = f.bind_device().await;
    let result = f
        .tools
        .tap(TapParams {
            session_id: sid(&project),
            selector: Some("login".into()),
            ..Default::default()
        })
        .await;
    assert!(is_error(&result));
    assert!(first_text(&result).contains("Call android_dump_ui first"));
}

#[tokio::test]
async fn tap_element_index_rejects_out_of_range() {
    let f = fixture();
    let project = f.bind_device().await;
    f.set_dump(
        &project,
        vec![node(
            0,
            "로그인",
            "com.example:id/btn_login",
            [0, 0, 100, 100],
        )],
    );
    let result = f
        .tools
        .tap(TapParams {
            session_id: sid(&project),
            element_index: Some(5),
            ..Default::default()
        })
        .await;
    assert!(is_error(&result));
    assert_eq!(
        first_text(&result),
        "Error tapping: elementIndex 5 out of range (0..0). Re-run android_dump_ui."
    );
}

#[tokio::test]
async fn tap_selector_matches_cached_dump_and_taps_center() {
    let f = fixture();
    let project = f.bind_device().await;
    f.set_dump(
        &project,
        vec![node(
            0,
            "로그인",
            "com.example:id/btn_login",
            [120, 1400, 960, 1520],
        )],
    );
    let result = f
        .tools
        .tap(TapParams {
            session_id: sid(&project),
            selector: Some("BTN_login".into()),
            ..Default::default()
        })
        .await;
    assert!(!is_error(&result));
    assert_eq!(
        first_text(&result),
        "Tapped element [0] 'BTN_login' at (540,1460)"
    );
    assert_eq!(f.adb.count(Some(EMU), "shell input tap 540 1460"), 1);
    // The screen-on guard ran before the tap.
    assert_eq!(f.adb.count(Some(EMU), "shell dumpsys power"), 1);
}

#[tokio::test]
async fn tap_reports_multiple_selector_matches() {
    let f = fixture();
    let project = f.bind_device().await;
    f.set_dump(
        &project,
        vec![
            node(0, "OK", "a:id/ok1", [0, 0, 10, 10]),
            node(1, "OK", "a:id/ok2", [0, 20, 10, 30]),
        ],
    );
    let result = f
        .tools
        .tap(TapParams {
            session_id: sid(&project),
            selector: Some("ok".into()),
            ..Default::default()
        })
        .await;
    assert_eq!(
        first_text(&result),
        "Tapped element [0] 'ok' (2 matched, used [0]) at (5,5)"
    );
}

#[tokio::test]
async fn tap_rejects_screenshot_after_ms_over_budget() {
    let f = fixture();
    let project = f.bind_device().await;
    let result = f
        .tools
        .tap(TapParams {
            session_id: sid(&project),
            x: Some(10),
            y: Some(20),
            screenshot_after_ms: Some(110_001),
            ..Default::default()
        })
        .await;
    assert!(is_error(&result));
    assert_eq!(
        first_text(&result),
        "Error: screenshotAfterMs (110001ms) would exceed the 110s operation budget. Reduce it."
    );
}

#[tokio::test]
async fn tap_device_failure_is_error_result() {
    let f = fixture();
    let project = f.bind_device().await;
    f.adb
        .on(Some(EMU), "shell input tap", vec![ok("Error: boom")]);
    let result = f
        .tools
        .tap(TapParams {
            session_id: sid(&project),
            x: Some(1),
            y: Some(2),
            ..Default::default()
        })
        .await;
    assert!(is_error(&result));
    assert_eq!(
        first_text(&result),
        "Error tapping: input tap failed: Error: boom"
    );
}

// ===== press key =====

#[tokio::test]
async fn press_key_normalizes_and_skips_wake_guard_for_power_keys() {
    let f = fixture();
    let project = f.bind_device().await;
    let result = f
        .tools
        .press_key(PressKeyParams {
            session_id: sid(&project),
            key: "power".into(),
            screenshot_after_ms: None,
        })
        .await;
    assert_eq!(first_text(&result), "Pressed KEYCODE_POWER");
    assert_eq!(f.adb.count(Some(EMU), "shell dumpsys power"), 0);

    f.tools
        .press_key(PressKeyParams {
            session_id: sid(&project),
            key: "back".into(),
            screenshot_after_ms: None,
        })
        .await;
    assert_eq!(f.adb.count(Some(EMU), "shell dumpsys power"), 1);
    assert_eq!(
        f.adb.count(Some(EMU), "shell input keyevent KEYCODE_BACK"),
        1
    );
}

// ===== text input =====

#[tokio::test]
async fn input_text_rejects_non_ascii_without_adb_keyboard() {
    let f = fixture();
    let project = f.bind_device().await;
    let result = f
        .tools
        .input_text(InputTextParams {
            session_id: sid(&project),
            text: "한글테스트".into(),
            screenshot_after_ms: None,
        })
        .await;
    assert!(is_error(&result));
    let text = first_text(&result);
    assert!(text.starts_with("Error typing text:"), "{text}");
    assert!(text.contains("ADBKeyboard"), "{text}");
    assert!(!f.state(&project).adb_keyboard_active);
}

#[tokio::test]
async fn input_text_rejects_empty_text() {
    let f = fixture();
    let project = f.bind_device().await;
    let result = f
        .tools
        .input_text(InputTextParams {
            session_id: sid(&project),
            text: String::new(),
            screenshot_after_ms: None,
        })
        .await;
    assert!(is_error(&result));
    assert_eq!(first_text(&result), "Error: text is required");
}

#[tokio::test]
async fn input_text_ascii_path_escapes_spaces() {
    let f = fixture();
    let project = f.bind_device().await;
    let result = f
        .tools
        .input_text(InputTextParams {
            session_id: sid(&project),
            text: "hello world".into(),
            screenshot_after_ms: None,
        })
        .await;
    assert_eq!(first_text(&result), "Typed 11 chars");
    assert_eq!(f.adb.count(Some(EMU), "shell input text hello%sworld"), 1);
}

#[tokio::test]
async fn input_text_unicode_path_activates_keyboard_once_and_counts_utf16() {
    let f = fixture();
    let project = f.bind_device().await;
    f.adb.on(
        Some(EMU),
        "shell pm list packages com.android.adbkeyboard",
        vec![ok("package:com.android.adbkeyboard")],
    );
    let params = || InputTextParams {
        session_id: sid(&project),
        text: "한글😀".into(),
        screenshot_after_ms: None,
    };
    let result = f.tools.input_text(params()).await;
    assert!(!is_error(&result), "{}", first_text(&result));
    assert_eq!(
        first_text(&result),
        "Typed 4 chars via ADBKeyboard IME (original keyboard is restored on android_close_session)"
    );
    f.tools.input_text(params()).await;
    assert_eq!(
        f.adb
            .count(Some(EMU), "shell ime set com.android.adbkeyboard/.AdbIME"),
        1
    );
    assert!(f.state(&project).adb_keyboard_active);
}

#[tokio::test]
async fn rebind_keeps_original_ime_and_restores_on_close() {
    let f = fixture();
    let project = f.bind_device().await;
    f.tools
        .session(&project.session_id)
        .unwrap()
        .with_active_state(|st| st.adb_keyboard_active = true);

    let result = f
        .tools
        .use_device(UseDeviceParams {
            session_id: sid(&project),
            serial: None,
        })
        .await;
    assert!(result.starts_with("Selected device"), "{result}");
    assert_eq!(
        f.adb
            .count(Some(EMU), &format!("shell ime set {LATIN_IME}")),
        0
    );
    assert_eq!(
        f.adb
            .count(Some(EMU), "shell settings get secure default_input_method"),
        1
    );
    let state = f.state(&project);
    assert_eq!(state.original_ime.as_deref(), Some(LATIN_IME));
    assert!(state.adb_keyboard_active);

    f.tools
        .close_session(SessionOnlyParams {
            session_id: sid(&project),
        })
        .await;
    assert_eq!(
        f.adb
            .count(Some(EMU), &format!("shell ime set {LATIN_IME}")),
        1
    );
}

// ===== session cleanup =====

#[tokio::test]
async fn close_session_returns_no_session_when_unknown() {
    let f = fixture();
    assert_eq!(
        f.tools
            .close_session(SessionOnlyParams {
                session_id: UNKNOWN_SID.into()
            })
            .await,
        "No session to close"
    );
}

#[tokio::test]
async fn close_session_keeps_registry_entry_for_other_tools() {
    let f = fixture();
    let project = f.bind_device().await;
    let result = f
        .tools
        .close_session(SessionOnlyParams {
            session_id: sid(&project),
        })
        .await;
    assert_eq!(
        result,
        "Android session closed (device binding released; sessionId remains valid for other tools)"
    );
    assert_eq!(f.tools.session_count(), 0);
    assert!(f.registry.require(Some(&project.session_id)).is_ok());
}

#[tokio::test]
async fn shutdown_should_be_idempotent_and_restore_imes() {
    let f = fixture();
    f.tools.shutdown().await;
    assert_eq!(f.tools.session_count(), 0);

    let project = f.bind_device().await;
    f.tools
        .session(&project.session_id)
        .unwrap()
        .with_active_state(|st| st.adb_keyboard_active = true);
    f.tools.shutdown().await;
    f.tools.shutdown().await;
    assert_eq!(f.tools.session_count(), 0);
    assert_eq!(
        f.adb
            .count(Some(EMU), &format!("shell ime set {LATIN_IME}")),
        1
    );
}

#[tokio::test]
async fn install_rejects_missing_apk() {
    let f = fixture();
    let project = f.bind_device().await;
    let missing = f.registry.screenshot_base_dir().join("missing.apk");
    let result = f
        .tools
        .install_app(InstallAppParams {
            session_id: sid(&project),
            apk_path: missing.display().to_string(),
            reinstall: None,
        })
        .await;
    assert!(!is_error(&result));
    assert!(first_text(&result).starts_with("Error: APK file not found"));
}

#[tokio::test]
async fn install_reports_output() {
    let f = fixture();
    let project = f.bind_device().await;
    let apk = f.registry.screenshot_base_dir().join("app.apk");
    std::fs::write(&apk, b"x").unwrap();
    f.adb.on(
        Some(EMU),
        "install",
        vec![ok("Performing Streamed Install\nSuccess")],
    );
    let result = f
        .tools
        .install_app(InstallAppParams {
            session_id: sid(&project),
            apk_path: apk.display().to_string(),
            reinstall: None,
        })
        .await;
    assert_eq!(
        first_text(&result),
        "Installed app.apk: Performing Streamed Install\nSuccess"
    );
    let call = f
        .adb
        .calls()
        .into_iter()
        .find(|c| c.args[0] == "install")
        .unwrap();
    assert_eq!(call.args[1], "-r");
    assert_eq!(call.timeout, Duration::from_secs(120));
}

// ===== per-serial state =====

#[tokio::test]
async fn per_serial_state_preserved_when_switching_devices() {
    let f = fixture();
    let project = f.bind_device().await;
    let dump_a = f.set_dump(
        &project,
        vec![node(
            0,
            "로그인",
            "com.example:id/btn_login",
            [0, 0, 100, 100],
        )],
    );

    f.adb.on(
        None,
        "devices -l",
        vec![ok(&devices_output(&[
            "emulator-5554\tdevice model:sdk_gphone64",
            "emulator-5556\tdevice model:sdk_gphone64",
        ]))],
    );
    // Newer rules take precedence over the single-device rule from bind_device.
    f.adb.on(
        Some("emulator-5556"),
        "shell settings get secure default_input_method",
        vec![ok("second.ime/.Ime")],
    );
    let r = f
        .tools
        .use_device(UseDeviceParams {
            session_id: sid(&project),
            serial: Some("emulator-5556".into()),
        })
        .await;
    assert!(r.starts_with("Selected device emulator-5556"), "{r}");
    let st = f.state(&project);
    assert!(st.last_dump.is_none());
    assert_eq!(st.original_ime.as_deref(), Some("second.ime/.Ime"));

    let r = f
        .tools
        .use_device(UseDeviceParams {
            session_id: sid(&project),
            serial: Some("emulator-5554".into()),
        })
        .await;
    assert!(r.starts_with("Selected device emulator-5554"), "{r}");
    let st = f.state(&project);
    assert!(Arc::ptr_eq(st.last_dump.as_ref().unwrap(), &dump_a));
    assert_eq!(st.original_ime.as_deref(), Some(LATIN_IME));
}

#[tokio::test]
async fn use_device_retries_ime_capture_when_first_read_was_blank() {
    let f = fixture();
    let project = f.registry.init(Some("ime-retry")).unwrap();
    f.adb.on(
        None,
        "devices -l",
        vec![ok(&devices_output(&["emulator-5554\tdevice"]))],
    );
    f.adb.on(
        Some(EMU),
        "shell settings get secure default_input_method",
        vec![ok(""), ok("real.ime/.Ime")],
    );
    let params = || UseDeviceParams {
        session_id: sid(&project),
        serial: None,
    };
    f.tools.use_device(params()).await;
    assert_eq!(f.state(&project).original_ime.as_deref(), Some(""));
    f.tools.use_device(params()).await;
    assert_eq!(
        f.state(&project).original_ime.as_deref(),
        Some("real.ime/.Ime")
    );
}

// ===== other tool guards =====

#[tokio::test]
async fn set_offline_fails_without_use_device() {
    let f = fixture();
    let project = f.registry.init(Some("no-device-offline")).unwrap();
    let result = f
        .tools
        .set_offline(SetOfflineParams {
            session_id: sid(&project),
            offline: true,
        })
        .await;
    assert!(is_error(&result));
    assert!(first_text(&result).contains("android_use_device"));
}

#[tokio::test]
async fn set_network_conditions_converts_domain_error_to_error_string() {
    let f = fixture();
    // Bind a physical device so the domain check rejects it.
    let project = f.registry.init(Some("phys")).unwrap();
    f.adb.on(
        None,
        "devices -l",
        vec![ok(&devices_output(&["R3CN30XXXX\tdevice"]))],
    );
    f.tools
        .use_device(UseDeviceParams {
            session_id: sid(&project),
            serial: None,
        })
        .await;
    let result = f
        .tools
        .set_network_conditions(NetworkConditionsParams {
            session_id: sid(&project),
            speed: Some("3g".into()),
            delay: None,
        })
        .await;
    assert!(!is_error(&result));
    let text = first_text(&result);
    assert!(
        text.starts_with("Error setting network conditions:"),
        "{text}"
    );
    assert!(text.contains("requires an emulator"), "{text}");
}

#[tokio::test]
async fn set_network_conditions_reports_applied_values() {
    let f = fixture();
    let project = f.bind_device().await;
    f.adb.on(Some(EMU), "emu", vec![ok("OK")]);
    let result = f
        .tools
        .set_network_conditions(NetworkConditionsParams {
            session_id: sid(&project),
            speed: Some(" 3g ".into()),
            delay: Some("none".into()),
        })
        .await;
    assert_eq!(
        first_text(&result),
        "Network conditions applied speed=3g delay=none. Reset with speed='full' delay='none'."
    );
}

#[tokio::test]
async fn set_http_proxy_messages() {
    let f = fixture();
    let project = f.bind_device().await;
    let set = f
        .tools
        .set_http_proxy(HttpProxyParams {
            session_id: sid(&project),
            host_port: Some(" 10.0.2.2:8888 ".into()),
        })
        .await;
    assert_eq!(
        first_text(&set),
        "Global HTTP proxy set to 10.0.2.2:8888 (make sure the proxy is running and reachable from the device)"
    );
    let clear = f
        .tools
        .set_http_proxy(HttpProxyParams {
            session_id: sid(&project),
            host_port: Some(":0".into()),
        })
        .await;
    assert_eq!(first_text(&clear), "Global HTTP proxy cleared");
}

#[tokio::test]
async fn push_file_rejects_missing_local_file() {
    let f = fixture();
    let project = f.bind_device().await;
    let result = f
        .tools
        .push_file(PushFileParams {
            session_id: sid(&project),
            local_path: f
                .registry
                .screenshot_base_dir()
                .join("missing.png")
                .display()
                .to_string(),
            device_path: "/sdcard/Download/missing.png".into(),
            scan_media: None,
        })
        .await;
    assert!(first_text(&result).starts_with("Error: local file not found"));
}

#[tokio::test]
async fn push_file_rejects_relative_device_path() {
    let f = fixture();
    let project = f.bind_device().await;
    let local = f.registry.screenshot_base_dir().join("exists.png");
    std::fs::write(&local, b"").unwrap();
    let result = f
        .tools
        .push_file(PushFileParams {
            session_id: sid(&project),
            local_path: local.display().to_string(),
            device_path: "sdcard/Download/exists.png".into(),
            scan_media: None,
        })
        .await;
    assert!(first_text(&result).starts_with("Error: devicePath must be an absolute device path"));
}

#[tokio::test]
async fn push_file_pushes_and_scans() {
    let f = fixture();
    let project = f.bind_device().await;
    let local = f.registry.screenshot_base_dir().join("exists.png");
    std::fs::write(&local, b"").unwrap();
    f.adb.on(Some(EMU), "push", vec![ok("1 file pushed.")]);
    let result = f
        .tools
        .push_file(PushFileParams {
            session_id: sid(&project),
            local_path: local.display().to_string(),
            device_path: " /sdcard/Download/x.png ".into(),
            scan_media: None,
        })
        .await;
    assert_eq!(
        first_text(&result),
        "Pushed exists.png -> /sdcard/Download/x.png (media scan broadcast sent): 1 file pushed."
    );
    assert_eq!(
        f.adb.count(
            Some(EMU),
            "shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/x.png"
        ),
        1
    );
}

#[tokio::test]
async fn capture_timeline_rejects_over_budget_before_session_check() {
    let f = fixture();
    let result = f
        .tools
        .capture_timeline(CaptureTimelineParams {
            session_id: UNKNOWN_SID.into(),
            interval_ms: Some(1000),
            count: Some(200),
        })
        .await;
    assert!(is_error(&result));
    assert_eq!(
        first_text(&result),
        "Error: total timeline duration (including ~1000ms screencap overhead per frame) would exceed 110s \
         (intervalMs=1000 * count=200). Reduce either parameter."
    );
}

#[tokio::test]
async fn record_screen_requires_duration() {
    let f = fixture();
    let project = f.bind_device().await;
    let result = f
        .tools
        .record_screen(RecordScreenParams {
            session_id: sid(&project),
            duration_ms: 0,
            bit_rate_mbps: None,
        })
        .await;
    assert_eq!(
        first_text(&result),
        "Error: durationMs is required (1..180000)"
    );
}

#[tokio::test]
async fn sample_pixels_rejects_missing_and_malformed_input() {
    let f = fixture();
    let project = f.bind_device().await;
    let missing = f
        .tools
        .sample_pixels(SamplePixelsParams {
            session_id: sid(&project),
            ..Default::default()
        })
        .await;
    assert!(first_text(&missing).starts_with("Error: provide points"));
    let malformed = f
        .tools
        .sample_pixels(SamplePixelsParams {
            session_id: sid(&project),
            points: Some("10".into()),
            rect: None,
        })
        .await;
    assert!(first_text(&malformed).starts_with("Error: invalid point"));
}

#[tokio::test]
async fn logcat_defaults_and_clamps() {
    let f = fixture();
    let project = f.bind_device().await;
    f.tools
        .logcat(LogcatParams {
            session_id: sid(&project),
            tail_lines: Some(5000),
            ..Default::default()
        })
        .await;
    assert_eq!(
        f.adb
            .count(Some(EMU), "shell logcat -d -v threadtime -t 1000 *:E"),
        1
    );
}

// ===== static helpers =====

#[test]
fn screenshot_budget_error_guards_upper_bound_only() {
    assert!(screenshot_budget_error(Some(0)).is_none());
    assert!(screenshot_budget_error(Some(110_000)).is_none());
    assert!(screenshot_budget_error(None).is_none());
    assert!(
        screenshot_budget_error(Some(110_001))
            .unwrap()
            .starts_with("Error: screenshotAfterMs")
    );
}

#[test]
fn normalize_keycode_handles_names_and_numbers() {
    assert_eq!(device::normalize_keycode("back"), "KEYCODE_BACK");
    assert_eq!(device::normalize_keycode("KEYCODE_HOME"), "KEYCODE_HOME");
    assert_eq!(device::normalize_keycode("4"), "4");
}

#[test]
fn abs_normalize_resolves_dot_segments() {
    let base = std::path::absolute(".").unwrap();
    assert_eq!(
        abs_normalize(Path::new("a/../b/./c")),
        base.join("b").join("c")
    );
}

#[test]
fn tool_schemas_keep_java_names_and_required_params() {
    let tools = NamsicServer::android_router().list_all();
    assert_eq!(tools.len(), 22);
    let swipe = tools.iter().find(|t| t.name == "android_swipe").unwrap();
    let required: Vec<&str> = swipe.input_schema["required"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|v| v.as_str())
        .collect();
    assert_eq!(required, vec!["sessionId", "x1", "y1", "x2", "y2"]);
    let tap = tools.iter().find(|t| t.name == "android_tap").unwrap();
    assert!(tap.description.as_deref().unwrap().contains(
        "Targeting priority: (1) selector — substring match against the last android_dump_ui's resource-id"
    ));
    let props = tap.input_schema["properties"].as_object().unwrap();
    assert_eq!(
        props["screenshotAfterMs"]["description"].as_str().unwrap(),
        "Optional. If set (>=0, max 110000), waits this many ms after the action, then captures a JPEG \
         screenshot returned inline plus its saved file path. Prefer this over a separate android_screenshot \
         call to verify the action result in a single round trip. Omit for no screenshot."
    );
    assert_eq!(
        props["sessionId"]["description"].as_str().unwrap(),
        SESSION_PARAM_DESC
    );
}

/// Real-device smoke: `cargo test real_device_smoke -- --ignored --nocapture`.
/// Read-only except an optional Korean `input_text` (only when ADBKeyboard is available).
#[tokio::test]
#[ignore = "requires adb (and optionally a connected device)"]
async fn real_device_smoke() {
    let dir = tempfile::tempdir().unwrap();
    let registry = Arc::new(ProjectSessionRegistry::new(dir.path()));
    let config = AndroidConfig {
        adb_keyboard_apk: std::env::var("ADB_KEYBOARD_APK").ok(),
        ..AndroidConfig::default()
    };
    let tools = AndroidTools::new(registry.clone(), config);
    let project = registry.init(Some("smoke")).unwrap();
    let session_id = sid(&project);

    let listed = tools
        .list_devices(SessionOnlyParams {
            session_id: session_id.clone(),
        })
        .await;
    println!("list_devices:\n{listed}");
    if !listed.starts_with("Connected devices:") {
        println!("no device connected — skipping device steps");
        return;
    }
    let used = tools
        .use_device(UseDeviceParams {
            session_id: session_id.clone(),
            serial: None,
        })
        .await;
    println!("use_device: {used}");
    assert!(used.starts_with("Selected device"), "{used}");

    let dump = tools
        .dump_ui(DumpUiParams {
            session_id: session_id.clone(),
            max_nodes: Some(10),
        })
        .await;
    println!("dump_ui:\n{}", first_text(&dump));

    let shot = tools
        .screenshot(ScreenshotParams {
            session_id: session_id.clone(),
            format: None,
        })
        .await;
    println!(
        "screenshot: isError={} text={} blocks={}",
        is_error(&shot),
        first_text(&shot),
        shot.content.len()
    );
    assert!(!is_error(&shot));

    let pixels = tools
        .sample_pixels(SamplePixelsParams {
            session_id: session_id.clone(),
            points: Some("10,10;100,200".into()),
            rect: Some("0,0,50,50".into()),
        })
        .await;
    println!("sample_pixels: {}", first_text(&pixels));
    assert!(first_text(&pixels).starts_with("{\"imageWidth\":"));

    let serial = tools.session(&session_id).unwrap().serial();
    let keyboard_ready = std::env::var("ADB_KEYBOARD_APK").is_ok()
        || tools.device.is_adb_keyboard_installed(&serial).await;
    if keyboard_ready {
        let typed = tools
            .input_text(InputTextParams {
                session_id: session_id.clone(),
                text: "한글".into(),
                screenshot_after_ms: None,
            })
            .await;
        println!("input_text(한글): {}", first_text(&typed));
    } else {
        println!("ADBKeyboard unavailable — skipping Korean input_text");
    }
    println!(
        "close_session: {}",
        tools.close_session(SessionOnlyParams { session_id }).await
    );
}
