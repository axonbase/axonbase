from dataclasses import dataclass


PROTOCOL_VERSION = 1
SERVER_VERSION = "0.1.0-SNAPSHOT"

METHODS = [
    "ping", "use", "query", "signin", "authenticate", "live", "kill", "version",
    "begin", "commit", "cancel",
    "kv_get", "kv_set", "kv_del", "kv_scan",
    "let", "set", "unset", "select", "create", "insert", "update", "upsert", "delete", "relate",
    "signup", "invalidate", "certificate.begin", "certificate.complete",
]


@dataclass(frozen=True)
class CertificateChallenge:
    id: str
    challenge: str
    expires_at: str


@dataclass(frozen=True)
class CertificateCompletion:
    id: str
    challenge: str
    store: str
    user: str
    chain: list[str]
    signature: str

ERR_PARSE = -32700
ERR_INVALID_REQ = -32600
ERR_METHOD_NOT_FOUND = -32601
ERR_INVALID_PARAMS = -32602
ERR_INTERNAL = -32000
ERR_AUTH = -32002
ERR_RUNTIME = -32003
ERR_TXN_CONFLICT = -32009
ERR_NOT_LEADER = -32010
ERR_NO_QUORUM = -32011
ERR_RATE_LIMIT = -32029
