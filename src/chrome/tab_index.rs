//! Parses the W/T records emitted by chrome-uia-host.ps1 into windows and tabs and
//! performs title matching. The script is a thin transport, so matching and
//! ambiguity decisions all live here (unit-tested).

use std::fmt::Write as _;

use super::{java_trim, parse_i32, parse_i64};

/// One tab in a tab strip. `name` is Chrome's accessibility label (page title + Chrome suffixes).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChromeTab {
    pub hwnd: i64,
    pub index: i32,
    pub selected: bool,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChromeWindow {
    pub hwnd: i64,
    pub visual_state: String,
    pub title: String,
    pub tabs: Vec<ChromeTab>,
}

impl ChromeWindow {
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn minimized(&self) -> bool {
        self.visual_state.eq_ignore_ascii_case("Minimized")
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ChromeTabIndex {
    pub windows: Vec<ChromeWindow>,
    pub errors: Vec<String>,
}

impl ChromeTabIndex {
    pub fn parse(lines: &[String]) -> Self {
        let mut windows = Vec::new();
        let mut errors = Vec::new();
        let mut current: Option<ChromeWindow> = None;

        for line in lines {
            if line.trim().is_empty() {
                continue;
            }
            let parts: Vec<&str> = line.split('\t').collect();
            match parts[0] {
                "W" => {
                    if let Some(window) = current.take() {
                        windows.push(window);
                    }
                    if parts.len() < 4 {
                        errors.push(format!("malformed window record: {line}"));
                        continue;
                    }
                    let Some(hwnd) = parse_i64(parts[1]) else {
                        errors.push(format!("malformed window handle: {line}"));
                        continue;
                    };
                    current = Some(ChromeWindow {
                        hwnd,
                        visual_state: parts[2].to_owned(),
                        title: parts[3].to_owned(),
                        tabs: Vec::new(),
                    });
                }
                "T" => {
                    let Some(window) = current.as_mut() else {
                        errors.push(format!("tab record before any window record: {line}"));
                        continue;
                    };
                    if parts.len() < 5 {
                        errors.push(format!("malformed tab record: {line}"));
                        continue;
                    }
                    let Some(index) = parse_i32(parts[2]) else {
                        errors.push(format!("malformed tab index: {line}"));
                        continue;
                    };
                    window.tabs.push(ChromeTab {
                        hwnd: window.hwnd,
                        index,
                        selected: parts[3] == "1",
                        name: parts[4].to_owned(),
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
        if let Some(window) = current {
            windows.push(window);
        }
        Self { windows, errors }
    }

    pub fn all_tabs(&self) -> Vec<&ChromeTab> {
        self.windows.iter().flat_map(|w| w.tabs.iter()).collect()
    }

    /// Finds tabs by title: exact match, then prefix, then substring (case-insensitive).
    /// Every candidate of the winning tier is returned; the caller handles ambiguity.
    pub fn match_title(&self, query: Option<&str>) -> Vec<&ChromeTab> {
        let needle = java_trim(query.unwrap_or("")).to_lowercase();
        if needle.is_empty() {
            return Vec::new();
        }
        let mut exact = Vec::new();
        let mut prefix = Vec::new();
        let mut contains = Vec::new();
        for tab in self.all_tabs() {
            let name = tab.name.to_lowercase();
            if name == needle {
                exact.push(tab);
            } else if name.starts_with(&needle) {
                prefix.push(tab);
            } else if name.contains(&needle) {
                contains.push(tab);
            }
        }
        if !exact.is_empty() {
            exact
        } else if !prefix.is_empty() {
            prefix
        } else {
            contains
        }
    }

    /// Human-readable listing for tool responses.
    pub fn render(&self) -> String {
        let mut sb = String::new();
        if self.windows.is_empty() {
            sb.push_str("No Chrome windows found. Is Chrome running?");
        } else {
            let _ = writeln!(sb, "Chrome windows ({}):", self.windows.len());
            for window in &self.windows {
                let _ = writeln!(
                    sb,
                    "- hwnd={} state={} \"{}\"",
                    window.hwnd, window.visual_state, window.title
                );
                for tab in &window.tabs {
                    let _ = writeln!(
                        sb,
                        "    [{}]{}{}",
                        tab.index,
                        if tab.selected { " * " } else { "   " },
                        tab.name
                    );
                }
            }
            sb.push_str(
                "\n'*' marks the active tab of its window — every other tab is hidden \
                 (requestAnimationFrame frozen, timers throttled to ~1Hz).\n\
                 Activate one with chrome_activate_tab, by title or by hwnd + tabIndex.",
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

    /// Real chrome-uia-host.ps1 output shape (tab names carry Chrome's suffixes).
    fn sample_lines() -> Vec<String> {
        lines(&[
            "W\t132886\tNormal\tExample Domain - Chrome",
            "T\t132886\t0\t0\tDreamMakerClient - ✅Claude 그룹에 속함 - 메모리 사용량 - 238MB",
            "T\t132886\t1\t1\tExample Domain - ⌛Claude 그룹에 속함",
            "W\t66560\tMinimized\tGitHub - Chrome",
            "T\t66560\t0\t1\tGitHub",
        ])
    }

    #[test]
    fn parses_windows_and_tabs() {
        let index = ChromeTabIndex::parse(&sample_lines());

        assert_eq!(2, index.windows.len());
        assert!(index.errors.is_empty(), "{:?}", index.errors);

        let first = &index.windows[0];
        assert_eq!(132886, first.hwnd);
        assert_eq!("Normal", first.visual_state);
        assert_eq!("Example Domain - Chrome", first.title);
        assert!(!first.minimized());
        assert_eq!(2, first.tabs.len());

        let active = &first.tabs[1];
        assert_eq!(1, active.index);
        assert!(active.selected);
        assert_eq!(132886, active.hwnd);

        assert!(!first.tabs[0].selected);
        assert!(index.windows[1].minimized());
        assert_eq!(3, index.all_tabs().len());
    }

    #[test]
    fn matches_page_title_as_prefix_of_accessibility_name() {
        let index = ChromeTabIndex::parse(&sample_lines());

        let matches = index.match_title(Some("Example Domain"));

        assert_eq!(1, matches.len());
        assert_eq!(132886, matches[0].hwnd);
        assert_eq!(1, matches[0].index);
    }

    #[test]
    fn match_is_case_insensitive_and_trimmed() {
        let index = ChromeTabIndex::parse(&sample_lines());
        assert_eq!(1, index.match_title(Some("  gitHUB  ")).len());
    }

    #[test]
    fn exact_match_wins_over_prefix_and_substring() {
        let index = ChromeTabIndex::parse(&lines(&[
            "W\t1\tNormal\tw",
            "T\t1\t0\t0\tReport",
            "T\t1\t1\t0\tReport - memory 12MB",
            "T\t1\t2\t0\tQuarterly Report archive",
        ]));

        let matches = index.match_title(Some("Report"));

        assert_eq!(1, matches.len());
        assert_eq!(0, matches[0].index);
    }

    #[test]
    fn prefix_match_wins_over_substring() {
        let index = ChromeTabIndex::parse(&lines(&[
            "W\t1\tNormal\tw",
            "T\t1\t0\t0\tReport - memory 12MB",
            "T\t1\t1\t0\tQuarterly Report archive",
        ]));

        let matches = index.match_title(Some("Report"));

        assert_eq!(1, matches.len());
        assert_eq!(0, matches[0].index);
    }

    #[test]
    fn returns_every_candidate_when_ambiguous_at_same_tier() {
        let index = ChromeTabIndex::parse(&lines(&[
            "W\t1\tNormal\tw1",
            "T\t1\t0\t0\tDashboard - memory 10MB",
            "W\t2\tNormal\tw2",
            "T\t2\t0\t0\tDashboard - memory 30MB",
        ]));

        let matches = index.match_title(Some("Dashboard"));

        assert_eq!(2, matches.len());
        assert_eq!(1, matches[0].hwnd);
        assert_eq!(2, matches[1].hwnd);
    }

    #[test]
    fn match_returns_empty_for_blank_or_null_query() {
        let index = ChromeTabIndex::parse(&sample_lines());
        assert!(index.match_title(Some("   ")).is_empty());
        assert!(index.match_title(None).is_empty());
    }

    #[test]
    fn collects_host_error_records() {
        let index = ChromeTabIndex::parse(&lines(&[
            "W\t1\tNormal\tw1",
            "ERR\ttab strip not found for hwnd 1",
        ]));

        assert_eq!(1, index.windows.len());
        assert!(index.windows[0].tabs.is_empty());
        assert_eq!(
            vec!["tab strip not found for hwnd 1".to_owned()],
            index.errors
        );
    }

    #[test]
    fn reports_malformed_records_without_losing_valid_ones() {
        let index = ChromeTabIndex::parse(&lines(&[
            "W\tnot-a-number\tNormal\tbroken",
            "T\t1\t0\t0\torphan tab",
            "W\t7\tNormal\tgood",
            "T\t7\tnope\t0\tbad index",
            "T\t7\t0\t1\tfine",
            "X\tunknown",
        ]));

        assert_eq!(1, index.windows.len());
        assert_eq!(7, index.windows[0].hwnd);
        assert_eq!(1, index.windows[0].tabs.len());
        assert_eq!("fine", index.windows[0].tabs[0].name);
        assert_eq!(4, index.errors.len(), "{:?}", index.errors);
    }

    #[test]
    fn parse_handles_null_and_empty_input() {
        assert!(ChromeTabIndex::parse(&[]).windows.is_empty());
        assert!(
            ChromeTabIndex::parse(&lines(&["", "   "]))
                .windows
                .is_empty()
        );
    }

    #[test]
    fn renders_tabs_with_active_marker() {
        let rendered = ChromeTabIndex::parse(&sample_lines()).render();

        assert!(rendered.contains("Chrome windows (2):"), "{rendered}");
        assert!(rendered.contains("hwnd=132886 state=Normal"), "{rendered}");
        assert!(rendered.contains("state=Minimized"), "{rendered}");
        assert!(rendered.contains("[1] * Example Domain"), "{rendered}");
        assert!(rendered.contains("[0]   DreamMakerClient"), "{rendered}");
    }

    #[test]
    fn renders_exact_layout() {
        let rendered = ChromeTabIndex::parse(&lines(&[
            "W\t5\tNormal\tTitle",
            "T\t5\t0\t1\tA",
            "T\t5\t1\t0\tB",
            "ERR\tboom",
        ]))
        .render();

        assert_eq!(
            "Chrome windows (1):\n\
             - hwnd=5 state=Normal \"Title\"\n    [0] * A\n    [1]   B\n\n\
             '*' marks the active tab of its window — every other tab is hidden \
             (requestAnimationFrame frozen, timers throttled to ~1Hz).\n\
             Activate one with chrome_activate_tab, by title or by hwnd + tabIndex.\n\n\
             Warnings:\n- boom",
            rendered
        );
    }

    #[test]
    fn renders_empty_and_warnings() {
        assert!(
            ChromeTabIndex::parse(&[])
                .render()
                .contains("No Chrome windows found")
        );

        let rendered = ChromeTabIndex::parse(&lines(&["ERR\tboom"])).render();
        assert!(rendered.contains("Warnings:"), "{rendered}");
        assert!(rendered.contains("boom"), "{rendered}");
    }
}
