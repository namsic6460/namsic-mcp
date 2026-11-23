pub mod registry;
pub mod tool;

#[allow(unused_imports)]
pub use registry::{Project, ProjectSessionRegistry};

/// Shared `sessionId` parameter description used by android_* and chrome_* tools.
pub const SESSION_PARAM_DESC: &str = "Session ID returned by project_init. Required; obtain it by calling project_init with a project name first.";
