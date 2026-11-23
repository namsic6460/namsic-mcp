//! Parses the D/X/B records emitted by chrome-uia-host.ps1 into dialogs and
//! performs button selection. The script is a thin transport, so selection and
//! ambiguity decisions all live here (unit-tested).

use std::fmt::Write as _;

use super::{java_trim, parse_i32, parse_i64};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DialogButton {
    pub index: i32,
    pub enabled: bool,
    pub name: String,
}

/// One dialog open in one Chrome window. `texts` are the body labels (title excluded).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChromeDialog {
    pub hwnd: i64,
    pub index: i32,
    pub title: String,
    pub texts: Vec<String>,
    pub buttons: Vec<DialogButton>,
}

impl ChromeDialog {
    /// Accept button. Chrome lays the button row out as [accept, cancel]; button
    /// names follow Chrome's UI language, so pick by position, not by name.
    pub fn accept_button(&self) -> Option<&DialogButton> {
        self.buttons.first()
    }

    /// Cancel button. With a single button (alert) that button is the close button.
    pub fn cancel_button(&self) -> Option<&DialogButton> {
        self.buttons.last()
    }

    /// Finds buttons by name: exact matches if any, otherwise every substring match.
    pub fn match_buttons(&self, query: Option<&str>) -> Vec<&DialogButton> {
        let needle = java_trim(query.unwrap_or("")).to_lowercase();
        if needle.is_empty() {
            return Vec::new();
        }
        let mut exact = Vec::new();
        let mut contains = Vec::new();
        for button in &self.buttons {
            let name = button.name.to_lowercase();
            if name == needle {
                exact.push(button);
            } else if name.contains(&needle) {
                contains.push(button);
            }
        }
        if exact.is_empty() { contains } else { exact }
    }

    pub fn describe(&self) -> String {
        let mut sb = String::new();
        let _ = writeln!(
            sb,
            "- hwnd={} dialogIndex={} \"{}\"",
            self.hwnd, self.index, self.title
        );
        for text in &self.texts {
            let _ = writeln!(sb, "    {text}");
        }
        sb.push_str("    buttons:");
        for button in &self.buttons {
            let _ = write!(
                sb,
                " [{}{}",
                button.name,
                if button.enabled { "]" } else { " (disabled)]" }
            );
        }
        sb.push('\n');
        sb
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ChromeDialogIndex {
    pub dialogs: Vec<ChromeDialog>,
    pub errors: Vec<String>,
}

impl ChromeDialogIndex {
    pub fn parse(lines: &[String]) -> Self {
        let mut dialogs = Vec::new();
        let mut errors = Vec::new();
        let mut current: Option<ChromeDialog> = None;

        for line in lines {
            if line.trim().is_empty() {
                continue;
            }
            let parts: Vec<&str> = line.split('\t').collect();
            match parts[0] {
                "D" => {
                    if let Some(dialog) = current.take() {
                        dialogs.push(dialog);
                    }
                    if parts.len() < 4 {
                        errors.push(format!("malformed dialog record: {line}"));
                        continue;
                    }
                    let (Some(hwnd), Some(index)) = (parse_i64(parts[1]), parse_i32(parts[2]))
                    else {
                        errors.push(format!("malformed dialog address: {line}"));
                        continue;
                    };
                    current = Some(ChromeDialog {
                        hwnd,
                        index,
                        title: parts[3].to_owned(),
                        texts: Vec::new(),
                        buttons: Vec::new(),
                    });
                }
                "X" => {
                    let Some(dialog) = current.as_mut() else {
                        errors.push(format!("text record before any dialog record: {line}"));
                        continue;
                    };
                    if parts.len() < 4 {
                        errors.push(format!("malformed text record: {line}"));
                        continue;
                    }
                    dialog.texts.push(parts[3].to_owned());
                }
                "B" => {
                    let Some(dialog) = current.as_mut() else {
                        errors.push(format!("button record before any dialog record: {line}"));
                        continue;
                    };
                    if parts.len() < 6 {
                        errors.push(format!("malformed button record: {line}"));
                        continue;
                    }
                    let Some(index) = parse_i32(parts[3]) else {
                        errors.push(format!("malformed button index: {line}"));
                        continue;
                    };
                    dialog.buttons.push(DialogButton {
                        index,
                        enabled: parts[4] == "1",
                        name: parts[5].to_owned(),
                    });
                }
                "ERR" => {
                    errors.push(if parts.len() > 1 {
                        parts[1].to_owned()
                    } else {
                        line.clone()
                    });
                }
                _ => errors.push(format!("unrecognized record: {line}")),
            }
        }
        if let Some(dialog) = current {
            dialogs.push(dialog);
        }
        Self { dialogs, errors }
    }

    /// Dialogs narrowed by hwnd / dialogIndex; `None` filters are ignored.
    pub fn select(&self, hwnd: Option<i64>, dialog_index: Option<i32>) -> Vec<&ChromeDialog> {
        self.dialogs
            .iter()
            .filter(|d| hwnd.is_none_or(|h| d.hwnd == h))
            .filter(|d| dialog_index.is_none_or(|i| d.index == i))
            .collect()
    }

    /// Human-readable listing for tool responses.
    pub fn render(&self) -> String {
        let mut sb = String::new();
        if self.dialogs.is_empty() {
            sb.push_str(
                "No Chrome dialog is open.\n\
                 A dialog raised by a background tab is not shown until that tab is selected: if a browser \
                 tool is hanging, bring the tab it was driving to the front with chrome_activate_tab \
                 and check again.",
            );
        } else {
            let _ = writeln!(sb, "Chrome dialogs ({}):", self.dialogs.len());
            for dialog in &self.dialogs {
                sb.push_str(&dialog.describe());
            }
            sb.push_str(
                "\nPress a button with chrome_dismiss_dialog (action=accept presses the first button, \
                 action=cancel the last).",
            );
        }
        if !self.errors.is_empty() {
            sb.push_str("\n\nWarnings:");
            for error in &self.errors {
                sb.push_str("\n- ");
                sb.push_str(error);
            }
        }
        sb
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lines(items: &[&str]) -> Vec<String> {
        items.iter().map(|s| (*s).to_owned()).collect()
    }

    fn button(index: i32, enabled: bool, name: &str) -> DialogButton {
        DialogButton {
            index,
            enabled,
            name: name.to_owned(),
        }
    }

    /// Real chrome-uia-host.ps1 output shape (measured confirm dialog).
    fn confirm_lines() -> Vec<String> {
        lines(&[
            "D\t655724\t0\texample.com 내용:",
            "X\t655724\t0\t정말 창을 닫으시겠습니까?",
            "B\t655724\t0\t0\t1\t확인",
            "B\t655724\t0\t1\t1\t취소",
        ])
    }

    #[test]
    fn parses_dialog_text_and_buttons() {
        let index = ChromeDialogIndex::parse(&confirm_lines());

        assert!(index.errors.is_empty(), "{:?}", index.errors);
        assert_eq!(1, index.dialogs.len());

        let dialog = &index.dialogs[0];
        assert_eq!(655724, dialog.hwnd);
        assert_eq!(0, dialog.index);
        assert_eq!("example.com 내용:", dialog.title);
        assert_eq!(vec!["정말 창을 닫으시겠습니까?".to_owned()], dialog.texts);
        assert_eq!(
            vec![button(0, true, "확인"), button(1, true, "취소")],
            dialog.buttons
        );
    }

    #[test]
    fn accept_is_first_button_and_cancel_is_last() {
        let index = ChromeDialogIndex::parse(&confirm_lines());
        let dialog = &index.dialogs[0];

        assert_eq!("확인", dialog.accept_button().unwrap().name);
        assert_eq!("취소", dialog.cancel_button().unwrap().name);
    }

    #[test]
    fn single_button_alert_uses_the_same_button_for_both_actions() {
        let index =
            ChromeDialogIndex::parse(&lines(&["D\t1\t0\texample.com says", "B\t1\t0\t0\t1\tOK"]));
        let dialog = &index.dialogs[0];

        assert_eq!("OK", dialog.accept_button().unwrap().name);
        assert_eq!("OK", dialog.cancel_button().unwrap().name);
    }

    #[test]
    fn match_buttons_prefers_exact_over_substring() {
        let index = ChromeDialogIndex::parse(&lines(&[
            "D\t1\t0\tLeave site?",
            "B\t1\t0\t0\t1\tLeave",
            "B\t1\t0\t1\t1\tLeave later",
        ]));
        let dialog = &index.dialogs[0];

        assert_eq!(
            vec![&button(0, true, "Leave")],
            dialog.match_buttons(Some(" leave "))
        );
        assert_eq!(2, dialog.match_buttons(Some("lea")).len());
        assert!(dialog.match_buttons(Some("nope")).is_empty());
        assert!(dialog.match_buttons(Some("  ")).is_empty());
    }

    #[test]
    fn parses_several_dialogs_and_selects_by_address() {
        let index = ChromeDialogIndex::parse(&lines(&[
            "D\t1\t0\tfirst",
            "B\t1\t0\t0\t1\tOK",
            "D\t2\t0\tsecond",
            "B\t2\t0\t0\t0\tOK",
        ]));

        assert_eq!(2, index.dialogs.len());
        assert_eq!(2, index.select(None, None).len());
        assert_eq!("second", index.select(Some(2), None)[0].title);
        assert_eq!("second", index.select(Some(2), Some(0))[0].title);
        assert!(index.select(Some(3), None).is_empty());
        assert!(!index.dialogs[1].buttons[0].enabled);
    }

    #[test]
    fn collects_malformed_and_error_records() {
        let index = ChromeDialogIndex::parse(&lines(&[
            "X\t1\t0\torphan text",
            "D\tnot-a-number\t0\ttitle",
            "D\t1\t0\tok",
            "B\t1\t0\tx\t1\tbad index",
            "ERR\tsomething failed",
            "???",
        ]));

        assert_eq!(1, index.dialogs.len());
        assert_eq!(5, index.errors.len());
        assert!(
            index.errors.contains(&"something failed".to_owned()),
            "{:?}",
            index.errors
        );
    }

    #[test]
    fn renders_empty_listing_with_background_tab_hint() {
        let rendered = ChromeDialogIndex::parse(&[]).render();

        assert!(
            rendered.starts_with("No Chrome dialog is open."),
            "{rendered}"
        );
        assert!(rendered.contains("chrome_activate_tab"), "{rendered}");
    }

    #[test]
    fn renders_dialog_listing() {
        let rendered = ChromeDialogIndex::parse(&confirm_lines()).render();

        assert!(rendered.contains("Chrome dialogs (1):"), "{rendered}");
        assert!(
            rendered.contains("hwnd=655724 dialogIndex=0 \"example.com 내용:\""),
            "{rendered}"
        );
        assert!(rendered.contains("정말 창을 닫으시겠습니까?"), "{rendered}");
        assert!(rendered.contains("buttons: [확인] [취소]"), "{rendered}");
    }

    #[test]
    fn renders_exact_layout_with_disabled_button_and_warning() {
        let rendered = ChromeDialogIndex::parse(&lines(&[
            "D\t3\t1\tT",
            "X\t3\t1\tbody",
            "B\t3\t1\t0\t0\tOK",
            "ERR\tboom",
        ]))
        .render();

        assert_eq!(
            "Chrome dialogs (1):\n- hwnd=3 dialogIndex=1 \"T\"\n    body\n    buttons: [OK (disabled)]\n\n\
             Press a button with chrome_dismiss_dialog (action=accept presses the first button, \
             action=cancel the last).\n\nWarnings:\n- boom",
            rendered
        );
    }
}
