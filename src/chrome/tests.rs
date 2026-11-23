//! Tool-layer tests with a scripted fake host (ports ChromeMcpToolsTest), plus
//! `#[ignore]`d integration tests against the real Chrome (ports
//! ChromeUiaHostIntegrationTest; run with `cargo test -- --ignored chrome`).

use std::collections::{HashMap, VecDeque};
use std::sync::Mutex;

use super::*;

const UNKNOWN_SID: &str = "00000000-0000-0000-0000-000000000000";

type Reply = Result<Vec<String>, ToolError>;

/// Scripted host: replies are queued per command line; every call is recorded.
#[derive(Default)]
struct FakeHost {
    replies: Mutex<HashMap<String, VecDeque<Reply>>>,
    calls: Mutex<Vec<String>>,
}

impl FakeHost {
    fn on(&self, line: &str, reply: Reply) {
        self.replies
            .lock()
            .unwrap()
            .entry(line.to_owned())
            .or_default()
            .push_back(reply);
    }

    fn on_ok(&self, line: &str, lines: &[&str]) {
        self.on(line, Ok(lines.iter().map(|s| (*s).to_owned()).collect()));
    }

    fn calls(&self) -> Vec<String> {
        self.calls.lock().unwrap().clone()
    }

    fn count(&self, prefix: &str) -> usize {
        self.calls()
            .iter()
            .filter(|c| c.starts_with(prefix))
            .count()
    }
}

impl ChromeHost for FakeHost {
    fn command<'a>(&'a self, line: String) -> BoxFuture<'a, Result<Vec<String>, ToolError>> {
        self.calls.lock().unwrap().push(line.clone());
        let mut replies = self.replies.lock().unwrap();
        let reply = match replies.get_mut(&line) {
            // The last scripted reply repeats, like Mockito's thenReturn chain.
            Some(queue) if queue.len() > 1 => queue.pop_front().unwrap(),
            Some(queue) if queue.len() == 1 => queue[0].clone(),
            _ => Ok(Vec::new()),
        };
        Box::pin(async move { reply })
    }

    fn shutdown<'a>(&'a self) -> BoxFuture<'a, ()> {
        Box::pin(async {})
    }
}

struct Fixture {
    _dir: tempfile::TempDir,
    host: Arc<FakeHost>,
    tools: ChromeTools,
    sid: String,
}

fn fixture() -> Fixture {
    let dir = tempfile::tempdir().unwrap();
    let registry = Arc::new(ProjectSessionRegistry::new(dir.path()));
    let sid = registry
        .init(Some("chrome-test"))
        .unwrap()
        .session_id
        .clone();
    let host = Arc::new(FakeHost::default());
    let tools = ChromeTools::with_host(registry, host.clone());
    Fixture {
        _dir: dir,
        host,
        tools,
        sid,
    }
}

const TWO_TABS: &[&str] = &[
    "W\t132886\tNormal\tExample Domain - Chrome",
    "T\t132886\t0\t1\tDreamMakerClient - 메모리 사용량 - 238MB",
    "T\t132886\t1\t0\tExample Domain - Claude 그룹에 속함",
];

// ===== session gate =====

#[tokio::test]
async fn list_tabs_requires_known_session() {
    let f = fixture();
    let result = f.tools.chrome_list_tabs(Some(UNKNOWN_SID)).await;

    assert!(result.starts_with("Error: Unknown sessionId"), "{result}");
    assert_eq!(0, f.host.count("LIST"));
}

#[tokio::test]
async fn activate_tab_requires_known_session() {
    let f = fixture();
    let result = f
        .tools
        .chrome_activate_tab(Some(UNKNOWN_SID), Some("Example Domain"), None, None, None)
        .await;

    assert!(result.starts_with("Error: Unknown sessionId"), "{result}");
    assert_eq!(0, f.host.count("LIST"));
}

#[tokio::test]
async fn missing_session_id_reports_required() {
    let f = fixture();
    let result = f.tools.chrome_list_tabs(None).await;

    assert_eq!(
        "Error: sessionId is required. Call project_init first to obtain one.",
        result
    );
}

// ===== listing =====

#[tokio::test]
async fn list_tabs_renders_host_output() {
    let f = fixture();
    f.host.on_ok("LIST", TWO_TABS);

    let result = f.tools.chrome_list_tabs(Some(&f.sid)).await;

    assert!(result.contains("Chrome windows (1):"), "{result}");
    assert!(result.contains("[0] * DreamMakerClient"), "{result}");
    assert!(result.contains("[1]   Example Domain"), "{result}");
}

#[tokio::test]
async fn list_tabs_reports_host_failure() {
    let f = fixture();
    f.host.on(
        "LIST",
        Err(ToolError::state("Chrome UIA host timed out after 90000 ms")),
    );

    let result = f.tools.chrome_list_tabs(Some(&f.sid)).await;

    assert!(
        result.starts_with("Error listing Chrome tabs: "),
        "{result}"
    );
    assert!(result.contains("timed out"), "{result}");
}

// ===== title-matched activation =====

#[tokio::test]
async fn activates_unique_title_match_and_brings_window_to_front_by_default() {
    let f = fixture();
    f.host.on_ok("LIST", TWO_TABS);
    f.host.on_ok(
        "ACTIVATE\t132886\t1\t1",
        &["OK\tExample Domain - Claude 그룹에 속함"],
    );

    let result = f
        .tools
        .chrome_activate_tab(Some(&f.sid), Some("Example Domain"), None, None, None)
        .await;

    assert_eq!(
        "Activated tab: Example Domain - Claude 그룹에 속함\n\
         It is now visible — requestAnimationFrame and timers run at full speed.",
        result
    );
    assert_eq!(1, f.host.count("ACTIVATE\t132886\t1\t1"));
}

#[tokio::test]
async fn honours_bring_to_front_false() {
    let f = fixture();
    f.host.on_ok("LIST", TWO_TABS);
    f.host
        .on_ok("ACTIVATE\t132886\t1\t0", &["OK\tExample Domain"]);

    f.tools
        .chrome_activate_tab(
            Some(&f.sid),
            Some("Example Domain"),
            None,
            None,
            Some(false),
        )
        .await;

    assert_eq!(1, f.host.count("ACTIVATE\t132886\t1\t0"));
}

#[tokio::test]
async fn reports_ambiguous_title_without_activating() {
    let f = fixture();
    f.host.on_ok(
        "LIST",
        &[
            "W\t1\tNormal\tw1",
            "T\t1\t0\t0\tDashboard - memory 10MB",
            "W\t2\tNormal\tw2",
            "T\t2\t3\t0\tDashboard - memory 30MB",
        ],
    );

    let result = f
        .tools
        .chrome_activate_tab(Some(&f.sid), Some("Dashboard"), None, None, None)
        .await;

    assert_eq!(
        "\"Dashboard\" matches 2 tabs. Narrow the title, or pass hwnd + tabIndex:\n\
         \x20 hwnd=1 tabIndex=0  Dashboard - memory 10MB\n\
         \x20 hwnd=2 tabIndex=3  Dashboard - memory 30MB\n",
        result
    );
    assert_eq!(0, f.host.count("ACTIVATE"));
}

#[tokio::test]
async fn reports_no_match_with_full_tab_listing() {
    let f = fixture();
    f.host.on_ok("LIST", TWO_TABS);

    let result = f
        .tools
        .chrome_activate_tab(Some(&f.sid), Some("Nonexistent"), None, None, None)
        .await;

    assert!(
        result.starts_with("No Chrome tab matches \"Nonexistent\".\n\nChrome windows (1):"),
        "{result}"
    );
    assert_eq!(0, f.host.count("ACTIVATE"));
}

#[tokio::test]
async fn requires_title_or_explicit_target() {
    let f = fixture();
    let result = f
        .tools
        .chrome_activate_tab(Some(&f.sid), Some("  "), None, None, None)
        .await;

    assert_eq!(
        "Error: specify title, or both hwnd and tabIndex (get them from chrome_list_tabs).",
        result
    );
    assert!(f.host.calls().is_empty());
}

#[tokio::test]
async fn partial_explicit_target_falls_back_to_title_requirement() {
    let f = fixture();
    let result = f
        .tools
        .chrome_activate_tab(Some(&f.sid), None, Some(132886), None, None)
        .await;

    assert!(
        result.starts_with("Error: specify title, or both hwnd and tabIndex"),
        "{result}"
    );
    assert!(f.host.calls().is_empty());
}

// ===== explicit-address activation =====

#[tokio::test]
async fn explicit_hwnd_and_index_skip_title_lookup() {
    let f = fixture();
    f.host.on_ok("ACTIVATE\t4242\t2\t1", &["OK\tSome Tab"]);

    let result = f
        .tools
        .chrome_activate_tab(Some(&f.sid), None, Some(4242), Some(2), None)
        .await;

    assert!(result.starts_with("Activated tab: Some Tab"), "{result}");
    assert_eq!(vec!["ACTIVATE\t4242\t2\t1".to_owned()], f.host.calls());
}

#[tokio::test]
async fn explicit_target_wins_over_title() {
    let f = fixture();
    f.host.on_ok("ACTIVATE\t4242\t2\t1", &["OK\tSome Tab"]);

    f.tools
        .chrome_activate_tab(
            Some(&f.sid),
            Some("Example Domain"),
            Some(4242),
            Some(2),
            None,
        )
        .await;

    assert_eq!(vec!["ACTIVATE\t4242\t2\t1".to_owned()], f.host.calls());
}

// ===== host error propagation =====

#[tokio::test]
async fn surfaces_host_error_record() {
    let f = fixture();
    f.host.on_ok(
        "ACTIVATE\t9\t0\t1",
        &["ERR\ttab index 0 out of range (window has 0 tabs)"],
    );

    let result = f
        .tools
        .chrome_activate_tab(Some(&f.sid), None, Some(9), Some(0), None)
        .await;

    assert_eq!(
        "Error activating Chrome tab: tab index 0 out of range (window has 0 tabs)",
        result
    );
}

#[tokio::test]
async fn surfaces_unexpected_host_reply() {
    let f = fixture();
    f.host.on_ok("ACTIVATE\t9\t0\t1", &[]);

    let result = f
        .tools
        .chrome_activate_tab(Some(&f.sid), None, Some(9), Some(0), None)
        .await;

    assert_eq!(
        "Error activating Chrome tab: unexpected reply from the Chrome UIA host.",
        result
    );
}

#[tokio::test]
async fn surfaces_host_exception() {
    let f = fixture();
    f.host.on(
        "ACTIVATE\t9\t0\t1",
        Err(ToolError::state(
            "Failed to start PowerShell ('powershell')",
        )),
    );

    let result = f
        .tools
        .chrome_activate_tab(Some(&f.sid), None, Some(9), Some(0), None)
        .await;

    assert!(
        result.starts_with("Error activating Chrome tab: Failed to start PowerShell"),
        "{result}"
    );
    assert!(!result.contains("Activated"), "{result}");
}

// ===== dialogs =====

const CONFIRM_DIALOG: &[&str] = &[
    "D\t655724\t0\texample.com 내용:",
    "X\t655724\t0\t정말 창을 닫으시겠습니까?",
    "B\t655724\t0\t0\t1\t확인",
    "B\t655724\t0\t1\t1\t취소",
];

const TWO_DIALOGS: &[&str] = &[
    "D\t1\t0\tfirst",
    "B\t1\t0\t0\t1\tOK",
    "D\t2\t0\tsecond",
    "B\t2\t0\t0\t1\tLeave",
    "B\t2\t0\t1\t1\tCancel",
];

#[tokio::test]
async fn dialog_tools_require_known_session() {
    let f = fixture();
    let listed = f.tools.chrome_list_dialogs(Some(UNKNOWN_SID)).await;
    let dismissed = f
        .tools
        .chrome_dismiss_dialog(Some(UNKNOWN_SID), Some("accept"), None, None, None)
        .await;

    assert!(listed.starts_with("Error: Unknown sessionId"), "{listed}");
    assert!(
        dismissed.starts_with("Error: Unknown sessionId"),
        "{dismissed}"
    );
    assert_eq!(0, f.host.count("DIALOGS"));
}

#[tokio::test]
async fn list_dialogs_renders_host_output() {
    let f = fixture();
    f.host.on_ok("DIALOGS", CONFIRM_DIALOG);

    let result = f.tools.chrome_list_dialogs(Some(&f.sid)).await;

    assert!(result.contains("Chrome dialogs (1):"), "{result}");
    assert!(result.contains("buttons: [확인] [취소]"), "{result}");
}

#[tokio::test]
async fn list_dialogs_reports_host_failure() {
    let f = fixture();
    f.host.on(
        "DIALOGS",
        Err(ToolError::state("Chrome UIA host timed out")),
    );

    let result = f.tools.chrome_list_dialogs(Some(&f.sid)).await;

    assert_eq!(
        "Error listing Chrome dialogs: Chrome UIA host timed out",
        result
    );
}

#[tokio::test]
async fn accept_presses_first_button_of_the_only_dialog() {
    let f = fixture();
    f.host.on_ok("DIALOGS", CONFIRM_DIALOG);
    f.host.on_ok("DIALOG_CLICK\t655724\t0\t0", &["OK\t확인"]);

    let result = f
        .tools
        .chrome_dismiss_dialog(Some(&f.sid), Some("Accept"), None, None, None)
        .await;

    assert_eq!(
        "Pressed '확인' on dialog \"example.com 내용:\" (정말 창을 닫으시겠습니까?).\n\
         The page is unblocked — retry the browser call that was hanging.",
        result
    );
    assert_eq!(1, f.host.count("DIALOG_CLICK\t655724\t0\t0"));
}

#[tokio::test]
async fn cancel_presses_last_button() {
    let f = fixture();
    f.host.on_ok("DIALOGS", CONFIRM_DIALOG);
    f.host.on_ok("DIALOG_CLICK\t655724\t0\t1", &["OK\t취소"]);

    let result = f
        .tools
        .chrome_dismiss_dialog(Some(&f.sid), Some("cancel"), None, None, None)
        .await;

    assert!(result.starts_with("Pressed '취소'"), "{result}");
}

#[tokio::test]
async fn button_name_overrides_action() {
    let f = fixture();
    f.host.on_ok("DIALOGS", CONFIRM_DIALOG);
    f.host.on_ok("DIALOG_CLICK\t655724\t0\t1", &["OK\t취소"]);

    f.tools
        .chrome_dismiss_dialog(Some(&f.sid), Some("accept"), Some("취소"), None, None)
        .await;

    assert_eq!(1, f.host.count("DIALOG_CLICK\t655724\t0\t1"));
    assert_eq!(1, f.host.count("DIALOG_CLICK"));
}

#[tokio::test]
async fn unknown_button_name_lists_the_dialog_without_pressing() {
    let f = fixture();
    f.host.on_ok("DIALOGS", CONFIRM_DIALOG);

    let result = f
        .tools
        .chrome_dismiss_dialog(Some(&f.sid), None, Some("Leave"), None, None)
        .await;

    assert_eq!(
        "No button matches \"Leave\" in this dialog:\n\
         - hwnd=655724 dialogIndex=0 \"example.com 내용:\"\n\
         \x20   정말 창을 닫으시겠습니까?\n\
         \x20   buttons: [확인] [취소]\n",
        result
    );
    assert_eq!(0, f.host.count("DIALOG_CLICK"));
}

#[tokio::test]
async fn several_matching_buttons_list_the_dialog_without_pressing() {
    let f = fixture();
    f.host.on_ok("DIALOGS", TWO_DIALOGS);

    let result = f
        .tools
        .chrome_dismiss_dialog(Some(&f.sid), None, Some("e"), Some(2), None)
        .await;

    assert!(
        result.starts_with("Several buttons match \"e\" in this dialog:\n- hwnd=2"),
        "{result}"
    );
    assert_eq!(0, f.host.count("DIALOG_CLICK"));
}

#[tokio::test]
async fn dismiss_requires_action_or_button() {
    let f = fixture();
    let missing = f
        .tools
        .chrome_dismiss_dialog(Some(&f.sid), None, Some(" "), None, None)
        .await;
    let invalid = f
        .tools
        .chrome_dismiss_dialog(Some(&f.sid), Some("yes"), None, None, None)
        .await;

    assert_eq!(
        "Error: specify action ('accept' or 'cancel'), or button (a label from chrome_list_dialogs).",
        missing
    );
    assert!(invalid.starts_with("Error: specify action"), "{invalid}");
    assert!(f.host.calls().is_empty());
}

#[tokio::test]
async fn dismiss_with_no_dialog_explains_background_tabs() {
    let f = fixture();
    f.host.on_ok("DIALOGS", &[]);

    let result = f
        .tools
        .chrome_dismiss_dialog(Some(&f.sid), Some("accept"), None, None, None)
        .await;

    assert!(result.starts_with("No Chrome dialog is open."), "{result}");
    assert!(result.contains("chrome_activate_tab"), "{result}");
    assert_eq!(0, f.host.count("DIALOG_CLICK"));
}

#[tokio::test]
async fn several_dialogs_need_an_address() {
    let f = fixture();
    f.host.on_ok("DIALOGS", TWO_DIALOGS);

    let result = f
        .tools
        .chrome_dismiss_dialog(Some(&f.sid), Some("accept"), None, None, None)
        .await;

    assert!(
        result.starts_with(
            "2 dialogs are open. Pass hwnd + dialogIndex to pick one:\n\nChrome dialogs (2):"
        ),
        "{result}"
    );
    assert_eq!(0, f.host.count("DIALOG_CLICK"));
}

#[tokio::test]
async fn address_picks_one_of_several_dialogs() {
    let f = fixture();
    f.host.on_ok("DIALOGS", TWO_DIALOGS);
    f.host.on_ok("DIALOG_CLICK\t2\t0\t1", &["OK\tCancel"]);

    let result = f
        .tools
        .chrome_dismiss_dialog(Some(&f.sid), Some("cancel"), None, Some(2), Some(0))
        .await;

    assert_eq!(
        "Pressed 'Cancel' on dialog \"second\".\n\
         The page is unblocked — retry the browser call that was hanging.",
        result
    );
}

#[tokio::test]
async fn unknown_address_lists_open_dialogs() {
    let f = fixture();
    f.host.on_ok("DIALOGS", TWO_DIALOGS);

    let result = f
        .tools
        .chrome_dismiss_dialog(Some(&f.sid), Some("accept"), None, Some(9), Some(0))
        .await;

    assert!(
        result.starts_with("No Chrome dialog at hwnd=9 dialogIndex=0.\n\nChrome dialogs (2):"),
        "{result}"
    );
}

#[tokio::test]
async fn unknown_address_prints_null_for_missing_filter() {
    let f = fixture();
    f.host.on_ok("DIALOGS", TWO_DIALOGS);

    let result = f
        .tools
        .chrome_dismiss_dialog(Some(&f.sid), Some("accept"), None, Some(9), None)
        .await;

    assert!(
        result.starts_with("No Chrome dialog at hwnd=9 dialogIndex=null."),
        "{result}"
    );
}

#[tokio::test]
async fn dismiss_surfaces_host_error_record_and_exception() {
    let f = fixture();
    f.host.on_ok("DIALOGS", CONFIRM_DIALOG);
    f.host.on_ok(
        "DIALOG_CLICK\t655724\t0\t0",
        &["ERR\tdialog index 0 out of range (window has 0 dialogs)"],
    );
    f.host.on(
        "DIALOG_CLICK\t655724\t0\t0",
        Err(ToolError::state("Chrome UIA host timed out")),
    );

    let error_record = f
        .tools
        .chrome_dismiss_dialog(Some(&f.sid), Some("accept"), None, None, None)
        .await;
    let exception = f
        .tools
        .chrome_dismiss_dialog(Some(&f.sid), Some("accept"), None, None, None)
        .await;

    assert!(
        error_record.starts_with("Error dismissing Chrome dialog: dialog index 0 out of range"),
        "{error_record}"
    );
    assert!(
        exception.starts_with("Error dismissing Chrome dialog: Chrome UIA host timed out"),
        "{exception}"
    );
}

#[tokio::test]
async fn dialog_without_buttons_reports_it() {
    let f = fixture();
    f.host.on_ok("DIALOGS", &["D\t5\t0\tempty"]);

    let result = f
        .tools
        .chrome_dismiss_dialog(Some(&f.sid), Some("accept"), None, None, None)
        .await;

    assert_eq!(
        "Error dismissing Chrome dialog: the dialog has no buttons:\n- hwnd=5 dialogIndex=0 \"empty\"\n    buttons:\n",
        result
    );
}

// ===== MCP schema parity =====

#[test]
fn tool_schemas_match_java_parameters() {
    let router = NamsicServer::chrome_router();
    let tools = router.list_all();
    let names: Vec<_> = tools.iter().map(|t| t.name.to_string()).collect();
    for expected in [
        "chrome_list_tabs",
        "chrome_activate_tab",
        "chrome_list_dialogs",
        "chrome_dismiss_dialog",
    ] {
        assert!(names.contains(&expected.to_owned()), "{names:?}");
    }
    let expectations: [(&str, &[&str]); 4] = [
        ("chrome_list_tabs", &["sessionId"]),
        (
            "chrome_activate_tab",
            &["sessionId", "title", "hwnd", "tabIndex", "bringToFront"],
        ),
        ("chrome_list_dialogs", &["sessionId"]),
        (
            "chrome_dismiss_dialog",
            &["sessionId", "action", "button", "hwnd", "dialogIndex"],
        ),
    ];
    for (name, props) in expectations {
        let tool = tools.iter().find(|t| t.name == name).unwrap();
        let schema = serde_json::Value::Object((*tool.input_schema).clone());
        let required = schema["required"].as_array().cloned().unwrap_or_default();
        assert_eq!(
            vec![serde_json::json!("sessionId")],
            required,
            "{name}: {schema}"
        );
        for prop in props {
            let desc = schema["properties"][prop]["description"].as_str();
            assert!(
                desc.is_some_and(|d| !d.is_empty()),
                "{name}.{prop}: {schema}"
            );
        }
        assert_eq!(
            SESSION_PARAM_DESC,
            schema["properties"]["sessionId"]["description"]
        );
    }
}

// ===== real Chrome integration (Windows, Chrome running) =====

mod chrome_uia_it {
    use std::time::Instant;

    use super::*;

    fn new_host() -> Arc<ChromeUiaHost> {
        Arc::new(ChromeUiaHost::new(ChromeConfig::default()))
    }

    #[tokio::test]
    #[ignore = "needs a running Chrome on Windows"]
    async fn chrome_lists_real_tabs_and_reuses_the_resident_process() {
        let host = new_host();
        let index = ChromeTabIndex::parse(&list_tabs(host.as_ref()).await.unwrap());

        assert!(index.errors.is_empty(), "host errors: {:?}", index.errors);
        assert!(
            !index.windows.is_empty(),
            "Chrome must be running for this test"
        );
        assert!(
            !index.all_tabs().is_empty(),
            "Chrome must have at least one tab"
        );

        let window = &index.windows[0];
        assert!(!window.visual_state.is_empty());
        assert_ne!(0, window.hwnd, "window handle must be resolved");
        assert!(
            index
                .windows
                .iter()
                .any(|w| w.tabs.iter().any(|t| t.selected)),
            "exactly one tab per window should be marked selected"
        );

        // The second call reuses the running process, so no Add-Type cost.
        let started = Instant::now();
        list_tabs(host.as_ref()).await.unwrap();
        let warm_ms = started.elapsed().as_millis();
        assert!(
            warm_ms < 5_000,
            "warm LIST took {warm_ms} ms — process was not reused"
        );
        host.close().await;
    }

    #[tokio::test]
    #[ignore = "needs a running Chrome on Windows"]
    async fn chrome_activating_the_already_selected_tab_succeeds() {
        let host = new_host();
        let index = ChromeTabIndex::parse(&list_tabs(host.as_ref()).await.unwrap());
        let target = index
            .all_tabs()
            .into_iter()
            .find(|t| t.selected)
            .cloned()
            .expect("Chrome must have an active tab");

        // Re-select the already selected tab without taking focus: the screen does not change.
        let reply = activate_tab(host.as_ref(), target.hwnd, target.index, false)
            .await
            .unwrap();
        host.close().await;

        assert!(
            reply.iter().any(|l| l.starts_with("OK\t")),
            "expected an OK record but got {reply:?}"
        );
    }

    /// Title match -> activation -> confirmation, through the MCP tool layer.
    #[tokio::test]
    #[ignore = "needs a running Chrome on Windows"]
    async fn chrome_activates_a_background_tab_by_title_through_the_mcp_tool() {
        let host = new_host();
        let before = ChromeTabIndex::parse(&list_tabs(host.as_ref()).await.unwrap());
        // A full accessibility name is an exact match; skip names shared by several tabs.
        let Some(target) = before
            .all_tabs()
            .into_iter()
            .find(|t| !t.selected && before.match_title(Some(&t.name)).len() == 1)
            .cloned()
        else {
            eprintln!(
                "skipped: needs at least one background Chrome tab
{}",
                before.render()
            );
            host.close().await;
            return;
        };
        let previously_selected = before
            .all_tabs()
            .into_iter()
            .find(|t| t.selected && t.hwnd == target.hwnd)
            .cloned();

        let dir = tempfile::tempdir().unwrap();
        let registry = Arc::new(ProjectSessionRegistry::new(dir.path()));
        let sid = registry.init(Some("chrome-it")).unwrap().session_id.clone();
        let tools = ChromeTools::with_host(registry, host.clone());

        // bringToFront=false leaves OS focus alone.
        let result = tools
            .chrome_activate_tab(Some(&sid), Some(&target.name), None, None, Some(false))
            .await;
        let after = ChromeTabIndex::parse(&list_tabs(host.as_ref()).await.unwrap());
        if let Some(tab) = &previously_selected {
            let _ = activate_tab(host.as_ref(), tab.hwnd, tab.index, false).await;
        }
        host.close().await;

        assert!(result.starts_with("Activated tab: "), "{result}");
        assert!(
            after
                .all_tabs()
                .iter()
                .any(|t| t.hwnd == target.hwnd && t.index == target.index && t.selected),
            "target tab should now be selected:\n{}",
            after.render()
        );
    }

    /// With no dialog open, DIALOGS must not mistake bubbles/infobars for dialogs.
    #[tokio::test]
    #[ignore = "needs a running Chrome on Windows"]
    async fn chrome_lists_no_dialogs_when_none_is_open_and_rejects_unknown_dialog() {
        let host = new_host();
        let index = ChromeDialogIndex::parse(&list_dialogs(host.as_ref()).await.unwrap());
        assert!(index.errors.is_empty(), "host errors: {:?}", index.errors);
        if !index.dialogs.is_empty() {
            eprintln!("skipped: a Chrome dialog is open — close it to run this test");
            host.close().await;
            return;
        }

        let tabs = ChromeTabIndex::parse(&list_tabs(host.as_ref()).await.unwrap());
        assert!(
            !tabs.windows.is_empty(),
            "Chrome must be running for this test"
        );
        let reply = click_dialog_button(host.as_ref(), tabs.windows[0].hwnd, 0, 0)
            .await
            .unwrap();
        host.close().await;

        assert!(
            reply
                .iter()
                .any(|l| l.starts_with("ERR\tdialog index 0 out of range")),
            "expected an ERR record but got {reply:?}"
        );
    }

    #[tokio::test]
    #[ignore = "needs a running Chrome on Windows"]
    async fn chrome_reports_error_for_unknown_window_handle() {
        let host = new_host();
        let reply = activate_tab(host.as_ref(), 1, 0, false).await.unwrap();
        host.close().await;

        assert!(
            reply
                .iter()
                .any(|l| l.starts_with("ERR\tno chrome window with hwnd 1")),
            "expected an ERR record but got {reply:?}"
        );
    }
}

#[test]
fn missing_session_id_deserializes_to_none() {
    let p: ChromeListTabsParams = serde_json::from_value(serde_json::json!({})).unwrap();
    assert!(p.session_id.is_none());
    let p: ChromeActivateTabParams =
        serde_json::from_value(serde_json::json!({"sessionId": "s", "hwnd": 5, "tabIndex": 2}))
            .unwrap();
    assert_eq!(Some("s"), p.session_id.as_deref());
    assert_eq!(
        (Some(5), Some(2), None),
        (p.hwnd, p.tab_index, p.bring_to_front)
    );
    let schema = serde_json::Value::Object(
        (*NamsicServer::chrome_router()
            .list_all()
            .into_iter()
            .find(|t| t.name == "chrome_activate_tab")
            .unwrap()
            .input_schema)
            .clone(),
    );
    assert_eq!(serde_json::json!(["sessionId"]), schema["required"]);
}
