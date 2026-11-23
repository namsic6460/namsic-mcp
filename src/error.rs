use std::fmt;

/// Mirrors the Java split between `IllegalArgumentException` (bad input / not found)
/// and `IllegalStateException` (conflict / queue full / unknown session).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ToolError {
    Arg(String),
    State(String),
}

impl ToolError {
    pub fn arg(msg: impl Into<String>) -> Self {
        Self::Arg(msg.into())
    }

    pub fn state(msg: impl Into<String>) -> Self {
        Self::State(msg.into())
    }

    pub fn message(&self) -> &str {
        match self {
            Self::Arg(m) | Self::State(m) => m,
        }
    }

    #[cfg(test)]
    pub fn is_arg(&self) -> bool {
        matches!(self, Self::Arg(_))
    }

    #[cfg(test)]
    pub fn is_state(&self) -> bool {
        matches!(self, Self::State(_))
    }
}

impl fmt::Display for ToolError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.message())
    }
}

impl std::error::Error for ToolError {}
