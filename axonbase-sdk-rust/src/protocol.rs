use serde::{Deserialize, Serialize};

pub const PROTOCOL_VERSION: u32 = 1;
pub const SERVER_VERSION: &str = "0.1.0-SNAPSHOT";

pub const METHODS: &[&str] = &[
    "ping",
    "use",
    "query",
    "signin",
    "authenticate",
    "live",
    "kill",
    "version",
    "begin",
    "commit",
    "cancel",
    "kv_get",
    "kv_set",
    "kv_del",
    "kv_scan",
    "let",
    "set",
    "unset",
    "select",
    "create",
    "insert",
    "update",
    "upsert",
    "delete",
    "relate",
    "signup",
    "invalidate",
    "certificate.begin",
    "certificate.complete",
];

pub const ERR_PARSE: i32 = -32700;
pub const ERR_INVALID_REQ: i32 = -32600;
pub const ERR_METHOD_NOT_FOUND: i32 = -32601;
pub const ERR_INVALID_PARAMS: i32 = -32602;
pub const ERR_INTERNAL: i32 = -32000;
pub const ERR_AUTH: i32 = -32002;
pub const ERR_RUNTIME: i32 = -32003;
pub const ERR_TXN_CONFLICT: i32 = -32009;
pub const ERR_NOT_LEADER: i32 = -32010;
pub const ERR_NO_QUORUM: i32 = -32011;
pub const ERR_RATE_LIMIT: i32 = -32029;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HelloFrame {
    pub hello: HelloPayload,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HelloPayload {
    pub protocol: u32,
    pub server: String,
    pub methods: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NotificationFrame {
    pub notification: NotificationPayload,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NotificationPayload {
    pub id: String,
    pub action: String,
    #[serde(default)]
    pub result: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RpcRequest {
    pub id: u32,
    pub method: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub params: Option<Vec<serde_json::Value>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub version: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RpcResponse {
    pub id: Option<u32>,
    #[serde(default)]
    pub result: Option<serde_json::Value>,
    #[serde(default)]
    pub error: Option<RpcError>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RpcError {
    pub code: i32,
    pub message: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub leader: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub leader_address: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub protocol: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KvEntry {
    pub key: String,
    pub value: serde_json::Value,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ttl: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Status {
    pub node_id: String,
    pub cluster_id: String,
    pub role: String,
    pub leader: String,
    pub leader_address: String,
    pub term: u64,
    pub commit_index: u64,
    pub active: u32,
    pub members: u32,
    pub quorum: u32,
    pub ready: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CertificateChallenge {
    pub id: String,
    pub challenge: String,
    pub expires_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CertificateCompletion {
    pub id: String,
    pub challenge: String,
    pub store: String,
    pub user: String,
    pub chain: Vec<String>,
    pub signature: String,
}
