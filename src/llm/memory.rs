//! Persistent project-scoped reference memory, file-compatible with the Java store
//! (`<sha256(project)>.json`, schemaVersion 1, `.lock` cross-process lock).

use std::cmp::Ordering;
use std::collections::HashSet;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use uuid::Uuid;

use super::MemorySaveParams;
use super::util::{instant_string, java_is_blank, java_strip, utf16_len};
use crate::error::ToolError;
use crate::session::ProjectSessionRegistry;

const MAX_STORE_BYTES: u64 = 64 * 1024 * 1024;
const MAX_ENTRIES: usize = 1000;
const MAX_REFERENCE: usize = 16000;
const STATUSES: [&str; 3] = ["candidate", "confirmed", "archived"];

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Entry {
    pub id: String,
    pub revision: i64,
    pub title: String,
    pub content: String,
    pub tags: Vec<String>,
    pub scope: String,
    pub status: String,
    pub evidence: String,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Document {
    #[serde(default)]
    schema_version: i32,
    #[serde(default)]
    project: Option<String>,
    #[serde(default)]
    entries: Option<Vec<Entry>>,
}

pub struct LocalLlmMemoryStore {
    directory: PathBuf,
    registry: Arc<ProjectSessionRegistry>,
    /// Java `synchronized`; the file lock additionally serializes other processes.
    monitor: Mutex<()>,
}

fn io_err(e: impl std::fmt::Display) -> ToolError {
    // Java IOExceptions; the tool layer only reports the message.
    ToolError::state(e.to_string())
}

fn text(value: Option<&str>, field: &str, max: usize, required: bool) -> Result<(), ToolError> {
    let missing = value.is_none_or(java_is_blank);
    if (required && missing) || value.is_some_and(|v| utf16_len(v) > max) {
        return Err(ToolError::arg(format!(
            "{field} is required when applicable and must be <= {max} characters"
        )));
    }
    Ok(())
}

fn digest(project: &str) -> String {
    hex::encode(Sha256::digest(project.as_bytes()))
}

fn require<'a>(document: &'a [Entry], id: &str) -> Result<&'a Entry, ToolError> {
    document
        .iter()
        .find(|e| e.id == id)
        .ok_or_else(|| ToolError::arg("Unknown memory ID in this project"))
}

/// Newest `updatedAt` first, then id. Compares instants rather than the raw strings so that
/// Java's variable fractional digits (`...12Z` vs `...12.051Z`) sort chronologically.
fn newest_first(a: &Entry, b: &Entry) -> Ordering {
    let parse = |s: &str| {
        DateTime::parse_from_rfc3339(s)
            .ok()
            .map(|d| d.with_timezone(&Utc))
    };
    let by_time = match (parse(&a.updated_at), parse(&b.updated_at)) {
        (Some(x), Some(y)) => y.cmp(&x),
        _ => b.updated_at.cmp(&a.updated_at),
    };
    by_time.then_with(|| a.id.cmp(&b.id))
}

impl LocalLlmMemoryStore {
    pub fn new(directory: &Path, registry: Arc<ProjectSessionRegistry>) -> Self {
        let directory = std::path::absolute(directory).unwrap_or_else(|_| directory.to_path_buf());
        Self {
            directory,
            registry,
            monitor: Mutex::new(()),
        }
    }

    pub fn save(&self, p: &MemorySaveParams) -> Result<Entry, ToolError> {
        let status = p.status.as_str();
        text(Some(&p.title), "title", 200, true)?;
        text(Some(&p.content), "content", 8000, true)?;
        text(Some(&p.scope), "scope", 1000, true)?;
        text(
            p.evidence.as_deref(),
            "evidence",
            2000,
            status == "confirmed",
        )?;
        if !STATUSES.contains(&status) {
            return Err(ToolError::arg(
                "status must be candidate, confirmed or archived",
            ));
        }
        let tags = p.tags.clone().unwrap_or_default();
        if tags.len() > 20 {
            return Err(ToolError::arg("At most 20 tags"));
        }
        for tag in &tags {
            text(Some(tag), "tag", 100, true)?;
        }
        let explicit_id = p.id.as_deref().filter(|id| !java_is_blank(id));
        let memory_id = explicit_id.map_or_else(|| Uuid::new_v4().to_string(), str::to_owned);
        let expected = p.expected_revision;
        self.locked(&p.session_id, |path, project, mut entries| {
            let old = entries.iter().find(|e| e.id == memory_id).cloned();
            if old.is_none() && explicit_id.is_some() {
                return Err(ToolError::arg("Unknown memory ID"));
            }
            if expected != old.as_ref().map_or(0, |o| o.revision) {
                return Err(ToolError::state(
                    "Memory revision conflict; fetch latest before retrying",
                ));
            }
            if old.is_none() && entries.len() >= MAX_ENTRIES {
                return Err(ToolError::state("Project memory limit is 1000"));
            }
            let now = instant_string(Utc::now());
            let entry = Entry {
                id: memory_id.clone(),
                revision: expected + 1,
                title: p.title.clone(),
                content: p.content.clone(),
                tags: tags.clone(),
                scope: p.scope.clone(),
                status: p.status.clone(),
                evidence: p.evidence.clone().unwrap_or_default(),
                created_at: old.map_or_else(|| now.clone(), |o| o.created_at),
                updated_at: now,
            };
            entries.retain(|e| e.id != memory_id);
            entries.push(entry.clone());
            self.write(path, project, entries)?;
            Ok(entry)
        })
    }

    pub fn get(&self, session_id: &str, id: &str) -> Result<Entry, ToolError> {
        self.locked(session_id, |_, _, entries| require(&entries, id).cloned())
    }

    pub fn search(
        &self,
        session_id: &str,
        query: Option<&str>,
        status: &str,
        offset: i32,
        limit: i32,
    ) -> Result<Value, ToolError> {
        text(query, "query", 1000, false)?;
        if offset < 0 || !(1..=100).contains(&limit) {
            return Err(ToolError::arg("offset >= 0, limit 1..100"));
        }
        if !STATUSES.contains(&status) && status != "all" {
            return Err(ToolError::arg("Invalid status filter"));
        }
        let needle = java_strip(query.unwrap_or("")).to_lowercase();
        self.locked(session_id, |_, _, entries| {
            let mut matches: Vec<Entry> = entries
                .into_iter()
                .filter(|e| status == "all" || status == e.status)
                .filter(|e| {
                    needle.is_empty()
                        || format!("{} {} {} {}", e.title, e.content, e.scope, e.tags.join(" "))
                            .to_lowercase()
                            .contains(&needle)
                })
                .collect();
            matches.sort_by(newest_first);
            let total = matches.len();
            // Validated non-negative / 1..=100 above.
            let page: Vec<Entry> = matches
                .into_iter()
                .skip(usize::try_from(offset).unwrap_or(usize::MAX))
                .take(usize::try_from(limit).unwrap_or(0))
                .collect();
            Ok(json!({"total": total, "offset": offset, "entries": page}))
        })
    }

    pub fn delete(
        &self,
        session_id: &str,
        id: &str,
        expected_revision: i64,
    ) -> Result<Value, ToolError> {
        self.locked(session_id, |path, project, mut entries| {
            if require(&entries, id)?.revision != expected_revision {
                return Err(ToolError::state("Memory revision conflict"));
            }
            entries.retain(|e| e.id != id);
            self.write(path, project, entries)?;
            Ok(json!({"deleted": id, "revision": expected_revision}))
        })
    }

    pub fn context(&self, session_id: &str, ids: &[String]) -> Result<Value, ToolError> {
        let distinct: HashSet<&String> = ids.iter().collect();
        if ids.is_empty() || ids.len() > 10 || distinct.len() != ids.len() {
            return Err(ToolError::arg("Select 1..10 distinct confirmed memory IDs"));
        }
        self.locked(session_id, |_, _, entries| {
            let mut selected = Vec::with_capacity(ids.len());
            for id in ids {
                let entry = require(&entries, id)?;
                if entry.status != "confirmed" {
                    return Err(ToolError::arg("Only confirmed memories can be composed"));
                }
                selected.push(entry.clone());
            }
            let reference = serde_json::to_string(&json!({
                "notice": "Reference cases only, not current evidence. Check scope and applicability.",
                "memories": selected,
            }))
            .map_err(io_err)?;
            let characters = utf16_len(&reference);
            if characters > MAX_REFERENCE {
                return Err(ToolError::arg(
                    "Selected memory exceeds 16000 characters; select fewer",
                ));
            }
            let selected: Vec<Value> = selected
                .iter()
                .map(|e| json!({"id": e.id, "revision": e.revision}))
                .collect();
            Ok(json!({"referenceMemory": reference, "characters": characters, "selected": selected}))
        })
    }

    fn locked<T>(
        &self,
        session_id: &str,
        operation: impl FnOnce(&Path, &str, Vec<Entry>) -> Result<T, ToolError>,
    ) -> Result<T, ToolError> {
        let _monitor = self.monitor.lock().unwrap_or_else(|e| e.into_inner());
        let project = self
            .registry
            .require(Some(session_id))?
            .project_name
            .clone();
        fs::create_dir_all(&self.directory).map_err(io_err)?;
        let key = digest(&project);
        let path = self.directory.join(format!("{key}.json"));
        // Serialize multiple MCP processes as well as concurrent requests.
        let lock_file = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(false)
            .open(self.directory.join(format!("{key}.lock")))
            .map_err(io_err)?;
        fs4::FileExt::lock(&lock_file).map_err(|_| io_err("Memory lock unavailable"))?;
        let entries = if path.exists() {
            let size = fs::metadata(&path).map_err(io_err)?.len();
            if size > MAX_STORE_BYTES {
                return Err(io_err("Memory store exceeds size limit"));
            }
            let raw = fs::read_to_string(&path).map_err(io_err)?;
            let document: Document = serde_json::from_str(&raw).map_err(io_err)?;
            match document {
                Document {
                    schema_version: 1,
                    project: Some(stored),
                    entries: Some(entries),
                } if stored == project => entries,
                _ => return Err(io_err("Invalid memory store; refusing to overwrite")),
            }
        } else {
            Vec::new()
        };
        // The OS releases the lock when `lock_file` is closed at the end of this scope.
        operation(&path, &project, entries)
    }

    fn write(&self, path: &Path, project: &str, entries: Vec<Entry>) -> Result<(), ToolError> {
        let document = Document {
            schema_version: 1,
            project: Some(project.to_owned()),
            entries: Some(entries),
        };
        let body = serde_json::to_string(&document).map_err(io_err)?;
        let mut temporary = tempfile::Builder::new()
            .prefix("memory-")
            .suffix(".tmp")
            .tempfile_in(&self.directory)
            .map_err(io_err)?;
        temporary.write_all(body.as_bytes()).map_err(io_err)?;
        // Replaces atomically; the temp file is removed on failure when dropped.
        temporary.persist(path).map_err(|e| io_err(e.error))?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use std::thread;

    use super::*;

    struct Fixture {
        _dir: tempfile::TempDir,
        root: PathBuf,
        store: Arc<LocalLlmMemoryStore>,
        session: String,
    }

    fn fixture() -> Fixture {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().to_path_buf();
        let registry = Arc::new(ProjectSessionRegistry::new(root.join("shots")));
        let session = registry.init(Some("project-a")).unwrap().session_id.clone();
        let store = Arc::new(LocalLlmMemoryStore::new(&root.join("memory"), registry));
        Fixture {
            _dir: dir,
            root,
            store,
            session,
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn params(
        session: &str,
        id: Option<&str>,
        expected_revision: i64,
        title: &str,
        content: &str,
        tags: &[&str],
        scope: &str,
        status: &str,
        evidence: &str,
    ) -> MemorySaveParams {
        MemorySaveParams {
            session_id: session.to_owned(),
            id: id.map(str::to_owned),
            expected_revision,
            title: title.to_owned(),
            content: content.to_owned(),
            tags: Some(tags.iter().map(|t| (*t).to_owned()).collect()),
            scope: scope.to_owned(),
            status: status.to_owned(),
            evidence: Some(evidence.to_owned()),
        }
    }

    impl Fixture {
        fn save(&self, status: &str, evidence: &str) -> Result<Entry, ToolError> {
            self.store.save(&params(
                &self.session,
                None,
                0,
                "설정창 Scroll",
                "넘침이 없으면 스크롤은 미검증",
                &["설정", "overflow"],
                "SettingsDialog / Android / version 1",
                status,
                evidence,
            ))
        }

        fn update(
            &self,
            entry: &Entry,
            expected: i64,
            content: &str,
            status: &str,
            evidence: &str,
        ) -> Result<Entry, ToolError> {
            let tags: Vec<&str> = entry.tags.iter().map(String::as_str).collect();
            self.store.save(&params(
                &self.session,
                Some(&entry.id),
                expected,
                &entry.title,
                content,
                &tags,
                &entry.scope,
                status,
                evidence,
            ))
        }
    }

    #[test]
    fn persists_across_registry_restart_and_separates_projects() {
        let f = fixture();
        let entry = f.save("confirmed", "run/shot.png: 재검수 확인").unwrap();
        let fresh = Arc::new(ProjectSessionRegistry::new(f.root.join("shots")));
        let reloaded = LocalLlmMemoryStore::new(&f.root.join("memory"), fresh.clone());
        let new_session = fresh.init(Some("project-a")).unwrap().session_id.clone();
        assert_ne!(f.session, new_session);
        assert_eq!(entry, reloaded.get(&new_session, &entry.id).unwrap());
        let other = fresh.init(Some("project-b")).unwrap().session_id.clone();
        assert!(reloaded.get(&other, &entry.id).unwrap_err().is_arg());
        assert_eq!(
            reloaded.search(&other, Some(""), "all", 0, 20).unwrap()["total"],
            0
        );
    }

    #[test]
    fn requires_evidence_and_only_composes_confirmed_entries() {
        let f = fixture();
        assert!(f.save("confirmed", "").unwrap_err().is_arg());
        let candidate = f.save("candidate", "").unwrap();
        assert!(
            f.store
                .context(&f.session, std::slice::from_ref(&candidate.id))
                .unwrap_err()
                .is_arg()
        );
        let confirmed = f
            .update(
                &candidate,
                1,
                &candidate.content,
                "confirmed",
                "proof / inspected",
            )
            .unwrap();
        let context = f
            .store
            .context(&f.session, std::slice::from_ref(&confirmed.id))
            .unwrap();
        let reference = context["referenceMemory"].as_str().unwrap();
        assert!(reference.contains("미검증"));
        assert!(reference.contains("proof / inspected"));
        assert!(reference.starts_with(r#"{"notice":"Reference cases only"#));
        assert_eq!(context["characters"], utf16_len(reference));
        assert_eq!(
            context["selected"],
            json!([{"id": confirmed.id, "revision": 2}])
        );
        assert!(
            f.store
                .context(&f.session, &[confirmed.id.clone(), confirmed.id.clone()])
                .unwrap_err()
                .is_arg()
        );
    }

    #[test]
    fn rejects_stale_updates_and_deletion() {
        let f = fixture();
        let original = f.save("candidate", "").unwrap();
        let updated = f.update(&original, 1, "new", "candidate", "").unwrap();
        assert_eq!(updated.revision, 2);
        assert_eq!(updated.created_at, original.created_at);
        let stale = f.store.save(&params(
            &f.session,
            Some(&original.id),
            1,
            "stale",
            "stale",
            &[],
            "all",
            "candidate",
            "",
        ));
        assert!(stale.unwrap_err().is_state());
        assert!(
            f.store
                .delete(&f.session, &original.id, 1)
                .unwrap_err()
                .is_state()
        );
        let deleted = f.store.delete(&f.session, &original.id, 2).unwrap();
        assert_eq!(
            serde_json::to_string(&deleted).unwrap(),
            format!(r#"{{"deleted":"{}","revision":2}}"#, original.id)
        );
        assert!(f.store.get(&f.session, &original.id).unwrap_err().is_arg());
    }

    #[test]
    fn searches_tags_case_insensitively_with_status_and_pagination() {
        let f = fixture();
        f.save("confirmed", "proof").unwrap();
        f.save("candidate", "").unwrap();
        f.save("archived", "").unwrap();
        let confirmed = f
            .store
            .search(&f.session, Some("OVERFLOW"), "confirmed", 0, 10)
            .unwrap();
        assert_eq!(confirmed["total"], 1);
        let all = f.store.search(&f.session, Some(""), "all", 1, 1).unwrap();
        assert_eq!(all["total"], 3);
        assert_eq!(all["entries"].as_array().unwrap().len(), 1);
        let keys: Vec<&str> = all
            .as_object()
            .unwrap()
            .keys()
            .map(String::as_str)
            .collect();
        assert_eq!(keys, ["total", "offset", "entries"]);
        assert!(
            f.store
                .search(&f.session, Some(""), "all", -1, 10)
                .unwrap_err()
                .is_arg()
        );
        assert!(
            f.store
                .search(&f.session, Some(""), "all", 0, 101)
                .unwrap_err()
                .is_arg()
        );
        assert!(
            f.store
                .search(&f.session, None, "bogus", 0, 10)
                .unwrap_err()
                .is_arg()
        );
    }

    #[test]
    fn refuses_corrupt_store_without_overwriting_it() {
        let f = fixture();
        f.save("candidate", "").unwrap();
        let path = fs::read_dir(f.root.join("memory"))
            .unwrap()
            .map(|e| e.unwrap().path())
            .find(|p| p.extension().is_some_and(|x| x == "json"))
            .unwrap();
        fs::write(&path, "{broken").unwrap();
        assert!(f.save("candidate", "").is_err());
        assert_eq!(fs::read_to_string(&path).unwrap(), "{broken");
        fs::write(
            &path,
            r#"{"schemaVersion":1,"project":"other","entries":[]}"#,
        )
        .unwrap();
        assert_eq!(
            f.save("candidate", "").unwrap_err().message(),
            "Invalid memory store; refusing to overwrite"
        );
    }

    #[test]
    fn bounds_context_without_silently_truncating() {
        let f = fixture();
        let long = "x".repeat(8000);
        let first = f
            .store
            .save(&params(
                &f.session,
                None,
                0,
                "a",
                &long,
                &[],
                "all",
                "confirmed",
                "proof",
            ))
            .unwrap();
        let second = f
            .store
            .save(&params(
                &f.session,
                None,
                0,
                "b",
                &long,
                &[],
                "all",
                "confirmed",
                "proof",
            ))
            .unwrap();
        let err = f
            .store
            .context(&f.session, &[first.id, second.id])
            .unwrap_err();
        assert!(err.is_arg());
        assert_eq!(
            err.message(),
            "Selected memory exceeds 16000 characters; select fewer"
        );
    }

    #[test]
    fn concurrent_revision_updates_do_not_lose_changes() {
        let f = fixture();
        let entry = f.save("candidate", "").unwrap();
        // A second store instance on the same directory exercises the cross-process file lock.
        let registry = f.store.registry.clone();
        let other = Arc::new(LocalLlmMemoryStore::new(&f.root.join("memory"), registry));
        let stores = [f.store.clone(), other, f.store.clone()];
        let handles: Vec<_> = stores
            .into_iter()
            .map(|store| {
                let (session, entry) = (f.session.clone(), entry.clone());
                thread::spawn(move || {
                    let tags: Vec<&str> = entry.tags.iter().map(String::as_str).collect();
                    match store.save(&params(
                        &session,
                        Some(&entry.id),
                        1,
                        &entry.title,
                        "new",
                        &tags,
                        &entry.scope,
                        "candidate",
                        "",
                    )) {
                        Ok(_) => true,
                        Err(e) if e.is_state() => false,
                        Err(e) => panic!("{e}"),
                    }
                })
            })
            .collect();
        let success = handles
            .into_iter()
            .map(|h| h.join().unwrap())
            .filter(|ok| *ok)
            .count();
        assert_eq!(success, 1);
        assert_eq!(f.store.get(&f.session, &entry.id).unwrap().revision, 2);
    }

    #[test]
    fn validation_messages_match_java() {
        let f = fixture();
        let long_title = "가".repeat(201);
        let err = f
            .store
            .save(&params(
                &f.session,
                None,
                0,
                &long_title,
                "c",
                &[],
                "s",
                "candidate",
                "",
            ))
            .unwrap_err();
        assert_eq!(
            err.message(),
            "title is required when applicable and must be <= 200 characters"
        );
        let err = f
            .store
            .save(&params(
                &f.session,
                None,
                0,
                "t",
                "c",
                &[],
                "s",
                "draft",
                "",
            ))
            .unwrap_err();
        assert_eq!(
            err.message(),
            "status must be candidate, confirmed or archived"
        );
        let many: Vec<String> = (0..21).map(|i| i.to_string()).collect();
        let many: Vec<&str> = many.iter().map(String::as_str).collect();
        let err = f
            .store
            .save(&params(
                &f.session,
                None,
                0,
                "t",
                "c",
                &many,
                "s",
                "candidate",
                "",
            ))
            .unwrap_err();
        assert_eq!(err.message(), "At most 20 tags");
        let err = f
            .store
            .save(&params(
                &f.session,
                Some("missing"),
                0,
                "t",
                "c",
                &[],
                "s",
                "candidate",
                "",
            ))
            .unwrap_err();
        assert_eq!(err.message(), "Unknown memory ID");
        let err = f.store.context(&f.session, &[]).unwrap_err();
        assert_eq!(err.message(), "Select 1..10 distinct confirmed memory IDs");
    }

    #[test]
    fn reads_java_written_store_with_variable_fraction_digits() {
        let f = fixture();
        let dir = f.root.join("memory");
        fs::create_dir_all(&dir).unwrap();
        let java = r#"{"schemaVersion":1,"project":"project-a","entries":[{"id":"b","revision":1,"title":"t","content":"c","tags":[],"scope":"s","status":"confirmed","evidence":"e","createdAt":"2026-11-22T21:45:12Z","updatedAt":"2026-11-22T21:45:12Z"},{"id":"a","revision":3,"title":"t2","content":"c","tags":["x"],"scope":"s","status":"confirmed","evidence":"e","createdAt":"2026-11-22T21:45:12.051234Z","updatedAt":"2026-11-22T21:45:12.051234Z"}]}"#;
        fs::write(dir.join(format!("{}.json", digest("project-a"))), java).unwrap();
        let result = f
            .store
            .search(&f.session, None, "confirmed", 0, 20)
            .unwrap();
        let ids: Vec<&str> = result["entries"]
            .as_array()
            .unwrap()
            .iter()
            .map(|e| e["id"].as_str().unwrap())
            .collect();
        assert_eq!(ids, ["a", "b"]);
        let entry = f.store.get(&f.session, "a").unwrap();
        assert_eq!(
            serde_json::to_string(&entry).unwrap(),
            r#"{"id":"a","revision":3,"title":"t2","content":"c","tags":["x"],"scope":"s","status":"confirmed","evidence":"e","createdAt":"2026-11-22T21:45:12.051234Z","updatedAt":"2026-11-22T21:45:12.051234Z"}"#
        );
        // A write round-trips the Java layout and keeps untouched entries byte-identical.
        f.update(&entry, 3, "c2", "confirmed", "e").unwrap();
        let raw = fs::read_to_string(dir.join(format!("{}.json", digest("project-a")))).unwrap();
        assert!(raw.starts_with(
            r#"{"schemaVersion":1,"project":"project-a","entries":[{"id":"b","revision":1,"#
        ));
    }
}
