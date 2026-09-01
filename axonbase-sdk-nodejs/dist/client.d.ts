import WebSocket from "ws";
import { HelloFrame, CertificateChallenge, CertificateCompletion, KvEntry } from "./protocol.js";
export type LiveHandler = (id: string, action: "CREATE" | "UPDATE" | "DELETE", result: unknown) => void;
export interface AxonOptions {
    /** Timeout padrão para chamadas RPC (ms). */
    timeout?: number;
    /** Reconectar automaticamente ao cair. */
    reconnect?: boolean;
    /** Intervalo entre tentativas de reconexão (ms). */
    reconnectInterval?: number;
    /** Máximo de tentativas de reconexão. */
    maxReconnectAttempts?: number;
    /** TLS and WebSocket client options, including mTLS certificates. */
    tls?: WebSocket.ClientOptions;
}
/**
 * Cliente AxonBase para Node.js/TypeScript.
 *
 * ```ts
 * const axon = await Axon.connect("ws://127.0.0.1:8000/rpc/ws");
 * await axon.use("test", "dev");
 * const result = await axon.query("SELECT * FROM person");
 * axon.close();
 * ```
 */
export declare class Axon {
    private ws;
    private url;
    private readonly options;
    private nextId;
    private readonly pending;
    private readonly liveHandlers;
    private handshakeReceived;
    private _namespace;
    private _database;
    private _auth;
    private hello;
    private openPromise;
    private openResolve;
    private closed;
    constructor(url: string, options?: AxonOptions);
    /** Conecta e aguarda o handshake hello. */
    static connect(url: string, options?: AxonOptions): Promise<Axon>;
    private open;
    private onOpen;
    private onMessage;
    private onClose;
    private reconnectLoop;
    private call;
    private callRpc;
    /**
     * Reconecta o WebSocket a um novo URL, preservando sessão, auth e live queries.
     */
    private reconnectTo;
    private callRaw;
    ping(): Promise<boolean>;
    use(ns: string, db: string): Promise<{
        namespace: string;
        database: string;
    }>;
    query(sql: string, vars?: Record<string, unknown>): Promise<unknown>;
    signin(user: string, pass: string, access?: string): Promise<string>;
    authenticate(token: string): Promise<void>;
    signup(user: string, pass: string): Promise<string>;
    invalidate(): Promise<void>;
    certificateBegin(store: string): Promise<CertificateChallenge>;
    certificateComplete(completion: CertificateCompletion): Promise<string>;
    version(): Promise<string>;
    select(what: string): Promise<unknown>;
    create(what: string, data: Record<string, unknown>): Promise<unknown>;
    update(what: string, data: Record<string, unknown>): Promise<unknown>;
    upsert(what: string, data: Record<string, unknown>): Promise<unknown>;
    delete(what: string): Promise<unknown>;
    insert(table: string, data: Record<string, unknown>): Promise<unknown>;
    relate(from: string, kind: string, to: string, data?: Record<string, unknown>): Promise<unknown>;
    letVar(name: string, value: unknown): Promise<void>;
    unset(name: string): Promise<void>;
    begin(): Promise<void>;
    commit(): Promise<void>;
    cancel(): Promise<void>;
    kvGet(ns: string, db: string, key: string): Promise<unknown>;
    kvSet(ns: string, db: string, key: string, value: unknown, ttl?: number): Promise<unknown>;
    kvDel(ns: string, db: string, key: string): Promise<boolean>;
    kvScan(ns: string, db: string, prefix: string): Promise<KvEntry[]>;
    live(table: string, handler: LiveHandler, diff?: boolean): Promise<string>;
    kill(id: string): Promise<boolean>;
    get namespace(): string;
    get database(): string;
    get isConnected(): boolean;
    get helloInfo(): HelloFrame["hello"] | null;
    close(): void;
}
