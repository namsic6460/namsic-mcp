use std::sync::Arc;

use rmcp::handler::server::router::tool::ToolRouter;
use rmcp::{ServerHandler, tool_handler};

use crate::android::AndroidTools;
use crate::chrome::ChromeTools;
use crate::config::Config;
use crate::llm::LlmTools;
use crate::session::ProjectSessionRegistry;

/// Long-lived services shared by every MCP connection.
pub struct AppState {
    pub registry: Arc<ProjectSessionRegistry>,
    pub android: AndroidTools,
    pub chrome: ChromeTools,
    pub llm: LlmTools,
}

impl AppState {
    pub fn new(config: &Config) -> anyhow::Result<Self> {
        let registry = Arc::new(ProjectSessionRegistry::new(
            config.session.screenshot_base_dir.clone(),
        ));
        Ok(Self {
            android: AndroidTools::new(registry.clone(), config.android.clone()),
            chrome: ChromeTools::new(registry.clone(), config.chrome.clone()),
            llm: LlmTools::new(registry.clone(), config.local_llm.clone())?,
            registry,
        })
    }

    /// Restores device IMEs, stops the Chrome host and fails queued LLM jobs.
    pub async fn shutdown(&self) {
        self.android.shutdown().await;
        self.chrome.shutdown().await;
        self.llm.shutdown().await;
    }
}

/// Per-connection MCP handler; cheap to clone.
#[derive(Clone)]
pub struct NamsicServer {
    pub state: Arc<AppState>,
    tool_router: ToolRouter<Self>,
}

impl NamsicServer {
    pub fn new(state: Arc<AppState>) -> Self {
        Self {
            state,
            tool_router: Self::router(),
        }
    }

    pub fn router() -> ToolRouter<Self> {
        Self::session_router() + Self::chrome_router() + Self::android_router() + Self::llm_router()
    }
}

#[tool_handler(router = self.tool_router, name = "namsic-mcp", version = "1.0.0")]
impl ServerHandler for NamsicServer {}

#[cfg(test)]
mod tests {
    use std::collections::HashSet;

    use super::*;

    #[test]
    fn registers_every_tool_exactly_once() {
        let names: Vec<String> = NamsicServer::router()
            .list_all()
            .into_iter()
            .map(|t| t.name.to_string())
            .collect();
        let unique: HashSet<&String> = names.iter().collect();
        assert_eq!(unique.len(), names.len(), "duplicate tool names: {names:?}");
    }
}
