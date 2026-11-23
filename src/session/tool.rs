use rmcp::handler::server::wrapper::Parameters;
use rmcp::{schemars, tool, tool_router};
use serde::Deserialize;

use super::ProjectSessionRegistry;
use crate::server::NamsicServer;

#[derive(Debug, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct ProjectInitParams {
    /// Project name used as the screenshot subdirectory name. Characters outside [A-Za-z0-9._-] are replaced with '_'.
    pub project_name: String,
}

pub fn project_init(registry: &ProjectSessionRegistry, project_name: Option<&str>) -> String {
    tracing::info!("MCP tool invoked: project_init projectName={project_name:?}");
    match registry.init(project_name) {
        Ok(p) => format!(
            "sessionId: {}\nproject: {}\nscreenshotDir: {}\n\nPass this sessionId to every android_* and chrome_* tool call in this conversation.",
            p.session_id,
            p.project_name,
            std::path::absolute(&p.screenshot_dir)
                .unwrap_or_else(|_| p.screenshot_dir.clone())
                .display()
        ),
        Err(e) => format!("Error initializing project session: {e}"),
    }
}

#[tool_router(router = session_router, vis = "pub(crate)")]
impl NamsicServer {
    #[tool(
        name = "project_init",
        description = "Initialize a project-scoped session. Returns a UUID sessionId that MUST be passed to every subsequent android_* and chrome_* tool call. The projectName is used as the screenshot subdirectory name under the screenshot base dir. Calling project_init again with the same projectName is idempotent and returns the existing sessionId."
    )]
    async fn project_init_tool(&self, Parameters(p): Parameters<ProjectInitParams>) -> String {
        project_init(&self.state.registry, Some(&p.project_name))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn registry() -> (tempfile::TempDir, ProjectSessionRegistry) {
        let dir = tempfile::tempdir().unwrap();
        let reg = ProjectSessionRegistry::new(dir.path());
        (dir, reg)
    }

    #[test]
    fn returns_session_id_and_creates_screenshot_dir() {
        let (dir, reg) = registry();
        let out = project_init(&reg, Some("my-project"));
        assert!(out.contains("sessionId: "));
        assert!(out.contains("project: my-project"));
        assert!(out.contains("screenshotDir: "));
        assert!(dir.path().join("my-project").is_dir());
    }

    #[test]
    fn sanitizes_project_name() {
        let (_dir, reg) = registry();
        let out = project_init(&reg, Some("unsafe/proj:name"));
        assert!(out.contains("project: unsafe_proj_name"));
    }

    #[test]
    fn rejects_blank_project_name() {
        let (_dir, reg) = registry();
        assert!(project_init(&reg, Some("  ")).starts_with("Error initializing project session"));
        assert!(project_init(&reg, None).starts_with("Error initializing project session"));
    }

    #[test]
    fn idempotent_for_same_project_name() {
        let (_dir, reg) = registry();
        let a = reg.init(Some("same")).unwrap();
        let b = reg.init(Some("same")).unwrap();
        assert_eq!(a.session_id, b.session_id);
    }

    #[test]
    fn different_project_names_get_different_session_ids() {
        let (_dir, reg) = registry();
        let a = reg.init(Some("one")).unwrap();
        let b = reg.init(Some("two")).unwrap();
        assert_ne!(a.session_id, b.session_id);
    }

    #[test]
    fn require_reports_missing_and_unknown_session() {
        let (_dir, reg) = registry();
        assert_eq!(
            reg.require(None).unwrap_err().message(),
            "sessionId is required. Call project_init first to obtain one."
        );
        assert!(
            reg.require(Some("nope"))
                .unwrap_err()
                .message()
                .starts_with("Unknown sessionId=nope.")
        );
    }
}
