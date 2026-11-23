//! Chrome tab activation and native-dialog MCP tools (Windows UI Automation).
//!
//! Dialogs: while an alert/confirm is open the page's JS is blocked, so the
//! automation extension's CDP calls time out until someone presses a button; the
//! extension cannot, so the button is pressed at the OS level like tab activation.
//!
//! Tabs: a background tab is `visibilityState === 'hidden'`, which freezes
//! requestAnimationFrame and throttles timers to ~1Hz, so canvas/WebGL/animated
//! UI is captured frozen. The extension cannot bring a tab to the front, so the
//! tab strip is selected directly at the OS level.

mod dialog_index;
mod tab_index;
mod uia_host;

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use rmcp::handler::server::wrapper::Parameters;
use rmcp::{schemars, tool, tool_router};
use serde::Deserialize;

use self::dialog_index::{ChromeDialog, ChromeDialogIndex, DialogButton};
use self::tab_index::{ChromeTab, ChromeTabIndex};
use self::uia_host::ChromeUiaHost;
use crate::config::ChromeConfig;
use crate::error::ToolError;
use crate::server::NamsicServer;
use crate::session::{ProjectSessionRegistry, SESSION_PARAM_DESC};

const OK_PREFIX: &str = "OK\t";
const ERR_PREFIX: &str = "ERR\t";
const ACTION_ACCEPT: &str = "accept";
const ACTION_CANCEL: &str = "cancel";

type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

/// Transport to the Chrome UIA host: one command line in, reply lines out.
/// Behind a trait so the tool logic can be tested with a fake host.
pub trait ChromeHost: Send + Sync {
    fn command<'a>(&'a self, line: String) -> BoxFuture<'a, Result<Vec<String>, ToolError>>;

    fn shutdown<'a>(&'a self) -> BoxFuture<'a, ()>;
}

/// W·T records of every running Chrome window/tab.
async fn list_tabs(host: &dyn ChromeHost) -> Result<Vec<String>, ToolError> {
    host.command("LIST".to_owned()).await
}

/// Selects the tabIndex-th tab of a window; a minimized window is restored first.
async fn activate_tab(
    host: &dyn ChromeHost,
    hwnd: i64,
    tab_index: i32,
    bring_to_front: bool,
) -> Result<Vec<String>, ToolError> {
    let front = if bring_to_front { '1' } else { '0' };
    host.command(format!("ACTIVATE\t{hwnd}\t{tab_index}\t{front}"))
        .await
}

/// D·X·B records of native dialogs open in Chrome windows.
async fn list_dialogs(host: &dyn ChromeHost) -> Result<Vec<String>, ToolError> {
    host.command("DIALOGS".to_owned()).await
}

/// Presses the buttonIndex-th button of the dialogIndex-th dialog of a window.
async fn click_dialog_button(
    host: &dyn ChromeHost,
    hwnd: i64,
    dialog_index: i32,
    button_index: i32,
) -> Result<Vec<String>, ToolError> {
    host.command(format!(
        "DIALOG_CLICK\t{hwnd}\t{dialog_index}\t{button_index}"
    ))
    .await
}

pub struct ChromeTools {
    registry: Arc<ProjectSessionRegistry>,
    host: Arc<dyn ChromeHost>,
}

impl ChromeTools {
    pub fn new(registry: Arc<ProjectSessionRegistry>, config: ChromeConfig) -> Self {
        Self::with_host(registry, Arc::new(ChromeUiaHost::new(config)))
    }

    pub fn with_host(registry: Arc<ProjectSessionRegistry>, host: Arc<dyn ChromeHost>) -> Self {
        Self { registry, host }
    }

    pub async fn shutdown(&self) {
        self.host.shutdown().await;
    }

    pub async fn chrome_list_tabs(&self, session_id: Option<&str>) -> String {
        tracing::info!(
            "MCP tool invoked: chrome_list_tabs sessionId={}",
            disp(session_id)
        );
        if let Err(e) = self.registry.require(session_id) {
            return format!("Error: {e}");
        }
        match list_tabs(self.host.as_ref()).await {
            Ok(lines) => ChromeTabIndex::parse(&lines).render(),
            Err(e) => format!("Error listing Chrome tabs: {e}"),
        }
    }

    pub async fn chrome_activate_tab(
        &self,
        session_id: Option<&str>,
        title: Option<&str>,
        hwnd: Option<i64>,
        tab_index: Option<i32>,
        bring_to_front: Option<bool>,
    ) -> String {
        tracing::info!(
            "MCP tool invoked: chrome_activate_tab title={} hwnd={} tabIndex={} sessionId={}",
            disp(title),
            disp(hwnd),
            disp(tab_index),
            disp(session_id)
        );
        if let Err(e) = self.registry.require(session_id) {
            return format!("Error: {e}");
        }

        let to_front = bring_to_front != Some(false);
        match self.activate_inner(title, hwnd, tab_index, to_front).await {
            Ok(text) => text,
            Err(e) => format!("Error activating Chrome tab: {e}"),
        }
    }

    async fn activate_inner(
        &self,
        title: Option<&str>,
        hwnd: Option<i64>,
        tab_index: Option<i32>,
        to_front: bool,
    ) -> Result<String, ToolError> {
        if let (Some(hwnd), Some(tab_index)) = (hwnd, tab_index) {
            return self.activate(hwnd, tab_index, to_front).await;
        }
        let Some(title) = title.filter(|t| !t.trim().is_empty()) else {
            return Ok(
                "Error: specify title, or both hwnd and tabIndex (get them from chrome_list_tabs)."
                    .to_owned(),
            );
        };

        let index = ChromeTabIndex::parse(&list_tabs(self.host.as_ref()).await?);
        let matches = index.match_title(Some(title));
        match matches.as_slice() {
            [] => Ok(format!(
                "No Chrome tab matches \"{title}\".\n\n{}",
                index.render()
            )),
            [target] => self.activate(target.hwnd, target.index, to_front).await,
            _ => Ok(ambiguous_message(title, &matches)),
        }
    }

    pub async fn chrome_list_dialogs(&self, session_id: Option<&str>) -> String {
        tracing::info!(
            "MCP tool invoked: chrome_list_dialogs sessionId={}",
            disp(session_id)
        );
        if let Err(e) = self.registry.require(session_id) {
            return format!("Error: {e}");
        }
        match list_dialogs(self.host.as_ref()).await {
            Ok(lines) => ChromeDialogIndex::parse(&lines).render(),
            Err(e) => format!("Error listing Chrome dialogs: {e}"),
        }
    }

    pub async fn chrome_dismiss_dialog(
        &self,
        session_id: Option<&str>,
        action: Option<&str>,
        button: Option<&str>,
        hwnd: Option<i64>,
        dialog_index: Option<i32>,
    ) -> String {
        tracing::info!(
            "MCP tool invoked: chrome_dismiss_dialog action={} button={} hwnd={} dialogIndex={} sessionId={}",
            disp(action),
            disp(button),
            disp(hwnd),
            disp(dialog_index),
            disp(session_id)
        );
        if let Err(e) = self.registry.require(session_id) {
            return format!("Error: {e}");
        }

        let button = button.filter(|b| !b.trim().is_empty());
        let action = java_trim(action.unwrap_or(""));
        let accept = action.eq_ignore_ascii_case(ACTION_ACCEPT);
        let cancel = action.eq_ignore_ascii_case(ACTION_CANCEL);
        if button.is_none() && !accept && !cancel {
            return "Error: specify action ('accept' or 'cancel'), or button (a label from chrome_list_dialogs)."
                .to_owned();
        }

        match self.dismiss_inner(button, accept, hwnd, dialog_index).await {
            Ok(text) => text,
            Err(e) => format!("Error dismissing Chrome dialog: {e}"),
        }
    }

    async fn dismiss_inner(
        &self,
        button: Option<&str>,
        accept: bool,
        hwnd: Option<i64>,
        dialog_index: Option<i32>,
    ) -> Result<String, ToolError> {
        let index = ChromeDialogIndex::parse(&list_dialogs(self.host.as_ref()).await?);
        let candidates = index.select(hwnd, dialog_index);
        let dialog = match candidates.as_slice() {
            [] => {
                return Ok(if index.dialogs.is_empty() {
                    index.render()
                } else {
                    format!(
                        "No Chrome dialog at hwnd={} dialogIndex={}.\n\n{}",
                        disp(hwnd),
                        disp(dialog_index),
                        index.render()
                    )
                });
            }
            [dialog] => *dialog,
            _ => {
                return Ok(format!(
                    "{} dialogs are open. Pass hwnd + dialogIndex to pick one:\n\n{}",
                    candidates.len(),
                    index.render()
                ));
            }
        };

        let target = if let Some(button) = button {
            let matches = dialog.match_buttons(Some(button));
            match matches.as_slice() {
                [only] => *only,
                _ => {
                    return Ok(format!(
                        "{}{button}\" in this dialog:\n{}",
                        if matches.is_empty() {
                            "No button matches \""
                        } else {
                            "Several buttons match \""
                        },
                        dialog.describe()
                    ));
                }
            }
        } else {
            let chosen = if accept {
                dialog.accept_button()
            } else {
                dialog.cancel_button()
            };
            let Some(chosen) = chosen else {
                return Ok(format!(
                    "Error dismissing Chrome dialog: the dialog has no buttons:\n{}",
                    dialog.describe()
                ));
            };
            chosen
        };
        self.dismiss(dialog, target).await
    }

    async fn dismiss(
        &self,
        dialog: &ChromeDialog,
        button: &DialogButton,
    ) -> Result<String, ToolError> {
        let reply =
            click_dialog_button(self.host.as_ref(), dialog.hwnd, dialog.index, button.index)
                .await?;
        for line in reply {
            if let Some(pressed) = line.strip_prefix(OK_PREFIX) {
                let texts = if dialog.texts.is_empty() {
                    String::new()
                } else {
                    format!(" ({})", dialog.texts.join(" / "))
                };
                return Ok(format!(
                    "Pressed '{pressed}' on dialog \"{}\"{texts}.\n\
                     The page is unblocked — retry the browser call that was hanging.",
                    dialog.title
                ));
            }
            if let Some(err) = line.strip_prefix(ERR_PREFIX) {
                return Ok(format!("Error dismissing Chrome dialog: {err}"));
            }
        }
        Ok("Error dismissing Chrome dialog: unexpected reply from the Chrome UIA host.".to_owned())
    }

    async fn activate(
        &self,
        hwnd: i64,
        tab_index: i32,
        bring_to_front: bool,
    ) -> Result<String, ToolError> {
        let reply = activate_tab(self.host.as_ref(), hwnd, tab_index, bring_to_front).await?;
        for line in reply {
            if let Some(name) = line.strip_prefix(OK_PREFIX) {
                return Ok(format!(
                    "Activated tab: {name}\n\
                     It is now visible — requestAnimationFrame and timers run at full speed."
                ));
            }
            if let Some(err) = line.strip_prefix(ERR_PREFIX) {
                return Ok(format!("Error activating Chrome tab: {err}"));
            }
        }
        Ok("Error activating Chrome tab: unexpected reply from the Chrome UIA host.".to_owned())
    }
}

fn ambiguous_message(title: &str, matches: &[&ChromeTab]) -> String {
    let mut sb = format!(
        "\"{title}\" matches {} tabs. Narrow the title, or pass hwnd + tabIndex:\n",
        matches.len()
    );
    for tab in matches {
        sb.push_str(&format!(
            "  hwnd={} tabIndex={}  {}\n",
            tab.hwnd, tab.index, tab.name
        ));
    }
    sb
}

/// Renders an optional value the way Java string concatenation renders a nullable one.
fn disp<T: std::fmt::Display>(value: Option<T>) -> String {
    value.map_or_else(|| "null".to_owned(), |v| v.to_string())
}

/// Java `String.trim()`: strips leading/trailing chars `<= U+0020`.
pub(crate) fn java_trim(s: &str) -> &str {
    s.trim_matches(|c: char| c <= ' ')
}

/// Java `Long.valueOf(text.trim())`, `None` on failure.
pub(crate) fn parse_i64(text: &str) -> Option<i64> {
    java_trim(text).parse().ok()
}

/// Java `Integer.valueOf(text.trim())`, `None` on failure.
pub(crate) fn parse_i32(text: &str) -> Option<i32> {
    java_trim(text).parse().ok()
}

// ===== MCP parameter schemas =====

#[derive(Debug, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct ChromeListTabsParams {
    #[schemars(required, with = "String", description = SESSION_PARAM_DESC)]
    pub session_id: Option<String>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct ChromeActivateTabParams {
    #[schemars(required, with = "String", description = SESSION_PARAM_DESC)]
    pub session_id: Option<String>,
    #[schemars(
        description = "Tab title to match against the tab's accessibility name, case-insensitive. Exact match wins, then prefix, then substring. Optional when both hwnd and tabIndex are given."
    )]
    pub title: Option<String>,
    #[schemars(
        description = "Window handle from chrome_list_tabs. Use with tabIndex to address a tab exactly, bypassing title matching."
    )]
    pub hwnd: Option<i64>,
    #[schemars(description = "Tab index within the window, from chrome_list_tabs. Use with hwnd.")]
    pub tab_index: Option<i32>,
    #[schemars(
        description = "Also raise the Chrome window to the foreground, which takes OS focus. Default: true. Set false to switch the tab within its window without stealing focus — less intrusive, but a fully covered Chrome window may still be treated as hidden by Chrome's occlusion tracking."
    )]
    pub bring_to_front: Option<bool>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct ChromeListDialogsParams {
    #[schemars(required, with = "String", description = SESSION_PARAM_DESC)]
    pub session_id: Option<String>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct ChromeDismissDialogParams {
    #[schemars(required, with = "String", description = SESSION_PARAM_DESC)]
    pub session_id: Option<String>,
    #[schemars(description = "'accept' or 'cancel'. Required unless `button` is given.")]
    pub action: Option<String>,
    #[schemars(
        description = "Button label to press, case-insensitive, as listed by chrome_list_dialogs. Overrides `action`."
    )]
    pub button: Option<String>,
    #[schemars(
        description = "Window handle from chrome_list_dialogs. Only needed when several dialogs are open."
    )]
    pub hwnd: Option<i64>,
    #[schemars(
        description = "Dialog index within the window, from chrome_list_dialogs. Only needed when several dialogs are open."
    )]
    pub dialog_index: Option<i32>,
}

#[tool_router(router = chrome_router, vis = "pub(crate)")]
impl NamsicServer {
    #[tool(
        name = "chrome_list_tabs",
        description = "List every tab of every Chrome window running on this machine, with its window handle (hwnd), tab index, and which tab is currently active. Use it to find the tab you want before calling chrome_activate_tab. Tab names are Chrome's accessibility labels — the page title followed by Chrome's own suffixes (tab-group name, memory usage) — so match against the page-title part. Windows-only: this reads the Chrome tab strip through Windows UI Automation."
    )]
    async fn chrome_list_tabs_tool(
        &self,
        Parameters(p): Parameters<ChromeListTabsParams>,
    ) -> String {
        self.state
            .chrome
            .chrome_list_tabs(p.session_id.as_deref())
            .await
    }

    #[tool(
        name = "chrome_activate_tab",
        description = "Bring a Chrome tab to the front: select it in its window's tab strip, restoring the window first if it is minimized. WHY THIS IS NEEDED: a tab that is not the active tab of its window reports document.visibilityState='hidden', which freezes requestAnimationFrame entirely and throttles setTimeout/setInterval to about 1Hz. Screenshots of such a tab still succeed but show a stale frame, so canvas, WebGL, chart and animated UI look frozen or unrendered, and code that waits on timers appears to do nothing. Static pages are unaffected. Call this before screenshotting or driving any page whose rendering depends on animation frames, and whenever a page seems stuck. Target the tab with `title`, or address it exactly with `hwnd` + `tabIndex` from chrome_list_tabs. If several tabs share a title, make the target unique first by setting document.title to a marker in that tab, then match the marker. Windows-only (Windows UI Automation)."
    )]
    async fn chrome_activate_tab_tool(
        &self,
        Parameters(p): Parameters<ChromeActivateTabParams>,
    ) -> String {
        self.state
            .chrome
            .chrome_activate_tab(
                p.session_id.as_deref(),
                p.title.as_deref(),
                p.hwnd,
                p.tab_index,
                p.bring_to_front,
            )
            .await
    }

    #[tool(
        name = "chrome_list_dialogs",
        description = "List the native dialogs currently open in Chrome windows — JavaScript alert/confirm/prompt, 'Leave site?' (beforeunload) and other Chrome confirmation dialogs — with their message text and buttons. WHY THIS IS NEEDED: while such a dialog is open the page's JavaScript is blocked, so browser automation calls on that tab (click, screenshot, script, close) hang until they time out and keep failing until the dialog is answered; the automation extension itself cannot answer it. Call this when a browser tool times out or reports an unresponsive renderer, then answer the dialog with chrome_dismiss_dialog. A dialog raised by a background tab is not shown (and not listed) until that tab is selected — use chrome_activate_tab on the tab first. Non-blocking bubbles (translate, save password) are not listed. Windows-only (Windows UI Automation)."
    )]
    async fn chrome_list_dialogs_tool(
        &self,
        Parameters(p): Parameters<ChromeListDialogsParams>,
    ) -> String {
        self.state
            .chrome
            .chrome_list_dialogs(p.session_id.as_deref())
            .await
    }

    #[tool(
        name = "chrome_dismiss_dialog",
        description = "Answer a native Chrome dialog (JavaScript alert/confirm/prompt, 'Leave site?' and similar) by pressing one of its buttons, which unblocks the page and lets hung browser automation calls work again. Choose the button with `action` — 'accept' presses the first button (OK / Leave), 'cancel' the last (Cancel; for a one-button alert that is the OK button) — or name it exactly with `button` as shown by chrome_list_dialogs. Decide deliberately: accepting a confirm() lets the page carry out whatever it asked about. With a single open dialog no address is needed; if several are open, pass `hwnd` and `dialogIndex` from chrome_list_dialogs. If no dialog is found but a browser tool is hanging, the dialog belongs to a background tab: select that tab with chrome_activate_tab and retry. Windows-only (Windows UI Automation)."
    )]
    async fn chrome_dismiss_dialog_tool(
        &self,
        Parameters(p): Parameters<ChromeDismissDialogParams>,
    ) -> String {
        self.state
            .chrome
            .chrome_dismiss_dialog(
                p.session_id.as_deref(),
                p.action.as_deref(),
                p.button.as_deref(),
                p.hwnd,
                p.dialog_index,
            )
            .await
    }
}

#[cfg(test)]
mod tests;
