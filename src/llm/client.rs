//! OpenAI-compatible chat completion client for the loopback Qwen backend.

use std::time::Duration;

use serde_json::{Value, json};

use crate::config::LocalLlmConfig;

/// Java simple class name of the failure, reported in the job error.
pub type FailureKind = &'static str;

pub const IO_EXCEPTION: FailureKind = "IOException";

pub struct LocalLlmClient {
    http: reqwest::Client,
    base_url: String,
    model: String,
    request_timeout: Duration,
}

impl LocalLlmClient {
    pub fn new(config: &LocalLlmConfig) -> anyhow::Result<Self> {
        let http = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(5))
            .redirect(reqwest::redirect::Policy::none())
            // Java's HttpClient ignores proxy env vars; the backend is loopback-only.
            .no_proxy()
            // Clients may be shared across runtimes (status vs worker); avoid pooled
            // connections bound to a runtime that might be gone.
            .pool_max_idle_per_host(0)
            .build()?;
        Ok(Self {
            http,
            base_url: config.base_url.clone(),
            model: config.model.clone(),
            request_timeout: config.request_timeout,
        })
    }

    /// `GET /health` returned 200 within 5 seconds.
    pub async fn healthy(&self) -> bool {
        match self
            .http
            .get(format!("{}/health", self.base_url))
            .timeout(Duration::from_secs(5))
            .send()
            .await
        {
            Ok(response) => response.status().as_u16() == 200,
            Err(_) => false,
        }
    }

    pub fn payload(&self, system: &str, content: &[Value]) -> Value {
        json!({
            "model": self.model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": content},
            ],
            "temperature": 0,
            "max_tokens": 1024,
            "chat_template_kwargs": {"enable_thinking": false},
            "response_format": {"type": "json_object"},
        })
    }

    pub async fn complete(&self, system: &str, content: &[Value]) -> Result<Value, FailureKind> {
        let body =
            serde_json::to_string(&self.payload(system, content)).map_err(|_| IO_EXCEPTION)?;
        let response = self
            .http
            .post(format!("{}/v1/chat/completions", self.base_url))
            .timeout(self.request_timeout)
            .header("Content-Type", "application/json")
            .body(body)
            .send()
            .await
            .map_err(|e| request_failure(&e))?;
        if response.status().as_u16() != 200 {
            // Backend response bodies may contain the submitted prompt or image data.
            tracing::debug!("Local LLM HTTP {}", response.status().as_u16());
            return Err(IO_EXCEPTION);
        }
        let text = response.text().await.map_err(|e| request_failure(&e))?;
        let body = parse_json(&text)?;
        if body
            .pointer("/choices/0/finish_reason")
            .and_then(Value::as_str)
            != Some("stop")
        {
            return Err(IO_EXCEPTION);
        }
        Ok(body)
    }
}

fn request_failure(e: &reqwest::Error) -> FailureKind {
    if e.is_timeout() {
        "HttpTimeoutException"
    } else if e.is_connect() {
        "ConnectException"
    } else {
        IO_EXCEPTION
    }
}

/// Parses JSON, naming failures like Jackson 3 does. Blank input is Jackson's
/// `MissingNode`, represented as `Null` (never an object, so later checks fail as IOException).
pub fn parse_json(text: &str) -> Result<Value, FailureKind> {
    if text.trim().is_empty() {
        return Ok(Value::Null);
    }
    serde_json::from_str(text).map_err(|e| {
        if e.classify() == serde_json::error::Category::Eof {
            "UnexpectedEndOfInputException"
        } else {
            "StreamReadException"
        }
    })
}
