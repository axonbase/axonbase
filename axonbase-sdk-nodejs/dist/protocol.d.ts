/** AxonBase protocol constants. */
export declare const PROTOCOL_VERSION = 1;
export declare const SERVER_VERSION = "0.1.0-SNAPSHOT";
export declare const METHODS: readonly ["ping", "use", "query", "signin", "authenticate", "live", "kill", "version", "begin", "commit", "cancel", "kv_get", "kv_set", "kv_del", "kv_scan", "let", "set", "unset", "select", "create", "insert", "update", "upsert", "delete", "relate", "signup", "invalidate", "certificate.begin", "certificate.complete"];
export type Method = typeof METHODS[number];
export declare const ERR_PARSE = -32700;
export declare const ERR_INVALID_REQ = -32600;
export declare const ERR_METHOD_NOT_FOUND = -32601;
export declare const ERR_INVALID_PARAMS = -32602;
export declare const ERR_INTERNAL = -32000;
export declare const ERR_AUTH = -32002;
export declare const ERR_RUNTIME = -32003;
export declare const ERR_TXN_CONFLICT = -32009;
export declare const ERR_NOT_LEADER = -32010;
export declare const ERR_NO_QUORUM = -32011;
export declare const ERR_RATE_LIMIT = -32029;
export declare const PROTOCOL_MISMATCH = -32600;
export interface AxonError {
    code: number;
    message: string;
    kind?: string;
    leader?: string;
    leader_address?: string;
    protocol?: number;
    version?: string;
}
export interface HelloFrame {
    hello: {
        protocol: number;
        server: string;
        methods: string[];
    };
}
export interface NotificationFrame {
    notification: {
        id: string;
        action: "CREATE" | "UPDATE" | "DELETE";
        result: unknown;
    };
}
export interface RpcRequest {
    id: number;
    method: string;
    params: unknown[];
    version?: number;
}
export interface RpcResponse {
    id: number | null;
    result?: unknown;
    error?: AxonError;
}
export interface Status {
    node_id: string;
    cluster_id: string;
    role: string;
    leader: string;
    leader_address: string;
    term: number;
    commit_index: number;
    active: number;
    members: number;
    quorum: number;
    ready: boolean;
}
export interface KvEntry {
    key: string;
    value: unknown;
    ttl?: number;
}
export interface CertificateChallenge {
    id: string;
    challenge: string;
    expires_at: string;
}
export interface CertificateCompletion {
    id: string;
    challenge: string;
    store: string;
    user: string;
    chain: string[];
    signature: string;
}
