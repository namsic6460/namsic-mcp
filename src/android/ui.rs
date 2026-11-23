//! `uiautomator dump` XML -> compact, LLM-friendly element list (pure functions).

use std::fmt::Write as _;
use std::sync::LazyLock;

use regex::Regex;

use crate::util::pixel_sampler::java_trim;

/// One dump element. `index` is the position in the list exposed by
/// `android_dump_ui` and the `elementIndex` target of `android_tap`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UiNode {
    pub index: usize,
    pub text: String,
    pub resource_id: String,
    pub content_desc: String,
    pub class_name: String,
    pub clickable: bool,
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
    pub center_x: i32,
    pub center_y: i32,
}

impl UiNode {
    /// One-line representation; empty fields are omitted to save tokens.
    pub fn to_compact_line(&self) -> String {
        let mut sb = String::with_capacity(64);
        let _ = write!(sb, "[{}]", self.index);
        if !self.text.is_empty() {
            let _ = write!(sb, " text=\"{}\"", self.text);
        }
        if !self.content_desc.is_empty() {
            let _ = write!(sb, " desc=\"{}\"", self.content_desc);
        }
        if !self.resource_id.is_empty() {
            let _ = write!(sb, " id={}", short_resource_id(&self.resource_id));
        }
        if !self.class_name.is_empty() {
            let _ = write!(sb, " class={}", short_class_name(&self.class_name));
        }
        if self.clickable {
            sb.push_str(" clickable");
        }
        let _ = write!(
            sb,
            " bounds=[{},{}][{},{}] center=({},{})",
            self.left, self.top, self.right, self.bottom, self.center_x, self.center_y
        );
        sb
    }
}

/// `"com.app:id/login"` -> `"login"`.
pub fn short_resource_id(resource_id: &str) -> &str {
    after_last(resource_id, '/')
}

/// `"android.widget.Button"` -> `"Button"`.
pub fn short_class_name(class_name: &str) -> &str {
    after_last(class_name, '.')
}

fn after_last(s: &str, ch: char) -> &str {
    match s.rfind(ch) {
        Some(idx) if idx + ch.len_utf8() < s.len() => &s[idx + ch.len_utf8()..],
        _ => s,
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ParseResult {
    pub nodes: Vec<UiNode>,
    pub hidden_count: usize,
    pub truncated: bool,
}

static BOUNDS_PATTERN: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^\[(-?[0-9]+),(-?[0-9]+)\]\[(-?[0-9]+),(-?[0-9]+)\]$").expect("valid regex")
});

/// Parses the whole dump. Invalid/empty XML yields an empty result instead of an error.
pub fn parse(raw_xml: Option<&str>, max_nodes: usize) -> ParseResult {
    let xml = strip_prefix_noise(raw_xml);
    if xml.is_empty() {
        return ParseResult::default();
    }
    // roxmltree rejects DTDs by default (matches disallow-doctype-decl / XXE defense).
    let document = match roxmltree::Document::parse(xml) {
        Ok(d) => d,
        Err(e) => {
            tracing::warn!("Failed to parse uiautomator dump XML: {e}");
            return ParseResult::default();
        }
    };
    let mut result = ParseResult::default();
    collect(document.root_element(), &mut result, max_nodes);
    result
}

/// Converts the parse result into compact text the LLM can act on directly.
pub fn to_compact_list(result: &ParseResult) -> String {
    if result.nodes.is_empty() {
        return "(no interactive elements found — the screen may be a WebView/Flutter/game surface that does not \
                expose its UI tree; use android_screenshot and tap by raw x,y coordinates instead)"
            .to_owned();
    }
    let mut sb = String::with_capacity(result.nodes.len() * 80);
    let _ = writeln!(sb, "UI elements ({} shown):", result.nodes.len());
    for node in &result.nodes {
        sb.push_str(&node.to_compact_line());
        sb.push('\n');
    }
    if result.truncated {
        let _ = writeln!(
            sb,
            "(truncated at {} nodes — pass a larger maxNodes to see more)",
            result.nodes.len()
        );
    }
    if result.hidden_count > 0 {
        let _ = writeln!(sb, "({} non-interactive nodes hidden)", result.hidden_count);
    }
    sb.push_str(
        "Tap an element with android_tap using selector (id/text/desc substring) or elementIndex.",
    );
    sb
}

/// `"[0,100][1080,200]"` -> `[0, 100, 1080, 200]`; `None` on mismatch.
pub fn parse_bounds(bounds: Option<&str>) -> Option<[i32; 4]> {
    let caps = BOUNDS_PATTERN.captures(bounds?)?;
    let mut out = [0i32; 4];
    for (i, slot) in out.iter_mut().enumerate() {
        *slot = caps.get(i + 1)?.as_str().parse().ok()?;
    }
    Some(out)
}

/// Drops noise the dump command prints before the XML ("UI hierchary dumped to: ...").
fn strip_prefix_noise(raw_xml: Option<&str>) -> &str {
    let Some(raw) = raw_xml.filter(|s| !s.trim().is_empty()) else {
        return "";
    };
    match raw.find("<?xml").or_else(|| raw.find("<hierarchy")) {
        Some(start) => &raw[start..],
        None => "",
    }
}

fn collect(element: roxmltree::Node<'_, '_>, out: &mut ParseResult, max_nodes: usize) {
    if out.truncated {
        return;
    }
    if element.tag_name().name() == "node" {
        let attr = |name: &str| java_trim(element.attribute(name).unwrap_or("")).to_owned();
        let text = attr("text");
        let content_desc = attr("content-desc");
        let clickable = element
            .attribute("clickable")
            .is_some_and(|v| v.eq_ignore_ascii_case("true"));
        if clickable || !text.is_empty() || !content_desc.is_empty() {
            if let Some([l, t, r, b]) = parse_bounds(element.attribute("bounds")) {
                if out.nodes.len() >= max_nodes {
                    out.truncated = true;
                    return;
                }
                out.nodes.push(UiNode {
                    index: out.nodes.len(),
                    text,
                    resource_id: attr("resource-id"),
                    content_desc,
                    class_name: attr("class"),
                    clickable,
                    left: l,
                    top: t,
                    right: r,
                    bottom: b,
                    center_x: l.wrapping_add(r) / 2,
                    center_y: t.wrapping_add(b) / 2,
                });
            }
        } else {
            out.hidden_count += 1;
        }
    }
    for child in element.children().filter(roxmltree::Node::is_element) {
        collect(child, out, max_nodes);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE_XML: &str = r#"<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" content-desc=""
        clickable="false" enabled="true" bounds="[0,0][1080,2400]">
    <node index="0" text="로그인" resource-id="com.example.app:id/btn_login"
          class="android.widget.Button" content-desc="" clickable="true" enabled="true"
          bounds="[120,1400][960,1520]"/>
    <node index="1" text="" resource-id="com.example.app:id/et_username"
          class="android.widget.EditText" content-desc="아이디 입력" clickable="true" enabled="true"
          bounds="[60,800][1020,920]"/>
    <node index="2" text="환영합니다" resource-id="" class="android.widget.TextView"
          content-desc="" clickable="false" enabled="true" bounds="[0,100][1080,200]"/>
  </node>
</hierarchy>
"#;

    #[test]
    fn parse_extracts_visible_nodes_with_centers() {
        let result = parse(Some(SAMPLE_XML), 200);
        assert_eq!(result.nodes.len(), 3);
        assert_eq!(result.hidden_count, 1);
        assert!(!result.truncated);

        let button = &result.nodes[0];
        assert_eq!(button.index, 0);
        assert_eq!(button.text, "로그인");
        assert_eq!(button.resource_id, "com.example.app:id/btn_login");
        assert!(button.clickable);
        assert_eq!((button.center_x, button.center_y), (540, 1460));

        let edit = &result.nodes[1];
        assert_eq!(edit.content_desc, "아이디 입력");
        assert_eq!((edit.center_x, edit.center_y), (540, 860));

        let text_view = &result.nodes[2];
        assert_eq!(text_view.text, "환영합니다");
        assert!(!text_view.clickable);
    }

    #[test]
    fn parse_respects_max_nodes() {
        let result = parse(Some(SAMPLE_XML), 2);
        assert_eq!(result.nodes.len(), 2);
        assert!(result.truncated);
    }

    #[test]
    fn parse_strips_dump_prefix_noise() {
        let raw = format!("UI hierchary dumped to: /dev/tty\n{SAMPLE_XML}");
        assert_eq!(parse(Some(&raw), 200).nodes.len(), 3);
    }

    #[test]
    fn parse_returns_empty_on_invalid_xml() {
        assert!(
            parse(Some("<?xml version='1.0'?><hierarchy><node"), 200)
                .nodes
                .is_empty()
        );
        assert!(parse(Some(""), 200).nodes.is_empty());
        assert!(parse(None, 200).nodes.is_empty());
        assert!(
            parse(Some("ERROR: could not get idle state."), 200)
                .nodes
                .is_empty()
        );
    }

    #[test]
    fn parse_rejects_doctype_xml() {
        let xxe = r#"<?xml version="1.0"?>
<!DOCTYPE hierarchy [<!ENTITY ext SYSTEM "file:///etc/passwd">]>
<hierarchy><node text="&ext;" clickable="true" bounds="[0,0][10,10]"/></hierarchy>
"#;
        assert!(parse(Some(xxe), 200).nodes.is_empty());
    }

    #[test]
    fn parse_bounds_parses_valid_and_rejects_invalid() {
        assert_eq!(
            parse_bounds(Some("[0,100][1080,200]")),
            Some([0, 100, 1080, 200])
        );
        assert_eq!(
            parse_bounds(Some("[-10,-5][30,40]")),
            Some([-10, -5, 30, 40])
        );
        assert_eq!(parse_bounds(Some("")), None);
        assert_eq!(parse_bounds(None), None);
        assert_eq!(parse_bounds(Some("[a,b][c,d]")), None);
    }

    #[test]
    fn to_compact_list_formats_elements() {
        let output = to_compact_list(&parse(Some(SAMPLE_XML), 200));
        assert!(
            output.contains("[0] text=\"로그인\" id=btn_login class=Button clickable"),
            "{output}"
        );
        assert!(output.contains("center=(540,1460)"), "{output}");
        assert!(
            output.contains("(1 non-interactive nodes hidden)"),
            "{output}"
        );
        assert!(output.starts_with("UI elements (3 shown):\n"), "{output}");
        assert!(output.ends_with(
            "\nTap an element with android_tap using selector (id/text/desc substring) or elementIndex."
        ));
    }

    #[test]
    fn to_compact_list_reports_truncation() {
        let output = to_compact_list(&parse(Some(SAMPLE_XML), 1));
        assert!(
            output.contains("(truncated at 1 nodes — pass a larger maxNodes to see more)\n"),
            "{output}"
        );
    }

    #[test]
    fn to_compact_list_guides_fallback_when_empty() {
        let output = to_compact_list(&ParseResult::default());
        assert_eq!(
            output,
            "(no interactive elements found — the screen may be a WebView/Flutter/game surface that does not \
             expose its UI tree; use android_screenshot and tap by raw x,y coordinates instead)"
        );
    }

    #[test]
    fn short_names() {
        assert_eq!(short_resource_id("com.app:id/login"), "login");
        assert_eq!(short_resource_id("trailing/"), "trailing/");
        assert_eq!(short_class_name("android.widget.Button"), "Button");
        assert_eq!(short_class_name("Plain"), "Plain");
    }
}
