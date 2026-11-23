//! Child-process helpers with timeouts.
//!
//! stdout/stderr are drained concurrently while waiting, so large outputs never
//! stall the child on a full pipe. On timeout the child is killed.

use std::process::Stdio;
use std::time::Duration;

use tokio::io::AsyncReadExt;
use tokio::process::Command;

/// Text result: stderr merged after stdout, decoded as UTF-8 (lossy) and trimmed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProcessResult {
    pub exit_code: i32,
    pub stdout: String,
    pub timed_out: bool,
}

impl ProcessResult {
    pub fn success(&self) -> bool {
        !self.timed_out && self.exit_code == 0
    }
}

/// Binary result: stdout and stderr kept separate as raw bytes.
#[derive(Debug, Clone, Default)]
pub struct BinaryResult {
    pub exit_code: i32,
    pub stdout: Vec<u8>,
    pub stderr: String,
    pub timed_out: bool,
}

impl BinaryResult {
    pub fn is_fail(&self) -> bool {
        self.timed_out || self.exit_code != 0
    }
}

/// Builds a `Command` that never pops a console window on Windows.
pub fn command(program: &str) -> Command {
    let mut cmd = Command::new(program);
    #[cfg(windows)]
    {
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd.kill_on_drop(true);
    cmd
}

pub async fn run_with_timeout(argv: &[String], timeout: Duration) -> ProcessResult {
    let bin = run_binary(argv, timeout).await;
    if bin.exit_code == -1 && !bin.timed_out && bin.stdout.is_empty() && bin.stderr.is_empty() {
        return ProcessResult {
            exit_code: -1,
            stdout: String::new(),
            timed_out: false,
        };
    }
    let mut out = String::from_utf8_lossy(&bin.stdout).into_owned();
    if !bin.stderr.is_empty() {
        if !out.is_empty() && !out.ends_with('\n') {
            out.push('\n');
        }
        out.push_str(&bin.stderr);
    }
    ProcessResult {
        exit_code: bin.exit_code,
        stdout: out.trim().to_owned(),
        timed_out: bin.timed_out,
    }
}

pub async fn run_binary(argv: &[String], timeout: Duration) -> BinaryResult {
    let Some((program, args)) = argv.split_first() else {
        return BinaryResult {
            exit_code: -1,
            ..Default::default()
        };
    };
    let mut cmd = command(program);
    cmd.args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            tracing::warn!("Failed to start process: cmd={argv:?}, reason={e}");
            return BinaryResult {
                exit_code: -1,
                ..Default::default()
            };
        }
    };

    let mut stdout = child.stdout.take().expect("piped stdout");
    let mut stderr = child.stderr.take().expect("piped stderr");
    let out_task = tokio::spawn(async move {
        let mut buf = Vec::new();
        let _ = stdout.read_to_end(&mut buf).await;
        buf
    });
    let err_task = tokio::spawn(async move {
        let mut buf = Vec::new();
        let _ = stderr.read_to_end(&mut buf).await;
        buf
    });

    let (exit_code, timed_out) = match tokio::time::timeout(timeout, child.wait()).await {
        Ok(Ok(status)) => (status.code().unwrap_or(-1), false),
        Ok(Err(_)) => (-1, false),
        Err(_) => {
            let _ = child.kill().await;
            (-1, true)
        }
    };

    // After exit (or kill) the pipes close; bound the wait in case a grandchild holds them.
    let grace = Duration::from_secs(2);
    let stdout = tokio::time::timeout(grace, out_task)
        .await
        .ok()
        .and_then(Result::ok)
        .unwrap_or_default();
    let stderr = tokio::time::timeout(grace, err_task)
        .await
        .ok()
        .and_then(Result::ok)
        .unwrap_or_default();

    BinaryResult {
        exit_code,
        stdout,
        stderr: String::from_utf8_lossy(&stderr).trim().to_owned(),
        timed_out,
    }
}
