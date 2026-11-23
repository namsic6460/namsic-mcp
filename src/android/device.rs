//! All adb domain logic (the tool layer is a thin adapter). Failures are
//! returned as `Err(message)` (Java `IllegalStateException`).

use std::collections::HashSet;
use std::io::Cursor;
use std::path::Path;
use std::sync::{Arc, LazyLock, Mutex};
use std::time::Duration;

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD as BASE64;
use image::codecs::jpeg::JpegEncoder;
use image::imageops::FilterType;
use image::{DynamicImage, RgbImage, RgbaImage};
use regex::Regex;

use super::adb::{AdbRunner, args};
use super::ui::{self, ParseResult};
use crate::config::AndroidConfig;
use crate::util::pixel_sampler::java_trim;

pub type R<T> = Result<T, String>;

pub const ADB_KEYBOARD_PKG: &str = "com.android.adbkeyboard";
pub const ADB_KEYBOARD_IME: &str = "com.android.adbkeyboard/.AdbIME";

const DUMP_DEVICE_PATH: &str = "/sdcard/window_dump.xml";
const DUMP_MAX_ATTEMPTS: u32 = 3;
const DUMP_RETRY_DELAY: Duration = Duration::from_millis(500);
pub const JPEG_QUALITY: u8 = 80;
/// Characters `adb shell input text` can carry safely — everything else goes via ADBKeyboard.
const SAFE_INPUT_SYMBOLS: &str = " .,:@_/+-";
const SCREEN_WAKE_SETTLE: Duration = Duration::from_millis(500);
const SHOT_DEVICE_PATH: &str = "/sdcard/namsic_shot.png";
const PNG_SIGNATURE_LENGTH: usize = 8;
const RECORD_DEVICE_PATH: &str = "/sdcard/namsic_record.mp4";

/// Keys that directly control screen power (names or numbers, as normalized).
const POWER_KEYCODES: [&str; 8] = [
    "KEYCODE_POWER",
    "KEYCODE_WAKEUP",
    "KEYCODE_SLEEP",
    "KEYCODE_SOFT_SLEEP",
    "26",
    "223",
    "224",
    "276",
];

static DISPLAY_POWER_STATE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"Display Power: state=([A-Za-z0-9_]+)").expect("valid regex"));
static WAKEFULNESS: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"mWakefulness=([A-Za-z0-9_]+)").expect("valid regex"));
static LEGACY_SCREEN_ON: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"mScreenOn=(true|false)").expect("valid regex"));
static PROXY_PATTERN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[A-Za-z0-9.\-]+:[0-9]+$").expect("valid regex"));

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceInfo {
    pub serial: String,
    pub state: String,
    pub model: String,
}

impl DeviceInfo {
    pub fn describe(&self) -> String {
        if self.model.is_empty() {
            format!("{} (state={})", self.serial, self.state)
        } else {
            format!(
                "{} (state={}, model={})",
                self.serial, self.state, self.model
            )
        }
    }
}

#[derive(Debug, Clone)]
pub struct ScreenshotResult {
    pub bytes: Vec<u8>,
    pub filename: String,
    pub mime_type: &'static str,
    pub device_width: u32,
    pub device_height: u32,
    pub image_width: u32,
    pub image_height: u32,
}

impl ScreenshotResult {
    pub fn base64(&self) -> String {
        BASE64.encode(&self.bytes)
    }

    /// Image pixels differ from device coordinates when downscaled, so the ratio is stated.
    pub fn scale_summary(&self) -> String {
        if self.device_width == self.image_width && self.device_height == self.image_height {
            return format!(
                "device {}x{}, image 1:1",
                self.device_width, self.device_height
            );
        }
        let scale = f64::from(self.image_width) / f64::from(self.device_width);
        let s = java_fixed3(scale);
        format!(
            "device {}x{}, image {}x{} (scale {s} — divide image coords by {s} for device tap coords)",
            self.device_width, self.device_height, self.image_width, self.image_height
        )
    }
}

/// Java `String.format("%.3f", v)`: HALF_UP rounding of the shortest decimal representation.
pub fn java_fixed3(v: f64) -> String {
    if !v.is_finite() {
        return if v.is_nan() {
            "NaN".to_owned()
        } else if v > 0.0 {
            "Infinity".to_owned()
        } else {
            "-Infinity".to_owned()
        };
    }
    let repr = format!("{}", v.abs());
    let (int_part, frac_part) = repr.split_once('.').unwrap_or((repr.as_str(), ""));
    let mut digits: Vec<u8> = int_part.bytes().map(|b| b - b'0').collect();
    let frac: Vec<u8> = frac_part.bytes().map(|b| b - b'0').collect();
    for i in 0..3 {
        digits.push(frac.get(i).copied().unwrap_or(0));
    }
    if frac.get(3).copied().unwrap_or(0) >= 5 {
        let mut i = digits.len();
        loop {
            if i == 0 {
                digits.insert(0, 1);
                break;
            }
            i -= 1;
            if digits[i] == 9 {
                digits[i] = 0;
            } else {
                digits[i] += 1;
                break;
            }
        }
    }
    let split = digits.len() - 3;
    let to_str = |d: &[u8]| d.iter().map(|x| char::from(b'0' + x)).collect::<String>();
    let sign = if v.is_sign_negative() && digits.iter().any(|&d| d != 0) {
        "-"
    } else {
        ""
    };
    format!(
        "{sign}{}.{}",
        to_str(&digits[..split]),
        to_str(&digits[split..])
    )
}

/// Java `String.lines()`-like split on `\n`, `\r\n` and `\r` (may yield extra empty pieces).
fn java_lines(s: &str) -> impl Iterator<Item = &str> {
    s.split(['\r', '\n'])
}

/// Java `split("\\s+")` on ASCII whitespace, dropping empty pieces.
fn split_ascii_ws(s: &str) -> impl Iterator<Item = &str> {
    s.split([' ', '\t', '\n', '\x0B', '\x0C', '\r'])
        .filter(|t| !t.is_empty())
}

fn is_blank(s: Option<&str>) -> bool {
    s.is_none_or(|v| v.trim().is_empty())
}

/// `"HOME"`/`"back"` -> `"KEYCODE_HOME"`/`"KEYCODE_BACK"`; digits and `KEYCODE_*` kept.
pub fn normalize_keycode(key: &str) -> String {
    let trimmed = java_trim(key);
    if !trimmed.is_empty() && trimmed.chars().all(|c| c.is_ascii_digit()) {
        return trimmed.to_owned();
    }
    let upper = trimmed.to_uppercase();
    if upper.starts_with("KEYCODE_") {
        upper
    } else {
        format!("KEYCODE_{upper}")
    }
}

/// Power/sleep/wake keys skip the automatic screen-on guard.
pub fn is_power_key(keycode: &str) -> bool {
    POWER_KEYCODES.contains(&keycode.to_uppercase().as_str())
}

/// Whether `adb shell input text` can carry `text` as-is.
pub fn is_safe_ascii_text(text: &str) -> bool {
    text.encode_utf16().all(|c| {
        u8::try_from(c)
            .is_ok_and(|b| b.is_ascii_alphanumeric() || SAFE_INPUT_SYMBOLS.as_bytes().contains(&b))
    })
}

/// Wireless (TCP/IP or mDNS) adb serials: disabling wifi there would sever adb itself.
pub fn is_wireless_serial(serial: Option<&str>) -> bool {
    match serial {
        None => false,
        Some(s) if s.starts_with("emulator-") => false,
        Some(s) => s.contains(':') || s.contains("_adb-tls-"),
    }
}

/// Composites onto black (drops alpha) and, when the longest side exceeds
/// `max_dimension`, shrinks proportionally with bilinear filtering.
pub fn downscale(source: &DynamicImage, max_dimension: u32) -> RgbImage {
    let width = source.width();
    let height = source.height();
    let longest = width.max(height);
    let scale = if longest > max_dimension {
        f64::from(max_dimension) / f64::from(longest)
    } else {
        1.0
    };
    let new_width = ((f64::from(width) * scale).round() as u32).max(1);
    let new_height = ((f64::from(height) * scale).round() as u32).max(1);
    let rgb = to_rgb_over_black(source);
    if scale >= 1.0 {
        return rgb;
    }
    image::imageops::resize(&rgb, new_width, new_height, FilterType::Triangle)
}

fn to_rgb_over_black(source: &DynamicImage) -> RgbImage {
    if !source.color().has_alpha() {
        return source.to_rgb8();
    }
    let rgba: RgbaImage = source.to_rgba8();
    RgbImage::from_fn(rgba.width(), rgba.height(), |x, y| {
        let [r, g, b, a] = rgba.get_pixel(x, y).0;
        let mul = |c: u8| ((u16::from(c) * u16::from(a) + 127) / 255) as u8;
        image::Rgb([mul(r), mul(g), mul(b)])
    })
}

pub fn to_jpeg(image: &RgbImage, quality: u8) -> R<Vec<u8>> {
    let mut out = Cursor::new(Vec::with_capacity(1 << 16));
    JpegEncoder::new_with_quality(&mut out, quality)
        .encode_image(image)
        .map_err(|e| e.to_string())?;
    Ok(out.into_inner())
}

fn try_decode(png: &[u8]) -> Option<DynamicImage> {
    image::load_from_memory(png).ok()
}

/// Diagnostic hex of the leading bytes (a PNG starts with "89 50 4e 47 0d 0a 1a 0a").
fn hex_prefix(data: &[u8]) -> String {
    data.iter()
        .take(PNG_SIGNATURE_LENGTH)
        .map(|b| format!("{b:02x}"))
        .collect::<Vec<_>>()
        .join(" ")
}

pub struct AndroidDeviceService {
    adb: Arc<dyn AdbRunner>,
    config: AndroidConfig,
    /// Devices whose `exec-out screencap -p` stdout is not a decodable PNG; they use file + pull.
    pull_only_screencap: Mutex<HashSet<String>>,
}

impl AndroidDeviceService {
    pub fn new(adb: Arc<dyn AdbRunner>, config: AndroidConfig) -> Self {
        Self {
            adb,
            config,
            pull_only_screencap: Mutex::new(HashSet::new()),
        }
    }

    fn pull_only(&self) -> std::sync::MutexGuard<'_, HashSet<String>> {
        self.pull_only_screencap
            .lock()
            .unwrap_or_else(|e| e.into_inner())
    }

    // ===== devices =====

    pub async fn list_devices(&self) -> R<Vec<DeviceInfo>> {
        let result = self
            .adb
            .run_text(None, args(["devices", "-l"]), self.config.command_timeout)
            .await;
        if !result.success() {
            return Err(format!(
                "adb devices failed: {}",
                if result.timed_out {
                    "timed out"
                } else {
                    &result.stdout
                }
            ));
        }
        let mut devices = Vec::new();
        for line in java_lines(&result.stdout) {
            let trimmed = java_trim(line);
            if trimmed.is_empty()
                || trimmed.starts_with("List of devices")
                || trimmed.starts_with('*')
            {
                continue;
            }
            let parts: Vec<&str> = split_ascii_ws(trimmed).collect();
            if parts.len() < 2 {
                continue;
            }
            let model = parts
                .iter()
                .find_map(|p| p.strip_prefix("model:"))
                .unwrap_or("")
                .to_owned();
            devices.push(DeviceInfo {
                serial: parts[0].to_owned(),
                state: parts[1].to_owned(),
                model,
            });
        }
        Ok(devices)
    }

    /// Without a serial, auto-selects the single ready device.
    pub async fn resolve_device(&self, requested: Option<&str>) -> R<DeviceInfo> {
        let devices = self.list_devices().await?;
        if let Some(requested) = requested.filter(|r| !r.trim().is_empty()) {
            let found = devices
                .iter()
                .find(|d| d.serial == requested)
                .ok_or_else(|| {
                    format!(
                        "Device not found: {requested}. Connected: {}",
                        format_devices(&devices)
                    )
                })?;
            if found.state != "device" {
                return Err(format!(
                    "Device {requested} is in state '{}' (unauthorized = approve the RSA debugging prompt on the \
                     device; offline = reconnect)",
                    found.state
                ));
            }
            return Ok(found.clone());
        }
        let ready: Vec<DeviceInfo> = devices
            .iter()
            .filter(|d| d.state == "device")
            .cloned()
            .collect();
        match ready.len() {
            0 => Err(format!(
                "No device in 'device' state. Connected: {} — start an emulator or connect a device with USB \
                 debugging enabled.",
                format_devices(&devices)
            )),
            1 => Ok(ready[0].clone()),
            _ => Err(format!(
                "Multiple devices connected; specify a serial. Ready: {}",
                format_devices(&ready)
            )),
        }
    }

    // ===== apps =====

    pub async fn install(&self, serial: &str, apk_path: &Path, reinstall: bool) -> R<String> {
        let mut a = args(["install"]);
        if reinstall {
            a.push("-r".to_owned());
        }
        a.push(super::session::absolute_string(apk_path));
        let result = self
            .adb
            .run_text(Some(serial), a, self.config.install_timeout)
            .await;
        if result.timed_out {
            return Err(format!(
                "adb install timed out after {}s",
                self.config.install_timeout.as_secs()
            ));
        }
        if result.exit_code != 0 || !result.stdout.contains("Success") {
            return Err(format!("adb install failed: {}", result.stdout));
        }
        Ok(result.stdout)
    }

    pub async fn launch_app(
        &self,
        serial: &str,
        package_name: &str,
        activity: Option<&str>,
    ) -> R<String> {
        if let Some(activity) = activity.filter(|a| !a.trim().is_empty()) {
            let component = if activity.contains('/') {
                activity.to_owned()
            } else {
                format!("{package_name}/{activity}")
            };
            let output = self
                .run_checked(
                    serial,
                    args(["shell", "am", "start", "-W", "-n", &component]),
                    self.config.command_timeout,
                    "am start",
                )
                .await?;
            // Only line starts count: "cmp=.../.ErrorReportActivity" is a normal start.
            if java_lines(&output).any(|l| java_trim(l).starts_with("Error")) {
                return Err(format!("am start failed: {output}"));
            }
            return Ok(output);
        }
        let output = self
            .run_checked(
                serial,
                args([
                    "shell",
                    "monkey",
                    "-p",
                    package_name,
                    "-c",
                    "android.intent.category.LAUNCHER",
                    "1",
                ]),
                self.config.command_timeout,
                "monkey launch",
            )
            .await?;
        if output.contains("No activities found") || output.contains("monkey aborted") {
            return Err(format!(
                "monkey launch failed (package not installed or has no launcher activity): {output}"
            ));
        }
        Ok(output)
    }

    pub async fn stop_app(&self, serial: &str, package_name: &str) -> R<()> {
        self.run_checked(
            serial,
            args(["shell", "am", "force-stop", package_name]),
            self.config.command_timeout,
            "am force-stop",
        )
        .await
        .map(drop)
    }

    pub async fn list_packages(
        &self,
        serial: &str,
        third_party_only: bool,
        name_filter: Option<&str>,
    ) -> R<String> {
        let mut a = args(["shell", "pm", "list", "packages"]);
        if third_party_only {
            a.push("-3".to_owned());
        }
        if let Some(f) = name_filter.filter(|f| !f.trim().is_empty()) {
            a.push(java_trim(f).to_owned());
        }
        self.run_checked(serial, a, self.config.command_timeout, "pm list packages")
            .await
    }

    // ===== UI =====

    /// dump -> exec-out cat -> parse, retrying up to 3 times on idle failure / invalid XML.
    pub async fn dump_ui(&self, serial: &str, max_nodes: usize) -> R<ParseResult> {
        let mut last_error = String::new();
        for attempt in 1..=DUMP_MAX_ATTEMPTS {
            if attempt > 1 {
                tokio::time::sleep(DUMP_RETRY_DELAY).await;
            }
            let dump = self
                .adb
                .run_text(
                    Some(serial),
                    args(["shell", "uiautomator", "dump", DUMP_DEVICE_PATH]),
                    self.config.dump_timeout,
                )
                .await;
            if dump.timed_out
                || dump.stdout.contains("could not get idle")
                || dump.stdout.contains("ERROR")
            {
                last_error = if dump.timed_out {
                    "dump timed out".to_owned()
                } else {
                    dump.stdout
                };
                continue;
            }
            let cat = self
                .adb
                .run_binary(
                    Some(serial),
                    args(["exec-out", "cat", DUMP_DEVICE_PATH]),
                    self.config.dump_timeout,
                )
                .await;
            if cat.is_fail() {
                last_error = format!("cat failed: {}", cat.stderr);
                continue;
            }
            let xml = String::from_utf8_lossy(&cat.stdout);
            if !xml.contains("<?xml") && !xml.contains("<hierarchy") {
                last_error = "invalid dump content".to_owned();
                continue;
            }
            return Ok(ui::parse(Some(&xml), max_nodes));
        }
        Err(format!(
            "UI dump failed after {DUMP_MAX_ATTEMPTS} attempts ({last_error}). The screen may be animating; retry \
             after it settles, or use android_screenshot instead."
        ))
    }

    pub async fn tap(&self, serial: &str, x: i32, y: i32) -> R<()> {
        self.run_checked(
            serial,
            args(["shell", "input", "tap", &x.to_string(), &y.to_string()]),
            self.config.command_timeout,
            "input tap",
        )
        .await
        .map(drop)
    }

    pub async fn swipe(
        &self,
        serial: &str,
        (x1, y1, x2, y2): (i32, i32, i32, i32),
        duration_ms: i32,
    ) -> R<()> {
        self.run_checked(
            serial,
            args([
                "shell",
                "input",
                "swipe",
                &x1.to_string(),
                &y1.to_string(),
                &x2.to_string(),
                &y2.to_string(),
                &duration_ms.to_string(),
            ]),
            self.config.command_timeout,
            "input swipe",
        )
        .await
        .map(drop)
    }

    pub async fn press_key(&self, serial: &str, keycode: &str) -> R<()> {
        self.run_checked(
            serial,
            args(["shell", "input", "keyevent", keycode]),
            self.config.command_timeout,
            "input keyevent",
        )
        .await
        .map(drop)
    }

    // ===== screen power =====

    /// Reads `dumpsys power`: "Display Power: state=" -> "mWakefulness=" -> legacy "mScreenOn=".
    /// Unreadable output counts as on (never poke a working screen needlessly).
    pub async fn is_screen_on(&self, serial: &str) -> bool {
        let result = self
            .adb
            .run_text(
                Some(serial),
                args(["shell", "dumpsys", "power"]),
                self.config.command_timeout,
            )
            .await;
        if !result.success() {
            return true;
        }
        let out = &result.stdout;
        if let Some(c) = DISPLAY_POWER_STATE.captures(out) {
            return matches!(&c[1], "ON" | "VR");
        }
        if let Some(c) = WAKEFULNESS.captures(out) {
            return &c[1] == "Awake";
        }
        if let Some(c) = LEGACY_SCREEN_ON.captures(out) {
            return &c[1] == "true";
        }
        true
    }

    pub async fn ensure_screen_on(&self, serial: &str) -> R<()> {
        if self.is_screen_on(serial).await {
            return Ok(());
        }
        tracing::info!("Screen is off on {serial} — sending WAKEUP before the requested action");
        self.press_key(serial, "KEYCODE_WAKEUP").await?;
        tokio::time::sleep(SCREEN_WAKE_SETTLE).await;
        Ok(())
    }

    // ===== screenshots =====

    /// Captures a raw PNG and saves it; `jpeg` downscales + encodes JPEG q80.
    /// Devices whose stdout PNG is undecodable fall back to file + `adb pull`, remembered per device.
    pub async fn screenshot(
        &self,
        serial: &str,
        dir: &Path,
        filename: &str,
        jpeg: bool,
    ) -> R<ScreenshotResult> {
        let pull_only = self.pull_only().contains(serial);
        let (png, source) = if pull_only {
            let png = self.screencap_via_pull(serial).await?;
            let src = try_decode(&png);
            (png, src)
        } else {
            let png = self.screencap_via_stdout(serial).await?;
            match try_decode(&png) {
                Some(src) => (png, Some(src)),
                None => {
                    tracing::warn!(
                        "screencap stdout was not a decodable PNG on {serial} ({} bytes, header=[{}]); \
                         switching this device to the adb pull capture path",
                        png.len(),
                        hex_prefix(&png)
                    );
                    self.pull_only().insert(serial.to_owned());
                    let png = self.screencap_via_pull(serial).await?;
                    let src = try_decode(&png);
                    (png, src)
                }
            }
        };
        let Some(source) = source else {
            return Err(format!(
                "screencap returned undecodable image data ({} bytes, header=[{}]). If the foreground window is \
                 FLAG_SECURE (DRM/banking/password manager), it cannot be captured.",
                png.len(),
                hex_prefix(&png)
            ));
        };
        let path = dir.join(filename);
        if !jpeg {
            tokio::fs::write(&path, &png)
                .await
                .map_err(|e| format!("Failed to process screenshot: {e}"))?;
            return Ok(ScreenshotResult {
                bytes: png,
                filename: filename.to_owned(),
                mime_type: "image/png",
                device_width: source.width(),
                device_height: source.height(),
                image_width: source.width(),
                image_height: source.height(),
            });
        }
        let scaled = downscale(&source, self.config.screenshot_max_dimension);
        let jpeg_bytes = to_jpeg(&scaled, JPEG_QUALITY)
            .map_err(|e| format!("Failed to process screenshot: {e}"))?;
        tokio::fs::write(&path, &jpeg_bytes)
            .await
            .map_err(|e| format!("Failed to process screenshot: {e}"))?;
        Ok(ScreenshotResult {
            bytes: jpeg_bytes,
            filename: filename.to_owned(),
            mime_type: "image/jpeg",
            device_width: source.width(),
            device_height: source.height(),
            image_width: scaled.width(),
            image_height: scaled.height(),
        })
    }

    async fn screencap_via_stdout(&self, serial: &str) -> R<Vec<u8>> {
        let result = self
            .adb
            .run_binary(
                Some(serial),
                args(["exec-out", "screencap", "-p"]),
                self.config.command_timeout,
            )
            .await;
        if result.is_fail() || result.stdout.is_empty() {
            return Err(format!(
                "screencap failed: {}",
                if result.timed_out {
                    "timed out"
                } else {
                    &result.stderr
                }
            ));
        }
        Ok(result.stdout)
    }

    /// Fallback: capture to a device file, then `adb pull` (binary safe). Temp files are always cleaned up.
    async fn screencap_via_pull(&self, serial: &str) -> R<Vec<u8>> {
        self.run_checked(
            serial,
            args(["shell", "screencap", "-p", SHOT_DEVICE_PATH]),
            self.config.command_timeout,
            "screencap to file",
        )
        .await?;
        let tmp = tempfile::Builder::new()
            .prefix("namsic_shot")
            .suffix(".png")
            .tempfile()
            .map_err(|e| format!("could not create temp file for screencap pull: {e}"))?
            .into_temp_path();
        let tmp_str = tmp.display().to_string();
        let pull = self
            .adb
            .run_text(
                Some(serial),
                args(["pull", SHOT_DEVICE_PATH, &tmp_str]),
                self.config.command_timeout,
            )
            .await;
        let result = if pull.timed_out || pull.exit_code != 0 {
            Err(format!(
                "adb pull of screencap failed: {}",
                if pull.timed_out {
                    "timed out"
                } else {
                    &pull.stdout
                }
            ))
        } else {
            tokio::fs::read(&tmp)
                .await
                .map_err(|e| format!("failed to read pulled screencap: {e}"))
        };
        drop(tmp);
        self.adb
            .run_text(
                Some(serial),
                args(["shell", "rm", "-f", SHOT_DEVICE_PATH]),
                self.config.command_timeout,
            )
            .await;
        result
    }

    // ===== logcat =====

    pub async fn logcat(
        &self,
        serial: &str,
        filter_spec: &str,
        tail_lines: i32,
        clear_first: bool,
    ) -> R<String> {
        if clear_first {
            self.run_checked(
                serial,
                args(["shell", "logcat", "-c"]),
                self.config.command_timeout,
                "logcat -c",
            )
            .await?;
            return Ok(
                "Logcat buffer cleared. Reproduce the issue, then call android_logcat again without clearFirst."
                    .to_owned(),
            );
        }
        let mut a = args([
            "shell",
            "logcat",
            "-d",
            "-v",
            "threadtime",
            "-t",
            &tail_lines.to_string(),
        ]);
        a.extend(split_ascii_ws(java_trim(filter_spec)).map(str::to_owned));
        let output = self
            .run_checked(serial, a, self.config.command_timeout, "logcat")
            .await?;
        Ok(if output.is_empty() {
            format!("(no log entries matching '{filter_spec}')")
        } else {
            output
        })
    }

    // ===== text input / IME =====

    pub async fn input_ascii_text(&self, serial: &str, text: &str) -> R<()> {
        let escaped = text.replace(' ', "%s");
        self.run_checked(
            serial,
            args(["shell", "input", "text", &escaped]),
            self.config.command_timeout,
            "input text",
        )
        .await
        .map(drop)
    }

    /// Base64-encodes on the host and broadcasts to ADBKeyboard (avoids shell escaping traps).
    pub async fn input_unicode_via_keyboard(&self, serial: &str, text: &str) -> R<()> {
        let b64 = BASE64.encode(text.as_bytes());
        let output = self
            .run_checked(
                serial,
                args([
                    "shell",
                    "am",
                    "broadcast",
                    "-a",
                    "ADB_INPUT_B64",
                    "--es",
                    "msg",
                    &b64,
                ]),
                self.config.command_timeout,
                "ADB_INPUT_B64 broadcast",
            )
            .await?;
        // "Broadcast completed: result=0" is printed regardless; only am's own errors are detectable.
        if output.contains("Error") || output.contains("Exception") {
            return Err(format!("ADB_INPUT_B64 broadcast failed: {output}"));
        }
        Ok(())
    }

    pub async fn is_adb_keyboard_installed(&self, serial: &str) -> bool {
        let result = self
            .adb
            .run_text(
                Some(serial),
                args(["shell", "pm", "list", "packages", ADB_KEYBOARD_PKG]),
                self.config.command_timeout,
            )
            .await;
        result.success()
            && result
                .stdout
                .contains(&format!("package:{ADB_KEYBOARD_PKG}"))
    }

    /// Installs ADBKeyboard from the configured APK if missing, then makes it the active IME.
    pub async fn ensure_adb_keyboard_active(&self, serial: &str) -> R<()> {
        if !self.is_adb_keyboard_installed(serial).await {
            let apk = self
                .config
                .adb_keyboard_apk
                .as_deref()
                .filter(|a| !a.trim().is_empty())
                .ok_or_else(|| {
                    "non-ASCII or special-character text requires the ADBKeyboard IME, which is not installed on \
                     the device and app.android.adb-keyboard-apk is not configured. Download ADBKeyboard.apk \
                     (github.com/senzhk/ADBKeyBoard), set the property, or use plain ASCII text."
                        .to_owned()
                })?;
            self.install(serial, Path::new(apk), true).await?;
            if !self.is_adb_keyboard_installed(serial).await {
                return Err(format!(
                    "ADBKeyboard install did not register package {ADB_KEYBOARD_PKG}"
                ));
            }
        }
        self.run_checked(
            serial,
            args(["shell", "ime", "enable", ADB_KEYBOARD_IME]),
            self.config.command_timeout,
            "ime enable",
        )
        .await?;
        self.run_checked(
            serial,
            args(["shell", "ime", "set", ADB_KEYBOARD_IME]),
            self.config.command_timeout,
            "ime set",
        )
        .await?;
        // Short wait so the first broadcast after the IME switch is not lost.
        tokio::time::sleep(Duration::from_millis(300)).await;
        Ok(())
    }

    pub async fn current_ime(&self, serial: &str) -> String {
        let result = self
            .adb
            .run_text(
                Some(serial),
                args(["shell", "settings", "get", "secure", "default_input_method"]),
                self.config.command_timeout,
            )
            .await;
        if !result.success() {
            return String::new();
        }
        let ime = java_trim(&result.stdout);
        if ime == "null" {
            String::new()
        } else {
            ime.to_owned()
        }
    }

    /// Restores the recorded IME when valid, otherwise `ime reset`.
    pub async fn restore_ime(&self, serial: &str, original_ime: Option<&str>) -> R<()> {
        match original_ime.filter(|i| !i.trim().is_empty()) {
            Some(ime) => self
                .run_checked(
                    serial,
                    args(["shell", "ime", "set", ime]),
                    self.config.command_timeout,
                    "ime set (restore)",
                )
                .await
                .map(drop),
            None => self
                .run_checked(
                    serial,
                    args(["shell", "ime", "reset"]),
                    self.config.command_timeout,
                    "ime reset",
                )
                .await
                .map(drop),
        }
    }

    // ===== network =====

    /// Toggles wifi and mobile data together (svc). Rejected over wireless adb.
    pub async fn set_offline(&self, serial: &str, offline: bool) -> R<()> {
        if is_wireless_serial(Some(serial)) {
            return Err(format!(
                "android_set_offline is disabled over a wireless adb connection ({serial}): disabling wifi would \
                 sever the adb control channel and the device could not be recovered without physical access. \
                 Connect the device over USB to test offline behavior, or use android_set_http_proxy / a \
                 server-side network fault to simulate connectivity loss."
            ));
        }
        let op = if offline { "disable" } else { "enable" };
        self.run_checked(
            serial,
            args(["shell", "svc", "wifi", op]),
            self.config.command_timeout,
            &format!("svc wifi {op}"),
        )
        .await?;
        self.run_checked(
            serial,
            args(["shell", "svc", "data", op]),
            self.config.command_timeout,
            &format!("svc data {op}"),
        )
        .await?;
        Ok(())
    }

    /// Emulator-console network speed/latency emulation (adb emu — emulators only).
    pub async fn set_network_conditions(
        &self,
        serial: &str,
        speed: Option<&str>,
        delay: Option<&str>,
    ) -> R<()> {
        if !serial.starts_with("emulator-") {
            return Err(format!(
                "network speed/delay emulation requires an emulator (serial starting with 'emulator-'); current \
                 device: {serial}. On physical devices use android_set_offline or android_set_http_proxy instead."
            ));
        }
        if is_blank(speed) && is_blank(delay) {
            return Err("specify speed and/or delay".to_owned());
        }
        if let Some(speed) = speed.filter(|s| !s.trim().is_empty()) {
            self.run_emu_checked(
                serial,
                args(["emu", "network", "speed", java_trim(speed)]),
                "emu network speed",
            )
            .await?;
        }
        if let Some(delay) = delay.filter(|s| !s.trim().is_empty()) {
            self.run_emu_checked(
                serial,
                args(["emu", "network", "delay", java_trim(delay)]),
                "emu network delay",
            )
            .await?;
        }
        Ok(())
    }

    /// `adb emu` returns the console reply ("OK"/"KO: ...") — KO is a failure even with exit 0.
    async fn run_emu_checked(&self, serial: &str, a: Vec<String>, what: &str) -> R<()> {
        let output = self
            .run_checked(serial, a, self.config.command_timeout, what)
            .await?;
        if java_lines(&output).any(|l| java_trim(l).starts_with("KO")) {
            return Err(format!("{what} rejected by emulator console: {output}"));
        }
        Ok(())
    }

    /// Sets the global HTTP proxy; blank or ":0" clears it.
    pub async fn set_http_proxy(&self, serial: &str, host_port: Option<&str>) -> R<()> {
        let value = match host_port.filter(|h| !h.trim().is_empty()) {
            Some(h) => java_trim(h).to_owned(),
            None => ":0".to_owned(),
        };
        if value != ":0" && !PROXY_PATTERN.is_match(&value) {
            return Err(format!(
                "invalid proxy value '{value}' — expected 'host:port' (e.g. 10.0.2.2:8888) or ':0' to clear"
            ));
        }
        self.run_checked(
            serial,
            args(["shell", "settings", "put", "global", "http_proxy", &value]),
            self.config.command_timeout,
            "settings put global http_proxy",
        )
        .await
        .map(drop)
    }

    // ===== push =====

    pub async fn push_file(
        &self,
        serial: &str,
        local_path: &Path,
        device_path: &str,
        scan_media: bool,
    ) -> R<String> {
        let local = local_path.display().to_string();
        let result = self
            .adb
            .run_text(
                Some(serial),
                args(["push", &local, device_path]),
                self.config.install_timeout,
            )
            .await;
        if result.timed_out {
            return Err(format!(
                "adb push timed out after {}s",
                self.config.install_timeout.as_secs()
            ));
        }
        if result.exit_code != 0 {
            return Err(format!("adb push failed: {}", result.stdout));
        }
        if scan_media {
            let uri = format!("file://{device_path}");
            self.run_checked(
                serial,
                args([
                    "shell",
                    "am",
                    "broadcast",
                    "-a",
                    "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                    "-d",
                    &uri,
                ]),
                self.config.command_timeout,
                "media scan broadcast",
            )
            .await?;
        }
        Ok(java_trim(&result.stdout).to_owned())
    }

    // ===== screen recording =====

    /// Records for `duration_ms` (screenrecord, max 180s) and pulls the MP4 to `dir/filename`.
    pub async fn record_screen(
        &self,
        serial: &str,
        duration_ms: i32,
        bit_rate_mbps: Option<i32>,
        dir: &Path,
        filename: &str,
    ) -> R<std::path::PathBuf> {
        let seconds = ((f64::from(duration_ms) / 1000.0).ceil() as i64).max(1);
        let mut a = args([
            "shell",
            "screenrecord",
            "--time-limit",
            &seconds.to_string(),
        ]);
        if let Some(mbps) = bit_rate_mbps.filter(|b| *b > 0) {
            a.push("--bit-rate".to_owned());
            a.push(mbps.wrapping_mul(1_000_000).to_string());
        }
        a.push(RECORD_DEVICE_PATH.to_owned());
        let record_timeout = Duration::from_secs(seconds.unsigned_abs() + 30);
        let record = self.adb.run_text(Some(serial), a, record_timeout).await;
        if record.timed_out {
            return Err(format!(
                "screenrecord timed out after {}s",
                record_timeout.as_secs()
            ));
        }
        if record.exit_code != 0 {
            return Err(format!(
                "screenrecord failed (exit {}): {}",
                record.exit_code, record.stdout
            ));
        }
        let host_path = dir.join(filename);
        let host_str = host_path.display().to_string();
        let pull = self
            .adb
            .run_text(
                Some(serial),
                args(["pull", RECORD_DEVICE_PATH, &host_str]),
                self.config.install_timeout,
            )
            .await;
        if pull.timed_out || pull.exit_code != 0 {
            return Err(format!(
                "adb pull of the recording failed: {}",
                if pull.timed_out {
                    "timed out"
                } else {
                    &pull.stdout
                }
            ));
        }
        if !host_path.is_file() {
            return Err(format!(
                "adb pull reported success but {host_str} is missing"
            ));
        }
        let rm = self
            .adb
            .run_text(
                Some(serial),
                args(["shell", "rm", "-f", RECORD_DEVICE_PATH]),
                self.config.command_timeout,
            )
            .await;
        if !rm.success() {
            tracing::warn!("failed to remove device-side recording file: {}", rm.stdout);
        }
        Ok(host_path)
    }

    // ===== pixel sampling =====

    /// Full-resolution PNG capture (no downscale) — coordinates match device pixels 1:1.
    pub async fn screenshot_raw(&self, serial: &str) -> R<RgbaImage> {
        let result = self
            .adb
            .run_binary(
                Some(serial),
                args(["exec-out", "screencap", "-p"]),
                self.config.command_timeout,
            )
            .await;
        if result.is_fail() || result.stdout.is_empty() {
            return Err(format!(
                "screencap failed: {}",
                if result.timed_out {
                    "timed out"
                } else {
                    &result.stderr
                }
            ));
        }
        match try_decode(&result.stdout) {
            Some(img) => Ok(img.to_rgba8()),
            None => Err(format!(
                "screencap returned invalid PNG data ({} bytes)",
                result.stdout.len()
            )),
        }
    }

    // ===== common =====

    async fn run_checked(
        &self,
        serial: &str,
        a: Vec<String>,
        timeout: Duration,
        what: &str,
    ) -> R<String> {
        let result = self.adb.run_text(Some(serial), a, timeout).await;
        if result.timed_out {
            return Err(format!("{what} timed out after {}s", timeout.as_secs()));
        }
        if result.exit_code != 0 {
            return Err(format!(
                "{what} failed (exit {}): {}",
                result.exit_code, result.stdout
            ));
        }
        // input commands sometimes print "Error: ..." with exit 0 on bad arguments
        if result.stdout.starts_with("Error") {
            return Err(format!("{what} failed: {}", result.stdout));
        }
        Ok(result.stdout)
    }
}

fn format_devices(devices: &[DeviceInfo]) -> String {
    if devices.is_empty() {
        "(none)".to_owned()
    } else {
        devices
            .iter()
            .map(DeviceInfo::describe)
            .collect::<Vec<_>>()
            .join(", ")
    }
}

#[cfg(test)]
mod tests {
    use image::{ImageFormat, Rgba};

    use super::super::adb::fake::{FakeAdb, bin_ok, ok};
    use super::*;
    use crate::process::ProcessResult;

    const SERIAL: &str = "emulator-5554";

    fn service(adb: &Arc<FakeAdb>) -> AndroidDeviceService {
        AndroidDeviceService::new(adb.clone(), AndroidConfig::default())
    }

    fn service_with_stdout(stdout: &str) -> (Arc<FakeAdb>, AndroidDeviceService) {
        let adb = Arc::new(FakeAdb::new());
        adb.on(None, "", vec![ok(stdout)]);
        let s = service(&adb);
        (adb, s)
    }

    fn png_bytes(w: u32, h: u32) -> Vec<u8> {
        let img = RgbaImage::from_pixel(w, h, Rgba([10, 20, 30, 255]));
        let mut out = Cursor::new(Vec::new());
        img.write_to(&mut out, ImageFormat::Png).unwrap();
        out.into_inner()
    }

    // ===== exit-0 false successes =====

    #[tokio::test]
    async fn launch_app_detects_missing_activity_despite_exit_zero() {
        let (_, s) = service_with_stdout(
            "Starting: Intent { cmp=com.example/.Missing }\nError type 3\nError: Activity class \
             {com.example/.Missing} does not exist.",
        );
        let err = s
            .launch_app(SERIAL, "com.example", Some(".Missing"))
            .await
            .unwrap_err();
        assert!(err.contains("am start failed"), "{err}");
    }

    #[tokio::test]
    async fn launch_app_accepts_successful_am_start() {
        let (adb, s) = service_with_stdout(
            "Starting: Intent { cmp=com.example/.Main }\nStatus: ok\nLaunchState: COLD\nTotalTime: 512",
        );
        assert!(
            s.launch_app(SERIAL, "com.example", Some(".Main"))
                .await
                .is_ok()
        );
        assert_eq!(
            adb.count(Some(SERIAL), "shell am start -W -n com.example/.Main"),
            1
        );
    }

    #[tokio::test]
    async fn launch_app_accepts_component_name_containing_error() {
        let (_, s) = service_with_stdout(
            "Starting: Intent { cmp=com.example/.ErrorReportActivity }\nStatus: ok\nTotalTime: 300",
        );
        assert!(
            s.launch_app(SERIAL, "com.example", Some(".ErrorReportActivity"))
                .await
                .is_ok()
        );
    }

    #[tokio::test]
    async fn launch_app_detects_monkey_abort_despite_exit_zero() {
        let (_, s) = service_with_stdout("** No activities found to run, monkey aborted.");
        let err = s
            .launch_app(SERIAL, "com.example.missing", None)
            .await
            .unwrap_err();
        assert!(err.contains("monkey launch failed"), "{err}");
    }

    #[tokio::test]
    async fn launch_app_accepts_successful_monkey_launch() {
        let (adb, s) = service_with_stdout("Events injected: 1\n## Network stats: ...");
        assert!(s.launch_app(SERIAL, "com.example", None).await.is_ok());
        assert_eq!(
            adb.count(
                Some(SERIAL),
                "shell monkey -p com.example -c android.intent.category.LAUNCHER 1"
            ),
            1
        );
    }

    #[tokio::test]
    async fn unicode_broadcast_accepts_normal_completion_and_rejects_am_errors() {
        let (adb, ok_svc) = service_with_stdout(
            "Broadcasting: Intent { act=ADB_INPUT_B64 }\nBroadcast completed: result=0",
        );
        assert!(
            ok_svc
                .input_unicode_via_keyboard(SERIAL, "한글")
                .await
                .is_ok()
        );
        assert_eq!(
            adb.count(
                Some(SERIAL),
                "shell am broadcast -a ADB_INPUT_B64 --es msg 7ZWc6riA"
            ),
            1
        );

        let (_, failing) = service_with_stdout(
            "Broadcasting: Intent { act=ADB_INPUT_B64 }\njava.lang.SecurityException: Permission Denial",
        );
        let err = failing
            .input_unicode_via_keyboard(SERIAL, "한글")
            .await
            .unwrap_err();
        assert!(err.contains("broadcast failed"), "{err}");
    }

    #[tokio::test]
    async fn list_devices_parses_serial_state_and_model() {
        let adb = Arc::new(FakeAdb::new());
        adb.on(
            None,
            "devices -l",
            vec![ok("List of devices attached\n\
                 emulator-5554\tdevice product:sdk_gphone64 model:sdk_gphone64_x86_64 device:emu64x\n\
                 R3CN30XXXX\tunauthorized usb:1-1\n")],
        );
        let devices = service(&adb).list_devices().await.unwrap();
        assert_eq!(devices.len(), 2);
        assert_eq!(devices[0].serial, "emulator-5554");
        assert_eq!(devices[0].state, "device");
        assert_eq!(devices[0].model, "sdk_gphone64_x86_64");
        assert_eq!(devices[1].state, "unauthorized");
        assert_eq!(adb.calls()[0].serial, None);
    }

    #[tokio::test]
    async fn resolve_device_reports_none_multiple_and_bad_state() {
        let adb = Arc::new(FakeAdb::new());
        adb.on(
            None,
            "devices -l",
            vec![
                ok("List of devices attached\n"),
                ok("List of devices attached\na\tdevice\nb\tdevice model:M\n"),
                ok("List of devices attached\nc\tunauthorized\n"),
            ],
        );
        let s = service(&adb);
        assert_eq!(
            s.resolve_device(None).await.unwrap_err(),
            "No device in 'device' state. Connected: (none) — start an emulator or connect a device with USB \
             debugging enabled."
        );
        assert_eq!(
            s.resolve_device(None).await.unwrap_err(),
            "Multiple devices connected; specify a serial. Ready: a (state=device), b (state=device, model=M)"
        );
        assert_eq!(
            s.resolve_device(Some("c")).await.unwrap_err(),
            "Device c is in state 'unauthorized' (unauthorized = approve the RSA debugging prompt on the device; \
             offline = reconnect)"
        );
        assert_eq!(
            s.resolve_device(Some("zz")).await.unwrap_err(),
            "Device not found: zz. Connected: c (state=unauthorized)"
        );
    }

    #[test]
    fn downscale_shrinks_longest_side_to_max_dimension() {
        let source = DynamicImage::ImageRgba8(RgbaImage::new(1080, 2400));
        let scaled = downscale(&source, 1280);
        assert_eq!(scaled.width(), 576);
        assert_eq!(scaled.height(), 1280);
    }

    #[test]
    fn downscale_keeps_small_rgb_image_size() {
        let source = DynamicImage::ImageRgb8(RgbImage::new(800, 600));
        let out = downscale(&source, 1280);
        assert_eq!((out.width(), out.height()), (800, 600));
    }

    #[test]
    fn downscale_converts_small_argb_image_to_rgb_over_black() {
        let mut rgba = RgbaImage::new(800, 600);
        rgba.put_pixel(0, 0, Rgba([200, 100, 50, 0]));
        rgba.put_pixel(1, 0, Rgba([200, 100, 50, 255]));
        let out = downscale(&DynamicImage::ImageRgba8(rgba), 1280);
        assert_eq!((out.width(), out.height()), (800, 600));
        assert_eq!(out.get_pixel(0, 0).0, [0, 0, 0]);
        assert_eq!(out.get_pixel(1, 0).0, [200, 100, 50]);
    }

    #[test]
    fn to_jpeg_produces_readable_jpeg_bytes() {
        let jpeg = to_jpeg(&RgbImage::new(100, 50), 80).unwrap();
        assert_eq!(&jpeg[..2], &[0xFF, 0xD8]);
        let decoded = image::load_from_memory(&jpeg).unwrap();
        assert_eq!((decoded.width(), decoded.height()), (100, 50));
    }

    #[test]
    fn scale_summary_formats() {
        let mut shot = ScreenshotResult {
            bytes: vec![],
            filename: "x".into(),
            mime_type: "image/jpeg",
            device_width: 1080,
            device_height: 2400,
            image_width: 576,
            image_height: 1280,
        };
        assert_eq!(
            shot.scale_summary(),
            "device 1080x2400, image 576x1280 (scale 0.533 — divide image coords by 0.533 for device tap coords)"
        );
        shot.image_width = 1080;
        shot.image_height = 2400;
        assert_eq!(shot.scale_summary(), "device 1080x2400, image 1:1");
    }

    #[test]
    fn java_fixed3_rounds_half_up_on_shortest_repr() {
        assert_eq!(java_fixed3(0.0625), "0.063");
        assert_eq!(java_fixed3(1.0005), "1.001");
        assert_eq!(java_fixed3(0.5), "0.500");
        assert_eq!(java_fixed3(0.9996), "1.000");
        assert_eq!(java_fixed3(0.5333333333), "0.533");
        assert_eq!(java_fixed3(12.0), "12.000");
    }

    // ===== network =====

    #[tokio::test]
    async fn set_network_conditions_rejects_physical_device() {
        let (_, s) = service_with_stdout("OK");
        let err = s
            .set_network_conditions("R3CN30XXXX", Some("3g"), None)
            .await
            .unwrap_err();
        assert!(err.contains("requires an emulator"), "{err}");
    }

    #[tokio::test]
    async fn set_network_conditions_requires_speed_or_delay() {
        let (_, s) = service_with_stdout("OK");
        let err = s
            .set_network_conditions(SERIAL, None, Some(" "))
            .await
            .unwrap_err();
        assert!(err.contains("specify speed and/or delay"), "{err}");
    }

    #[tokio::test]
    async fn set_network_conditions_detects_ko_console_response_despite_exit_zero() {
        let (_, s) = service_with_stdout("KO: bad network speed format");
        let err = s
            .set_network_conditions(SERIAL, Some("not-a-speed"), None)
            .await
            .unwrap_err();
        assert!(err.contains("rejected by emulator console"), "{err}");
    }

    #[tokio::test]
    async fn set_network_conditions_accepts_ok_console_response() {
        let (adb, s) = service_with_stdout("OK");
        assert!(
            s.set_network_conditions(SERIAL, Some("full"), Some("none"))
                .await
                .is_ok()
        );
        assert_eq!(adb.count(Some(SERIAL), "emu network speed full"), 1);
        assert_eq!(adb.count(Some(SERIAL), "emu network delay none"), 1);
    }

    #[tokio::test]
    async fn set_http_proxy_validates_host_port_and_clears_with_colon_zero() {
        let (adb, s) = service_with_stdout("");
        let err = s
            .set_http_proxy(SERIAL, Some("not a proxy"))
            .await
            .unwrap_err();
        assert!(err.contains("invalid proxy value"), "{err}");

        s.set_http_proxy(SERIAL, Some("10.0.2.2:8888"))
            .await
            .unwrap();
        assert_eq!(
            adb.count(
                Some(SERIAL),
                "shell settings put global http_proxy 10.0.2.2:8888"
            ),
            1
        );
        s.set_http_proxy(SERIAL, None).await.unwrap();
        assert_eq!(
            adb.count(Some(SERIAL), "shell settings put global http_proxy :0"),
            1
        );
    }

    #[tokio::test]
    async fn set_offline_toggles_both_wifi_and_data() {
        let (adb, s) = service_with_stdout("");
        s.set_offline(SERIAL, true).await.unwrap();
        assert_eq!(adb.count(Some(SERIAL), "shell svc wifi disable"), 1);
        assert_eq!(adb.count(Some(SERIAL), "shell svc data disable"), 1);
        s.set_offline(SERIAL, false).await.unwrap();
        assert_eq!(adb.count(Some(SERIAL), "shell svc wifi enable"), 1);
        assert_eq!(adb.count(Some(SERIAL), "shell svc data enable"), 1);
    }

    #[tokio::test]
    async fn set_offline_rejects_wireless_serial() {
        let (adb, s) = service_with_stdout("");
        let err = s.set_offline("192.168.0.10:5555", true).await.unwrap_err();
        assert!(err.starts_with(
            "android_set_offline is disabled over a wireless adb connection (192.168.0.10:5555)"
        ));
        assert!(adb.calls().is_empty());
        assert!(is_wireless_serial(Some("adb-XYZ._adb-tls-connect._tcp")));
        assert!(!is_wireless_serial(Some("emulator-5554")));
        assert!(!is_wireless_serial(Some("R3CN30XXXX")));
    }

    // ===== screen recording =====

    #[tokio::test]
    async fn record_screen_fails_when_pulled_file_missing() {
        let dir = tempfile::tempdir().unwrap();
        let (adb, s) = service_with_stdout("");
        let err = s
            .record_screen(SERIAL, 3_000, None, dir.path(), "rec.mp4")
            .await
            .unwrap_err();
        assert!(err.contains("is missing"), "{err}");
        let first = &adb.calls()[0];
        assert_eq!(
            first.args,
            args([
                "shell",
                "screenrecord",
                "--time-limit",
                "3",
                "/sdcard/namsic_record.mp4"
            ])
        );
        assert_eq!(first.timeout, Duration::from_secs(33));
    }

    // ===== screen power / misc =====

    #[tokio::test]
    async fn is_screen_on_reads_signals_in_priority_order() {
        for (out, expected) in [
            ("Display Power: state=OFF\nmWakefulness=Awake", false),
            ("Display Power: state=ON", true),
            ("Display Power: state=DOZE", false),
            ("mWakefulness=Asleep", false),
            ("mWakefulness=Awake", true),
            ("mScreenOn=false", false),
            ("nothing useful", true),
        ] {
            let (_, s) = service_with_stdout(out);
            assert_eq!(s.is_screen_on(SERIAL).await, expected, "{out}");
        }
    }

    #[tokio::test]
    async fn ensure_screen_on_sends_wakeup_only_when_off() {
        let (adb, s) = service_with_stdout("Display Power: state=OFF");
        s.ensure_screen_on(SERIAL).await.unwrap();
        assert_eq!(
            adb.count(Some(SERIAL), "shell input keyevent KEYCODE_WAKEUP"),
            1
        );
        let (adb, s) = service_with_stdout("Display Power: state=ON");
        s.ensure_screen_on(SERIAL).await.unwrap();
        assert_eq!(
            adb.count(Some(SERIAL), "shell input keyevent KEYCODE_WAKEUP"),
            0
        );
    }

    #[test]
    fn power_keys_and_keycodes() {
        assert!(is_power_key("KEYCODE_POWER"));
        assert!(is_power_key("26"));
        assert!(!is_power_key("KEYCODE_HOME"));
        assert_eq!(normalize_keycode(" home "), "KEYCODE_HOME");
    }

    #[tokio::test]
    async fn run_checked_reports_timeout_exit_and_error_output() {
        let adb = Arc::new(FakeAdb::new());
        adb.on(
            None,
            "",
            vec![
                ProcessResult {
                    exit_code: -1,
                    stdout: String::new(),
                    timed_out: true,
                },
                ProcessResult {
                    exit_code: 1,
                    stdout: "boom".into(),
                    timed_out: false,
                },
                ok("Error: bad args"),
            ],
        );
        let s = service(&adb);
        assert_eq!(
            s.tap(SERIAL, 1, 2).await.unwrap_err(),
            "input tap timed out after 20s"
        );
        assert_eq!(
            s.tap(SERIAL, 1, 2).await.unwrap_err(),
            "input tap failed (exit 1): boom"
        );
        assert_eq!(
            s.tap(SERIAL, 1, 2).await.unwrap_err(),
            "input tap failed: Error: bad args"
        );
    }

    #[tokio::test]
    async fn logcat_builds_filter_args_and_reports_empty() {
        let (adb, s) = service_with_stdout("");
        let out = s.logcat(SERIAL, " MyTag:D  *:S ", 50, false).await.unwrap();
        assert_eq!(out, "(no log entries matching ' MyTag:D  *:S ')");
        assert_eq!(
            adb.count(
                Some(SERIAL),
                "shell logcat -d -v threadtime -t 50 MyTag:D *:S"
            ),
            1
        );
    }

    #[tokio::test]
    async fn ensure_adb_keyboard_active_without_apk_fails_with_guidance() {
        let (_, s) = service_with_stdout("");
        let err = s.ensure_adb_keyboard_active(SERIAL).await.unwrap_err();
        assert!(
            err.starts_with("non-ASCII or special-character text requires the ADBKeyboard IME")
        );
    }

    #[tokio::test]
    async fn current_ime_maps_null_to_empty() {
        let (_, s) = service_with_stdout("null");
        assert_eq!(s.current_ime(SERIAL).await, "");
        let (_, s) = service_with_stdout("a.b/.Ime");
        assert_eq!(s.current_ime(SERIAL).await, "a.b/.Ime");
    }

    // ===== screenshots =====

    #[tokio::test]
    async fn screenshot_fast_path_writes_jpeg_and_reports_scale() {
        let dir = tempfile::tempdir().unwrap();
        let adb = Arc::new(FakeAdb::new());
        adb.on_binary(
            None,
            "exec-out screencap -p",
            vec![bin_ok(png_bytes(1080, 2400))],
        );
        let shot = service(&adb)
            .screenshot(SERIAL, dir.path(), "0001.jpg", true)
            .await
            .unwrap();
        assert_eq!(shot.mime_type, "image/jpeg");
        assert_eq!((shot.image_width, shot.image_height), (576, 1280));
        assert_eq!((shot.device_width, shot.device_height), (1080, 2400));
        assert_eq!(
            std::fs::read(dir.path().join("0001.jpg")).unwrap(),
            shot.bytes
        );
    }

    #[tokio::test]
    async fn screenshot_png_writes_original_bytes() {
        let dir = tempfile::tempdir().unwrap();
        let adb = Arc::new(FakeAdb::new());
        let png = png_bytes(20, 10);
        adb.on_binary(None, "exec-out screencap -p", vec![bin_ok(png.clone())]);
        let shot = service(&adb)
            .screenshot(SERIAL, dir.path(), "0001.png", false)
            .await
            .unwrap();
        assert_eq!(shot.bytes, png);
        assert_eq!(shot.scale_summary(), "device 20x10, image 1:1");
        assert_eq!(std::fs::read(dir.path().join("0001.png")).unwrap(), png);
    }

    #[tokio::test]
    async fn screenshot_falls_back_to_pull_and_remembers_device() {
        let dir = tempfile::tempdir().unwrap();
        let adb = Arc::new(FakeAdb::new());
        adb.on_binary(
            None,
            "exec-out screencap -p",
            vec![bin_ok(b"\r\n\x89PNG garbage".to_vec())],
        );
        let s = service(&adb);
        // The fake pull leaves the temp file empty -> undecodable after fallback.
        let err = s
            .screenshot(SERIAL, dir.path(), "0001.jpg", true)
            .await
            .unwrap_err();
        assert_eq!(
            err,
            "screencap returned undecodable image data (0 bytes, header=[]). If the foreground window is \
             FLAG_SECURE (DRM/banking/password manager), it cannot be captured."
        );
        assert_eq!(
            adb.count(Some(SERIAL), "shell screencap -p /sdcard/namsic_shot.png"),
            1
        );
        assert_eq!(
            adb.count(Some(SERIAL), "shell rm -f /sdcard/namsic_shot.png"),
            1
        );
        // Second call goes straight to the pull path.
        let _ = s.screenshot(SERIAL, dir.path(), "0002.jpg", true).await;
        assert_eq!(adb.count(Some(SERIAL), "exec-out screencap -p"), 1);
        assert_eq!(
            adb.count(Some(SERIAL), "shell screencap -p /sdcard/namsic_shot.png"),
            2
        );
    }

    #[test]
    fn hex_prefix_formats_first_eight_bytes() {
        assert_eq!(
            hex_prefix(&[0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0xff]),
            "89 50 4e 47 0d 0a 1a 0a"
        );
    }

    #[test]
    fn is_safe_ascii_text_distinguishes_input_paths() {
        assert!(is_safe_ascii_text("hello world 123 user@example.com"));
        assert!(!is_safe_ascii_text("한글"));
        assert!(!is_safe_ascii_text("quote\"inside"));
        assert!(!is_safe_ascii_text("semi;colon"));
    }
}
