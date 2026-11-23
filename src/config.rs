//! Server configuration.
//!
//! Only values that differ between machines/users are read from the environment
//! (optionally via `.env`); everything else is a code constant.

use std::env;
use std::path::PathBuf;
use std::time::Duration;

use anyhow::{Context, Result, bail};
use url::Url;

// Server name/version live in `server.rs` (`#[tool_handler]` needs literals).
pub const MCP_PATH: &str = "/mcp";
pub const MCP_SESSION_MAX_IDLE: Duration = Duration::from_secs(3 * 60 * 60);

const DEFAULT_PORT: u16 = 8060;
const DEFAULT_LLM_BASE_URL: &str = "http://127.0.0.1:9000";
const DEFAULT_LLM_MODEL: &str = "qwen3.8-27b";

#[derive(Debug, Clone)]
pub struct Config {
    pub port: u16,
    pub session: SessionConfig,
    pub android: AndroidConfig,
    pub chrome: ChromeConfig,
    pub local_llm: LocalLlmConfig,
}

#[derive(Debug, Clone)]
pub struct SessionConfig {
    pub screenshot_base_dir: PathBuf,
}

#[derive(Debug, Clone)]
pub struct AndroidConfig {
    pub adb_path: String,
    pub command_timeout: Duration,
    pub install_timeout: Duration,
    pub dump_timeout: Duration,
    /// Host path of ADBKeyboard.apk; `None` means non-ASCII input is unavailable.
    pub adb_keyboard_apk: Option<String>,
    pub max_ui_nodes: usize,
    pub screenshot_max_dimension: u32,
}

#[derive(Debug, Clone)]
pub struct ChromeConfig {
    pub powershell_path: String,
    pub command_timeout: Duration,
    pub startup_timeout: Duration,
}

#[derive(Debug, Clone)]
pub struct LocalLlmConfig {
    /// Loopback HTTP origin without trailing slash, e.g. `http://127.0.0.1:9000`.
    pub base_url: String,
    pub model: String,
    pub request_timeout: Duration,
    pub queue_capacity: usize,
    pub max_retained_jobs: usize,
    pub retention: Duration,
    pub image_roots: Vec<PathBuf>,
    pub memory_directory: PathBuf,
}

impl Default for AndroidConfig {
    fn default() -> Self {
        Self {
            adb_path: "adb".to_owned(),
            command_timeout: Duration::from_secs(20),
            install_timeout: Duration::from_secs(120),
            dump_timeout: Duration::from_secs(15),
            adb_keyboard_apk: None,
            max_ui_nodes: 200,
            screenshot_max_dimension: 1280,
        }
    }
}

impl Default for ChromeConfig {
    fn default() -> Self {
        Self {
            // Windows PowerShell 5.1 ships with Windows and includes UIAutomationClient.
            powershell_path: "powershell".to_owned(),
            command_timeout: Duration::from_secs(30),
            // The first command also pays for Add-Type C# compilation + UIA assembly load.
            startup_timeout: Duration::from_secs(60),
        }
    }
}

impl LocalLlmConfig {
    pub fn new(base_url: &str, model: &str, image_roots: Vec<PathBuf>) -> Result<Self> {
        Ok(Self {
            base_url: validate_loopback_origin(base_url)?,
            model: model.to_owned(),
            request_timeout: Duration::from_secs(3 * 60),
            queue_capacity: 8,
            max_retained_jobs: 100,
            retention: Duration::from_secs(30 * 60),
            image_roots,
            memory_directory: data_dir().join("llm-memory"),
        })
    }
}

impl Config {
    /// Loads `.env` (if present) and the process environment.
    pub fn from_env() -> Result<Self> {
        let _ = dotenvy::dotenv();

        let port = match env_opt("SERVER_PORT") {
            Some(v) => v
                .parse()
                .with_context(|| format!("SERVER_PORT is not a port: {v}"))?,
            None => DEFAULT_PORT,
        };

        let mut android = AndroidConfig::default();
        if let Some(v) = env_opt("ADB_PATH") {
            android.adb_path = v;
        }
        android.adb_keyboard_apk = env_opt("ADB_KEYBOARD_APK");

        let image_roots = env_opt("LOCAL_LLM_IMAGE_ROOTS")
            .map(|v| {
                v.split(';')
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .map(PathBuf::from)
                    .collect()
            })
            .unwrap_or_default();
        let local_llm = LocalLlmConfig::new(
            &env_opt("LOCAL_LLM_BASE_URL").unwrap_or_else(|| DEFAULT_LLM_BASE_URL.to_owned()),
            &env_opt("LOCAL_LLM_MODEL").unwrap_or_else(|| DEFAULT_LLM_MODEL.to_owned()),
            image_roots,
        )?;

        Ok(Self {
            port,
            session: SessionConfig {
                screenshot_base_dir: data_dir().join("screenshots"),
            },
            android,
            chrome: ChromeConfig::default(),
            local_llm,
        })
    }
}

/// `~/.namsic-mcp`
pub fn data_dir() -> PathBuf {
    let home = env::var_os("USERPROFILE")
        .or_else(|| env::var_os("HOME"))
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."));
    home.join(".namsic-mcp")
}

fn env_opt(key: &str) -> Option<String> {
    env::var(key)
        .ok()
        .map(|v| v.trim().to_owned())
        .filter(|v| !v.is_empty())
}

/// Accepts only a loopback HTTP origin (no userinfo/query/fragment/path) and
/// returns it without a trailing slash.
pub fn validate_loopback_origin(raw: &str) -> Result<String> {
    const ERR: &str = "LOCAL_LLM_BASE_URL must be a loopback HTTP origin";
    let url = Url::parse(raw).context(ERR)?;
    let host_ok = matches!(
        url.host_str(),
        Some("127.0.0.1") | Some("localhost") | Some("[::1]") | Some("::1")
    );
    if url.scheme() != "http"
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || !(url.path().is_empty() || url.path() == "/")
        || !host_ok
    {
        bail!(ERR);
    }
    Ok(url.as_str().trim_end_matches('/').to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_loopback_origins() {
        assert_eq!(
            validate_loopback_origin("http://127.0.0.1:9000").unwrap(),
            "http://127.0.0.1:9000"
        );
        assert_eq!(
            validate_loopback_origin("http://localhost:9000/").unwrap(),
            "http://localhost:9000"
        );
        assert!(validate_loopback_origin("http://[::1]:9000").is_ok());
    }

    #[test]
    fn rejects_remote_or_decorated_origins() {
        for bad in [
            "https://example.com",
            "https://127.0.0.1:9000",
            "http://example.com:9000",
            "http://user@127.0.0.1:9000",
            "http://127.0.0.1:9000/v1",
            "http://127.0.0.1:9000/?a=1",
            "http://127.0.0.1:9000/#x",
        ] {
            assert!(validate_loopback_origin(bad).is_err(), "{bad}");
        }
    }
}
