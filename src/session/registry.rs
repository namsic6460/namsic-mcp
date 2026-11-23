#[cfg(test)]
use std::path::Path;
use std::path::PathBuf;
use std::sync::{Arc, LazyLock};

use chrono::{DateTime, Utc};
use dashmap::DashMap;
use regex::Regex;
use uuid::Uuid;

use crate::error::ToolError;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Project {
    pub session_id: String,
    pub project_name: String,
    pub screenshot_dir: PathBuf,
    pub created_at: DateTime<Utc>,
}

/// Project-scoped sessions keyed by UUID. In-memory only; a restart issues new IDs.
#[derive(Debug)]
pub struct ProjectSessionRegistry {
    screenshot_base_dir: PathBuf,
    by_id: DashMap<String, Arc<Project>>,
    by_name: DashMap<String, String>,
    init_lock: std::sync::Mutex<()>,
}

static UNSAFE_CHARS: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"[^A-Za-z0-9._-]").unwrap());

impl ProjectSessionRegistry {
    pub fn new(screenshot_base_dir: impl Into<PathBuf>) -> Self {
        Self {
            screenshot_base_dir: screenshot_base_dir.into(),
            by_id: DashMap::new(),
            by_name: DashMap::new(),
            init_lock: std::sync::Mutex::new(()),
        }
    }

    #[cfg(test)]
    pub fn screenshot_base_dir(&self) -> &Path {
        &self.screenshot_base_dir
    }

    /// Idempotent per sanitized project name.
    pub fn init(&self, project_name: Option<&str>) -> Result<Arc<Project>, ToolError> {
        let name = project_name.unwrap_or("");
        if name.trim().is_empty() {
            return Err(ToolError::arg("projectName is required"));
        }
        let normalized = sanitize(name);
        let _guard = self.init_lock.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(existing_id) = self.by_name.get(&normalized).map(|e| e.value().clone())
            && let Some(existing) = self.by_id.get(&existing_id)
        {
            return Ok(existing.value().clone());
        }
        let dir = self.screenshot_base_dir.join(&normalized);
        std::fs::create_dir_all(&dir).map_err(|e| {
            ToolError::state(format!(
                "Failed to create screenshot directory: {}: {e}",
                dir.display()
            ))
        })?;
        let project = Arc::new(Project {
            session_id: Uuid::new_v4().to_string(),
            project_name: normalized.clone(),
            screenshot_dir: dir,
            created_at: Utc::now(),
        });
        self.by_id
            .insert(project.session_id.clone(), project.clone());
        self.by_name.insert(normalized, project.session_id.clone());
        Ok(project)
    }

    pub fn require(&self, session_id: Option<&str>) -> Result<Arc<Project>, ToolError> {
        let sid = session_id.unwrap_or("");
        if sid.trim().is_empty() {
            return Err(ToolError::state(
                "sessionId is required. Call project_init first to obtain one.",
            ));
        }
        self.by_id
            .get(sid)
            .map(|p| p.value().clone())
            .ok_or_else(|| {
                ToolError::state(format!(
                    "Unknown sessionId={sid}. Call project_init first to obtain a valid session."
                ))
            })
    }
}

pub fn sanitize(name: &str) -> String {
    if name.trim().is_empty() {
        return "default".to_owned();
    }
    let replaced = UNSAFE_CHARS.replace_all(name, "_").into_owned();
    if replaced.trim().is_empty() {
        "default".to_owned()
    } else {
        replaced
    }
}
