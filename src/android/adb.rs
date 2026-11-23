//! Single gateway for adb invocations: adb path, `-s <serial>` assembly and timeouts.
//!
//! Text commands go through [`process::run_with_timeout`]; binary commands
//! (screencap, `exec-out cat`) keep raw stdout bytes so PNGs are not mangled.

use std::future::Future;
use std::pin::Pin;
use std::time::Duration;

use crate::process::{self, BinaryResult, ProcessResult};

pub type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

/// adb execution seam so the device service can run against a fake in tests.
pub trait AdbRunner: Send + Sync {
    /// Text-output adb command. `serial == None` runs without `-s` (e.g. `adb devices`).
    fn run_text<'a>(
        &'a self,
        serial: Option<&'a str>,
        args: Vec<String>,
        timeout: Duration,
    ) -> BoxFuture<'a, ProcessResult>;

    /// Binary-output adb command (e.g. `exec-out screencap -p`).
    fn run_binary<'a>(
        &'a self,
        serial: Option<&'a str>,
        args: Vec<String>,
        timeout: Duration,
    ) -> BoxFuture<'a, BinaryResult>;
}

/// Real adb runner spawning child processes (killed on timeout or drop).
pub struct ProcessAdbRunner {
    adb_path: String,
}

impl ProcessAdbRunner {
    pub fn new(adb_path: impl Into<String>) -> Self {
        Self {
            adb_path: adb_path.into(),
        }
    }

    pub fn build_command(&self, serial: Option<&str>, args: Vec<String>) -> Vec<String> {
        let mut command = Vec::with_capacity(args.len() + 3);
        command.push(self.adb_path.clone());
        if let Some(serial) = serial.filter(|s| !s.trim().is_empty()) {
            command.push("-s".to_owned());
            command.push(serial.to_owned());
        }
        command.extend(args);
        command
    }
}

impl AdbRunner for ProcessAdbRunner {
    fn run_text<'a>(
        &'a self,
        serial: Option<&'a str>,
        args: Vec<String>,
        timeout: Duration,
    ) -> BoxFuture<'a, ProcessResult> {
        let command = self.build_command(serial, args);
        Box::pin(async move { process::run_with_timeout(&command, timeout).await })
    }

    fn run_binary<'a>(
        &'a self,
        serial: Option<&'a str>,
        args: Vec<String>,
        timeout: Duration,
    ) -> BoxFuture<'a, BinaryResult> {
        let command = self.build_command(serial, args);
        Box::pin(async move { process::run_binary(&command, timeout).await })
    }
}

/// `["a", "b"]` -> `vec!["a".to_owned(), "b".to_owned()]`.
pub fn args<const N: usize>(items: [&str; N]) -> Vec<String> {
    items.iter().map(|s| (*s).to_owned()).collect()
}

#[cfg(test)]
pub mod fake {
    //! Scripted adb fake: rules match on (optional serial, args prefix), newest
    //! rule first; the last queued result of a rule is sticky. Unmatched calls succeed with "".

    use std::collections::VecDeque;
    use std::sync::Mutex;

    use super::*;

    struct Rule<T> {
        serial: Option<String>,
        prefix: Vec<String>,
        results: VecDeque<T>,
    }

    #[derive(Debug, Clone, PartialEq, Eq)]
    pub struct Call {
        pub serial: Option<String>,
        pub args: Vec<String>,
        pub timeout: Duration,
        pub binary: bool,
    }

    #[derive(Default)]
    pub struct FakeAdb {
        text_rules: Mutex<Vec<Rule<ProcessResult>>>,
        binary_rules: Mutex<Vec<Rule<BinaryResult>>>,
        calls: Mutex<Vec<Call>>,
    }

    pub fn ok(stdout: &str) -> ProcessResult {
        ProcessResult {
            exit_code: 0,
            stdout: stdout.to_owned(),
            timed_out: false,
        }
    }

    pub fn bin_ok(stdout: Vec<u8>) -> BinaryResult {
        BinaryResult {
            exit_code: 0,
            stdout,
            stderr: String::new(),
            timed_out: false,
        }
    }

    fn split(prefix: &str) -> Vec<String> {
        prefix.split_whitespace().map(str::to_owned).collect()
    }

    fn take<T: Clone>(rules: &mut [Rule<T>], serial: Option<&str>, args: &[String]) -> Option<T> {
        let rule = rules.iter_mut().find(|r| {
            r.serial.as_deref().is_none_or(|s| Some(s) == serial) && args.starts_with(&r.prefix)
        })?;
        if rule.results.len() > 1 {
            rule.results.pop_front()
        } else {
            rule.results.front().cloned()
        }
    }

    impl FakeAdb {
        pub fn new() -> Self {
            Self::default()
        }

        /// Registers text results for calls whose args start with the whitespace-split `prefix`.
        pub fn on(&self, serial: Option<&str>, prefix: &str, results: Vec<ProcessResult>) {
            self.text_rules.lock().unwrap().insert(
                0,
                Rule {
                    serial: serial.map(str::to_owned),
                    prefix: split(prefix),
                    results: results.into(),
                },
            );
        }

        pub fn on_binary(&self, serial: Option<&str>, prefix: &str, results: Vec<BinaryResult>) {
            self.binary_rules.lock().unwrap().insert(
                0,
                Rule {
                    serial: serial.map(str::to_owned),
                    prefix: split(prefix),
                    results: results.into(),
                },
            );
        }

        pub fn calls(&self) -> Vec<Call> {
            self.calls.lock().unwrap().clone()
        }

        /// Number of recorded calls whose args equal `args` exactly (whitespace-split).
        pub fn count(&self, serial: Option<&str>, args: &str) -> usize {
            let expected = split(args);
            self.calls()
                .iter()
                .filter(|c| c.serial.as_deref() == serial && c.args == expected)
                .count()
        }

        fn record(&self, serial: Option<&str>, args: &[String], timeout: Duration, binary: bool) {
            self.calls.lock().unwrap().push(Call {
                serial: serial.map(str::to_owned),
                args: args.to_vec(),
                timeout,
                binary,
            });
        }
    }

    impl AdbRunner for FakeAdb {
        fn run_text<'a>(
            &'a self,
            serial: Option<&'a str>,
            args: Vec<String>,
            timeout: Duration,
        ) -> BoxFuture<'a, ProcessResult> {
            self.record(serial, &args, timeout, false);
            let result =
                take(&mut self.text_rules.lock().unwrap(), serial, &args).unwrap_or_else(|| ok(""));
            Box::pin(async move { result })
        }

        fn run_binary<'a>(
            &'a self,
            serial: Option<&'a str>,
            args: Vec<String>,
            timeout: Duration,
        ) -> BoxFuture<'a, BinaryResult> {
            self.record(serial, &args, timeout, true);
            let result = take(&mut self.binary_rules.lock().unwrap(), serial, &args)
                .unwrap_or_else(|| bin_ok(Vec::new()));
            Box::pin(async move { result })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn build_command_adds_serial_only_when_present() {
        let runner = ProcessAdbRunner::new("adb");
        assert_eq!(
            runner.build_command(None, args(["devices", "-l"])),
            args(["adb", "devices", "-l"])
        );
        assert_eq!(
            runner.build_command(Some(" "), args(["devices"])),
            args(["adb", "devices"])
        );
        assert_eq!(
            runner.build_command(Some("emulator-5554"), args(["shell", "ls"])),
            args(["adb", "-s", "emulator-5554", "shell", "ls"])
        );
    }
}
