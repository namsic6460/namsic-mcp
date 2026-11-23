//! Runs chrome-uia-host.ps1 as a resident PowerShell process and exchanges
//! one-line commands / multi-line replies with it.
//!
//! The process is kept alive because Add-Type C# compilation plus the UIAutomation
//! assembly load costs over 1.5 s per call. Every exchange is serialized behind one
//! async mutex: the protocol is "1 command line -> N reply lines -> END", so
//! interleaved calls would steal each other's replies.

use std::future::Future;
use std::io;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::process::Stdio;
use std::sync::Mutex as StdMutex;
use std::time::Duration;

use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, ChildStdout};
use tokio::sync::Mutex;

use super::ChromeHost;
use crate::config::ChromeConfig;
use crate::error::ToolError;
use crate::process::command;

const SCRIPT: &[u8] = include_bytes!("chrome-uia-host.ps1");
const SCRIPT_FILE_NAME: &str = "chrome-uia-host.ps1";
const END_MARKER: &str = "<<<END>>>";
const UTF8_BOM: [u8; 3] = [0xEF, 0xBB, 0xBF];
const STOP_GRACE: Duration = Duration::from_secs(2);

struct Streams {
    writer: ChildStdin,
    reader: BufReader<ChildStdout>,
}

pub struct ChromeUiaHost {
    config: ChromeConfig,
    /// Extracted script; the temp dir is kept alive (and removed on drop) with it.
    script: StdMutex<Option<(tempfile::TempDir, PathBuf)>>,
    /// Process lifecycle, independent of the exchange lock so a timeout can kill it.
    child: StdMutex<Option<Child>>,
    /// Serializes exchanges; holds the pipes of the current process.
    streams: Mutex<Option<Streams>>,
}

impl ChromeUiaHost {
    pub fn new(config: ChromeConfig) -> Self {
        Self {
            config,
            script: StdMutex::new(None),
            child: StdMutex::new(None),
            streams: Mutex::new(None),
        }
    }

    async fn command(&self, line: String) -> Result<Vec<String>, ToolError> {
        let mut streams = self.streams.lock().await;
        // On a cold start, also wait for Add-Type compilation so the first call does not time out.
        let await_ms = self.config.command_timeout.as_millis()
            + if self.is_running() {
                0
            } else {
                self.config.startup_timeout.as_millis()
            };
        let budget = Duration::from_millis(u64::try_from(await_ms).unwrap_or(u64::MAX));

        match tokio::time::timeout(budget, self.exchange(&mut streams, &line)).await {
            Ok(result) => result,
            Err(_) => {
                *streams = None;
                self.stop_process().await;
                Err(ToolError::state(format!(
                    "Chrome UIA host timed out after {await_ms} ms"
                )))
            }
        }
    }

    async fn exchange(
        &self,
        slot: &mut Option<Streams>,
        line: &str,
    ) -> Result<Vec<String>, ToolError> {
        self.ensure_started(slot).await?;
        let Some(streams) = slot.as_mut() else {
            return Err(ToolError::state(
                "Chrome UIA host communication failed: Chrome UIA host was stopped",
            ));
        };
        let result = async {
            write_line(&mut streams.writer, line).await?;
            read_until_end(&mut streams.reader).await
        }
        .await;
        match result {
            Ok(lines) => Ok(lines),
            Err(e) => {
                *slot = None;
                self.stop_process().await;
                Err(ToolError::state(format!(
                    "Chrome UIA host communication failed: {e}"
                )))
            }
        }
    }

    async fn ensure_started(&self, slot: &mut Option<Streams>) -> Result<(), ToolError> {
        if self.is_running() && slot.is_some() {
            return Ok(());
        }
        *slot = None;
        self.stop_process().await;

        let script = self.ensure_script_extracted()?;
        let powershell = &self.config.powershell_path;
        let args = [
            "-NoProfile".to_owned(),
            "-NonInteractive".to_owned(),
            "-ExecutionPolicy".to_owned(),
            "Bypass".to_owned(),
            "-File".to_owned(),
            script.display().to_string(),
        ];
        tracing::info!(
            "Starting Chrome UIA host: [{}, {}]",
            powershell,
            args.join(", ")
        );

        let spawned = command(powershell)
            .args(&args)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn();
        let mut child = spawned.map_err(|e| {
            ToolError::state(format!(
                "Failed to start PowerShell ('{powershell}'). Chrome tab control is Windows-only and \
                 needs PowerShell on PATH. Reason: {e}"
            ))
        })?;
        let (Some(writer), Some(stdout), Some(stderr)) =
            (child.stdin.take(), child.stdout.take(), child.stderr.take())
        else {
            let _ = child.start_kill();
            return Err(ToolError::state(
                "Chrome UIA host failed to start: process pipes are unavailable",
            ));
        };
        drain_stderr(stderr);
        self.lock_child().replace(child);

        // Commands are all ASCII: PowerShell decodes piped stdin with the OEM code page.
        let mut streams = Streams {
            writer,
            reader: BufReader::new(stdout),
        };
        if let Err(e) = handshake(&mut streams).await {
            self.stop_process().await;
            return Err(ToolError::state(format!(
                "Chrome UIA host failed to start: {e}"
            )));
        }
        *slot = Some(streams);
        Ok(())
    }

    /// Extracts the embedded script to a temp file (re-extracting if it vanished).
    /// Windows PowerShell 5.1 reads BOM-less files as ANSI, which breaks the Korean
    /// comments, so a UTF-8 BOM is prepended.
    fn ensure_script_extracted(&self) -> Result<PathBuf, ToolError> {
        let mut cached = self
            .script
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some((_, path)) = cached.as_ref()
            && std::fs::File::open(path).is_ok()
        {
            return Ok(path.clone());
        }
        let extracted = extract_script().map_err(|e| {
            ToolError::state(format!("Failed to extract the Chrome UIA host script: {e}"))
        })?;
        let path = extracted.1.clone();
        *cached = Some(extracted);
        Ok(path)
    }

    fn lock_child(&self) -> std::sync::MutexGuard<'_, Option<Child>> {
        self.child
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn is_running(&self) -> bool {
        self.lock_child()
            .as_mut()
            .is_some_and(|c| matches!(c.try_wait(), Ok(None)))
    }

    async fn stop_process(&self) {
        let taken = self.lock_child().take();
        let Some(mut child) = taken else {
            return;
        };
        let _ = child.start_kill();
        if tokio::time::timeout(STOP_GRACE, child.wait())
            .await
            .is_err()
        {
            let _ = child.start_kill();
        }
    }

    pub async fn close(&self) {
        if let Ok(mut streams) = self.streams.try_lock() {
            if self.is_running()
                && let Some(s) = streams.as_mut()
                && let Err(e) = write_line(&mut s.writer, "EXIT").await
            {
                tracing::debug!("Chrome UIA host EXIT write failed: {e}");
            }
            *streams = None;
        }
        self.stop_process().await;
    }
}

impl ChromeHost for ChromeUiaHost {
    fn command<'a>(
        &'a self,
        line: String,
    ) -> Pin<Box<dyn Future<Output = Result<Vec<String>, ToolError>> + Send + 'a>> {
        Box::pin(self.command(line))
    }

    fn shutdown<'a>(&'a self) -> Pin<Box<dyn Future<Output = ()> + Send + 'a>> {
        Box::pin(self.close())
    }
}

fn extract_script() -> io::Result<(tempfile::TempDir, PathBuf)> {
    let dir = tempfile::Builder::new()
        .prefix("namsic-chrome-uia")
        .tempdir()?;
    let file = dir.path().join(SCRIPT_FILE_NAME);
    write_script(&file)?;
    Ok((dir, file))
}

fn write_script(file: &Path) -> io::Result<()> {
    let mut body = Vec::with_capacity(SCRIPT.len() + UTF8_BOM.len());
    if !SCRIPT.starts_with(&UTF8_BOM) {
        body.extend_from_slice(&UTF8_BOM);
    }
    body.extend_from_slice(SCRIPT);
    std::fs::write(file, body)
}

/// PING/PONG confirms startup and pays the Add-Type cost up front.
async fn handshake(streams: &mut Streams) -> io::Result<()> {
    write_line(&mut streams.writer, "PING").await?;
    let reply = read_until_end(&mut streams.reader).await?;
    if reply.iter().any(|l| l.trim() == "PONG") {
        Ok(())
    } else {
        Err(io::Error::other(format!(
            "unexpected handshake reply: [{}]",
            reply.join(", ")
        )))
    }
}

async fn write_line(writer: &mut ChildStdin, line: &str) -> io::Result<()> {
    let mut bytes = Vec::with_capacity(line.len() + 1);
    // Mirror Java's US-ASCII encoder: unmappable characters become '?'.
    bytes.extend(
        line.chars()
            .map(|c| if c.is_ascii() { c as u8 } else { b'?' }),
    );
    bytes.push(b'\n');
    writer.write_all(&bytes).await?;
    writer.flush().await
}

async fn read_until_end(reader: &mut BufReader<ChildStdout>) -> io::Result<Vec<String>> {
    let mut lines = Vec::new();
    let mut buf = Vec::new();
    loop {
        buf.clear();
        if reader.read_until(b'\n', &mut buf).await? == 0 {
            return Err(io::Error::other("Chrome UIA host exited unexpectedly"));
        }
        if buf.last() == Some(&b'\n') {
            buf.pop();
        }
        if buf.last() == Some(&b'\r') {
            buf.pop();
        }
        let line = String::from_utf8_lossy(&buf).into_owned();
        if line.trim() == END_MARKER {
            return Ok(lines);
        }
        lines.push(line);
    }
}

fn drain_stderr(stderr: tokio::process::ChildStderr) {
    tokio::spawn(async move {
        let mut reader = BufReader::new(stderr);
        let mut buf = Vec::new();
        loop {
            buf.clear();
            match reader.read_until(b'\n', &mut buf).await {
                Ok(0) => break,
                Ok(_) => {
                    let line = String::from_utf8_lossy(&buf);
                    let line = line.trim_end_matches(['\r', '\n']);
                    if !line.trim().is_empty() {
                        tracing::warn!("chrome-uia-host stderr: {line}");
                    }
                }
                Err(e) => {
                    tracing::debug!("chrome-uia-host stderr reader ended: {e}");
                    break;
                }
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracted_script_has_bom_and_original_body() {
        let (dir, path) = extract_script().unwrap();
        let written = std::fs::read(&path).unwrap();
        assert!(written.starts_with(&UTF8_BOM));
        assert_eq!(&written[3..], SCRIPT);
        assert!(path.starts_with(dir.path()));
        assert_eq!(
            Some(SCRIPT_FILE_NAME),
            path.file_name().and_then(|n| n.to_str())
        );
    }

    #[test]
    fn re_extracts_when_the_script_file_vanished() {
        let host = ChromeUiaHost::new(ChromeConfig::default());
        let first = host.ensure_script_extracted().unwrap();
        assert_eq!(first, host.ensure_script_extracted().unwrap());
        std::fs::remove_file(&first).unwrap();
        let second = host.ensure_script_extracted().unwrap();
        assert!(second.is_file());
    }

    #[tokio::test]
    async fn missing_powershell_reports_start_failure() {
        let host = ChromeUiaHost::new(ChromeConfig {
            powershell_path: "definitely-not-a-real-powershell-xyz".to_owned(),
            ..ChromeConfig::default()
        });
        let err = host.command("LIST".to_owned()).await.unwrap_err();
        assert!(
            err.message().starts_with(
                "Failed to start PowerShell ('definitely-not-a-real-powershell-xyz'). Chrome tab \
                 control is Windows-only and needs PowerShell on PATH. Reason: "
            ),
            "{err}"
        );
        host.close().await;
    }
}
