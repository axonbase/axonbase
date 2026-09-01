use thiserror::Error;

use crate::protocol;

#[derive(Debug, Clone, Error)]
pub enum AxonError {
    #[error("{0}")]
    Sdk(String),

    #[error("NOT_LEADER: {message} (leader={leader}, addr={leader_address})")]
    NotLeader {
        message: String,
        leader: String,
        leader_address: String,
    },

    #[error("NO_QUORUM: {0}")]
    NoQuorum(String),

    #[error("RATE_LIMIT: {0}")]
    RateLimit(String),

    #[error("PROTOCOL_MISMATCH: {message} (protocol={protocol}, version={version})")]
    ProtocolMismatch {
        message: String,
        protocol: u32,
        version: String,
    },

    #[error("AUTH: {0}")]
    Auth(String),

    #[error("CONFLICT: {0}")]
    Conflict(String),
}

impl AxonError {
    pub fn code(&self) -> i32 {
        match self {
            AxonError::Sdk(_) => 0,
            AxonError::NotLeader { .. } => protocol::ERR_NOT_LEADER,
            AxonError::NoQuorum(_) => protocol::ERR_NO_QUORUM,
            AxonError::RateLimit(_) => protocol::ERR_RATE_LIMIT,
            AxonError::ProtocolMismatch { .. } => protocol::ERR_INVALID_REQ,
            AxonError::Auth(_) => protocol::ERR_AUTH,
            AxonError::Conflict(_) => protocol::ERR_TXN_CONFLICT,
        }
    }
}

impl From<protocol::RpcError> for AxonError {
    fn from(err: protocol::RpcError) -> Self {
        match err.code {
            protocol::ERR_NOT_LEADER => AxonError::NotLeader {
                message: err.message,
                leader: err.leader.unwrap_or_default(),
                leader_address: err.leader_address.unwrap_or_default(),
            },
            protocol::ERR_NO_QUORUM => AxonError::NoQuorum(err.message),
            protocol::ERR_RATE_LIMIT => AxonError::RateLimit(err.message),
            protocol::ERR_AUTH => AxonError::Auth(err.message),
            protocol::ERR_TXN_CONFLICT => AxonError::Conflict(err.message),
            _ if err.kind.as_deref() == Some("PROTOCOL_MISMATCH") => AxonError::ProtocolMismatch {
                message: err.message,
                protocol: err.protocol.unwrap_or(0),
                version: err.version.unwrap_or_default(),
            },
            _ => AxonError::Sdk(err.message),
        }
    }
}

impl From<AxonError> for protocol::RpcError {
    fn from(err: AxonError) -> Self {
        let (code, message) = match &err {
            AxonError::Sdk(m) => (0, m.clone()),
            AxonError::NotLeader { message, .. } => (protocol::ERR_NOT_LEADER, message.clone()),
            AxonError::NoQuorum(m) => (protocol::ERR_NO_QUORUM, m.clone()),
            AxonError::RateLimit(m) => (protocol::ERR_RATE_LIMIT, m.clone()),
            AxonError::ProtocolMismatch { message, .. } => {
                (protocol::ERR_INVALID_REQ, message.clone())
            }
            AxonError::Auth(m) => (protocol::ERR_AUTH, m.clone()),
            AxonError::Conflict(m) => (protocol::ERR_TXN_CONFLICT, m.clone()),
        };
        protocol::RpcError {
            code,
            message,
            kind: None,
            leader: None,
            leader_address: None,
            protocol: None,
            version: None,
        }
    }
}
