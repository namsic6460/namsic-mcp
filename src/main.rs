mod android;
mod chrome;
mod config;
mod error;
mod llm;
mod process;
mod result;
mod server;
mod session;
mod util;

use std::net::SocketAddr;
use std::sync::Arc;

use anyhow::Context;
use rmcp::transport::streamable_http_server::session::local::{LocalSessionManager, SessionConfig};
use rmcp::transport::streamable_http_server::{StreamableHttpServerConfig, StreamableHttpService};
use tokio_util::sync::CancellationToken;
use tracing_subscriber::EnvFilter;

use crate::config::{Config, MCP_PATH, MCP_SESSION_MAX_IDLE};
use crate::server::{AppState, NamsicServer};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let config = Config::from_env()?;
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    let state = Arc::new(AppState::new(&config)?);
    let ct = CancellationToken::new();

    let mut session_config = SessionConfig::default();
    session_config.keep_alive = Some(MCP_SESSION_MAX_IDLE);
    let mut session_manager = LocalSessionManager::default();
    session_manager.session_config = session_config;
    let factory_state = state.clone();
    let service = StreamableHttpService::new(
        move || Ok(NamsicServer::new(factory_state.clone())),
        Arc::new(session_manager),
        StreamableHttpServerConfig::default().with_cancellation_token(ct.child_token()),
    );
    let app = axum::Router::new().nest_service(MCP_PATH, service);

    let addr = SocketAddr::from(([127, 0, 0, 1], config.port));
    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .with_context(|| format!("failed to bind {addr}"))?;
    tracing::info!("namsic-mcp listening on http://{addr}{MCP_PATH}");

    let shutdown_ct = ct.clone();
    axum::serve(listener, app)
        .with_graceful_shutdown(async move {
            let _ = tokio::signal::ctrl_c().await;
            tracing::info!("Shutting down...");
            shutdown_ct.cancel();
        })
        .await?;

    state.shutdown().await;
    Ok(())
}
