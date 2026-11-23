//! Helpers that build `CallToolResult`s matching the Java server's shapes.

use rmcp::model::{CallToolResult, ContentBlock};
use serde::Serialize;

/// One text block, `isError=false` (Java tools returning `String`).
pub fn ok_text(text: impl Into<String>) -> CallToolResult {
    CallToolResult::success(vec![ContentBlock::text(text)])
}

/// One text block, `isError=true`.
pub fn err_text(text: impl Into<String>) -> CallToolResult {
    CallToolResult::error(vec![ContentBlock::text(text)])
}

/// Text block followed by image blocks (base64 data + mime type), `isError=false`.
pub fn text_and_images(text: impl Into<String>, images: Vec<(String, String)>) -> CallToolResult {
    let mut content = vec![ContentBlock::text(text)];
    content.extend(
        images
            .into_iter()
            .map(|(data, mime)| ContentBlock::image(data, mime)),
    );
    CallToolResult::success(content)
}

/// Serializes `value` as compact JSON into one text block, `isError=false`.
pub fn ok_json<T: Serialize>(value: &T) -> CallToolResult {
    match serde_json::to_string(value) {
        Ok(json) => ok_text(json),
        Err(e) => err_text(format!("Failed to serialize result: {e}")),
    }
}

/// Text of the first content block (test helper).
#[cfg(test)]
pub fn first_text(result: &CallToolResult) -> String {
    result
        .content
        .iter()
        .find_map(|c| match c {
            ContentBlock::Text(t) => Some(t.text.clone()),
            _ => None,
        })
        .unwrap_or_default()
}

#[cfg(test)]
pub fn is_error(result: &CallToolResult) -> bool {
    result.is_error.unwrap_or(false)
}
