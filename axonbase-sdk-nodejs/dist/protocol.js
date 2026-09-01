"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.PROTOCOL_MISMATCH = exports.ERR_RATE_LIMIT = exports.ERR_NO_QUORUM = exports.ERR_NOT_LEADER = exports.ERR_TXN_CONFLICT = exports.ERR_RUNTIME = exports.ERR_AUTH = exports.ERR_INTERNAL = exports.ERR_INVALID_PARAMS = exports.ERR_METHOD_NOT_FOUND = exports.ERR_INVALID_REQ = exports.ERR_PARSE = exports.METHODS = exports.SERVER_VERSION = exports.PROTOCOL_VERSION = void 0;
/** AxonBase protocol constants. */
exports.PROTOCOL_VERSION = 1;
exports.SERVER_VERSION = "0.1.0-SNAPSHOT";
exports.METHODS = [
    "ping", "use", "query", "signin", "authenticate", "live", "kill", "version",
    "begin", "commit", "cancel",
    "kv_get", "kv_set", "kv_del", "kv_scan",
    "let", "set", "unset", "select", "create", "insert", "update", "upsert", "delete", "relate",
    "signup", "invalidate", "certificate.begin", "certificate.complete",
];
/* Códigos de erro */
exports.ERR_PARSE = -32700;
exports.ERR_INVALID_REQ = -32600;
exports.ERR_METHOD_NOT_FOUND = -32601;
exports.ERR_INVALID_PARAMS = -32602;
exports.ERR_INTERNAL = -32000;
exports.ERR_AUTH = -32002;
exports.ERR_RUNTIME = -32003;
exports.ERR_TXN_CONFLICT = -32009;
exports.ERR_NOT_LEADER = -32010;
exports.ERR_NO_QUORUM = -32011;
exports.ERR_RATE_LIMIT = -32029;
exports.PROTOCOL_MISMATCH = -32600; // com kind="PROTOCOL_MISMATCH"
