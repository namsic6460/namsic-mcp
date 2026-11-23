//! Small helpers that reproduce Java string/time semantics used by the LLM tools.

use chrono::{DateTime, SecondsFormat, Utc};

/// `String.length()` in Java: number of UTF-16 code units.
pub fn utf16_len(value: &str) -> usize {
    value.encode_utf16().count()
}

/// `Character.isWhitespace(char)`.
pub fn java_is_whitespace(c: char) -> bool {
    match c {
        '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r' | '\u{1C}'..='\u{1F}' => true,
        // Non-breaking space separators are excluded by Java.
        '\u{A0}' | '\u{2007}' | '\u{202F}' => false,
        ' '
        | '\u{1680}'
        | '\u{2000}'..='\u{200A}'
        | '\u{2028}'
        | '\u{2029}'
        | '\u{205F}'
        | '\u{3000}' => true,
        _ => false,
    }
}

/// `String.isBlank()`.
pub fn java_is_blank(value: &str) -> bool {
    value.chars().all(java_is_whitespace)
}

/// `String.strip()`.
pub fn java_strip(value: &str) -> &str {
    value.trim_matches(java_is_whitespace)
}

/// `Instant.toString()`: ISO-8601 UTC with 0, 3, 6 or 9 fractional digits and a `Z` suffix.
pub fn instant_string(at: DateTime<Utc>) -> String {
    at.to_rfc3339_opts(SecondsFormat::AutoSi, true)
}

#[cfg(test)]
mod tests {
    use chrono::TimeZone;

    use super::*;

    #[test]
    fn instant_string_matches_java_fraction_rules() {
        let base = Utc.with_ymd_and_hms(2026, 11, 22, 21, 45, 12).unwrap();
        assert_eq!(instant_string(base), "2026-11-22T21:45:12Z");
        let ms = base + chrono::Duration::milliseconds(51);
        assert_eq!(instant_string(ms), "2026-11-22T21:45:12.051Z");
        let us = base + chrono::Duration::microseconds(51_234);
        assert_eq!(instant_string(us), "2026-11-22T21:45:12.051234Z");
        let ns = base + chrono::Duration::nanoseconds(51_234_500);
        assert_eq!(instant_string(ns), "2026-11-22T21:45:12.051234500Z");
    }

    #[test]
    fn java_string_semantics() {
        assert_eq!(utf16_len("a한😀"), 4);
        assert!(java_is_blank(" \t\n\u{3000}"));
        assert!(!java_is_blank("\u{A0}"));
        assert_eq!(java_strip("  x y \n"), "x y");
    }
}
