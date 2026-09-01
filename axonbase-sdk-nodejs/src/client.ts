import WebSocket from "ws";
import {
  PROTOCOL_VERSION, METHODS, Method,
  ERR_PARSE, ERR_INVALID_REQ, ERR_METHOD_NOT_FOUND, ERR_INVALID_PARAMS,
  ERR_NOT_LEADER, ERR_NO_QUORUM, ERR_RATE_LIMIT,
  PROTOCOL_MISMATCH,
  AxonError, HelloFrame, NotificationFrame, CertificateChallenge, CertificateCompletion,
  RpcRequest, RpcResponse, KvEntry,
} from "./protocol.js";
import { AxonSdkError, NotLeaderError, NoQuorumError, RateLimitError, fromRpcError } from "./errors.js";
import { AxonValue, encodeTyped } from "./codec.js";

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
export class Axon {
  private ws: WebSocket | null = null;
  private url: string;
  private readonly options: Required<Omit<AxonOptions, "tls">> & Pick<AxonOptions, "tls">;
  private nextId = 1;
  private readonly pending = new Map<number, { resolve: (v: RpcResponse) => void; reject: (e: Error) => void; timer: ReturnType<typeof setTimeout> }>();
  private readonly liveHandlers = new Map<string, LiveHandler>();
  private handshakeReceived = false;
  private _namespace = "";
  private _database = "";
  private _auth: string | null = null;
  private hello: HelloFrame["hello"] | null = null;
  private openPromise: Promise<void> | null = null;
  private openResolve: (() => void) | null = null;
  private closed = false;

  constructor(url: string, options?: AxonOptions) {
    this.url = url;
    this.options = {
      timeout: 30_000,
      reconnect: true,
      reconnectInterval: 1_000,
      maxReconnectAttempts: 10,
      ...options,
    };
  }

  // ------------------------------------------------------------------
  // Conexão
  // ------------------------------------------------------------------

  /** Conecta e aguarda o handshake hello. */
  static async connect(url: string, options?: AxonOptions): Promise<Axon> {
    const axon = new Axon(url, options);
    await axon.open();
    return axon;
  }

  private async open(): Promise<void> {
    if (this.openPromise) return this.openPromise;
    this.openPromise = new Promise((resolve, reject) => {
      this.openResolve = resolve;
      try {
        this.ws = new WebSocket(this.url, this.options.tls);
        this.ws.on("open", () => this.onOpen());
        this.ws.on("message", (data) => this.onMessage(data.toString()));
        this.ws.on("close", () => this.onClose());
        this.ws.on("error", (err) => {
          if (!this.handshakeReceived) reject(err);
        });
      } catch (err) {
        reject(err);
      }
    });
    return this.openPromise;
  }

  private onOpen(): void {
    // o handshake hello chega como primeiro frame
  }

  private onMessage(text: string): void {
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch {
      return;
    }

    // Handshake hello
    if (!this.handshakeReceived && isHello(parsed)) {
      this.hello = parsed.hello;
      this.handshakeReceived = true;
      if (this.openResolve) {
        this.openResolve();
        this.openResolve = null;
      }
      return;
    }

    // Notificação live
    if (isNotification(parsed)) {
      const n = parsed.notification;
      const handler = this.liveHandlers.get(n.id);
      if (handler) {
        handler(n.id, n.action, n.result);
      }
      return;
    }

    // Resposta RPC
    const rpc = parsed as RpcResponse;
    if (rpc.id != null) {
      const pending = this.pending.get(rpc.id);
      if (pending) {
        clearTimeout(pending.timer);
        this.pending.delete(rpc.id);
        pending.resolve(rpc);
      }
    }
  }

  private onClose(): void {
    for (const [, pending] of this.pending) {
      clearTimeout(pending.timer);
      pending.reject(new AxonSdkError("conexão fechada"));
    }
    this.pending.clear();
    this.ws = null;
    this.handshakeReceived = false;
    this.openPromise = null;

    // Reconexão automática
    if (!this.closed && this.options.reconnect) {
      this.reconnectLoop();
    }
  }

  private async reconnectLoop(attempt = 0): Promise<void> {
    if (attempt >= this.options.maxReconnectAttempts) return;
    await sleep(this.options.reconnectInterval);
    try {
      await this.open();
      // Re-registrar live queries
      for (const [id] of this.liveHandlers) {
        try {
          await this.call("live", [id, false]);
        } catch {
          // A subscription expirou; remover
          this.liveHandlers.delete(id);
        }
      }
    } catch {
      await this.reconnectLoop(attempt + 1);
    }
  }

  // ------------------------------------------------------------------
  // Chamada RPC
  // ------------------------------------------------------------------

  private async call(method: string, params: unknown[]): Promise<unknown> {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new AxonSdkError("conexão não aberta");
    }
    const id = this.nextId++;
    const request: RpcRequest = { id, method, params: params.map(encodeTyped), version: PROTOCOL_VERSION };
    const text = JSON.stringify(request);

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new AxonSdkError(`timeout em ${method}`));
      }, this.options.timeout);
      this.pending.set(id, { resolve: (r) => resolve(r.result), reject, timer });
      this.ws!.send(text, (err) => {
        if (err) {
          clearTimeout(timer);
          this.pending.delete(id);
          reject(err);
        }
      });
    }).then((result: unknown) => {
      return result;
    }, (err: unknown) => {
      throw err;
    });
  }

  private async callRpc(method: string, params: unknown[]): Promise<unknown> {
    const maxRetries = this.options.reconnect ? 3 : 1;
    for (let attempt = 0; attempt < maxRetries; attempt++) {
      try {
        const response = await this.callRaw(method, params);
        if (response.error) {
          throw fromRpcError(response.error);
        }
        return response.result;
      } catch (err: unknown) {
        if (err instanceof NotLeaderError && err.leaderAddress && attempt < maxRetries - 1) {
          // Reconectar no líder automaticamente
          const newUrl = this.url.startsWith("ws://") ? `ws://${err.leaderAddress}/rpc/ws` : `wss://${err.leaderAddress}/rpc/ws`;
          await this.reconnectTo(newUrl);
          continue;
        }
        throw err;
      }
    }
    throw new AxonSdkError("falha após tentativas de reconexão");
  }

  /**
   * Reconecta o WebSocket a um novo URL, preservando sessão, auth e live queries.
   */
  private async reconnectTo(newUrl: string): Promise<void> {
    if (this.ws) {
      try { this.ws.close(); } catch { /* ignorar */ }
      this.ws = null;
    }
    this.handshakeReceived = false;
    this.hello = null;
    this.openPromise = null;
    this.openResolve = null;
    this.url = newUrl;
    await this.open();
    if (this._namespace) {
      await this.callRaw("use", [this._namespace, this._database]);
    }
    if (this._auth) {
      await this.callRaw("authenticate", [this._auth]);
    }
  }

  private async callRaw(method: string, params: unknown[]): Promise<RpcResponse> {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new AxonSdkError("conexão não aberta");
    }
    const id = this.nextId++;
    const request: RpcRequest = { id, method, params: params.map(encodeTyped), version: PROTOCOL_VERSION };
    const text = JSON.stringify(request);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new AxonSdkError(`timeout em ${method}`));
      }, this.options.timeout);
      this.pending.set(id, { resolve, reject, timer });
      this.ws!.send(text, (err) => {
        if (err) {
          clearTimeout(timer);
          this.pending.delete(id);
          reject(err);
        }
      });
    });
  }

  // ------------------------------------------------------------------
  // Métodos RPC
  // ------------------------------------------------------------------

  async ping(): Promise<boolean> {
    return (await this.callRpc("ping", [])) as boolean;
  }

  async use(ns: string, db: string): Promise<{ namespace: string; database: string }> {
    const result = await this.callRpc("use", [ns, db]) as { namespace: string; database: string };
    this._namespace = result.namespace;
    this._database = result.database;
    return result;
  }

  async query(sql: string, vars?: Record<string, unknown>): Promise<unknown> {
    const params = vars ? [sql, vars] : [sql];
    return this.callRpc("query", params);
  }

  async signin(user: string, pass: string, access?: string): Promise<string> {
    const cred: Record<string, string> = { user, pass };
    if (access) cred.access = access;
    const token = await this.callRpc("signin", [cred]) as string;
    this._auth = token;
    return token;
  }

  async authenticate(token: string): Promise<void> {
    await this.callRpc("authenticate", [token]);
    this._auth = token;
  }

  async signup(user: string, pass: string): Promise<string> {
    const token = await this.callRpc("signup", [{ user, pass }]) as string;
    this._auth = token;
    return token;
  }

  async invalidate(): Promise<void> {
    await this.callRpc("invalidate", []);
    this._auth = null;
  }

  async certificateBegin(store: string): Promise<CertificateChallenge> {
    return (await this.callRpc("certificate.begin", [{ store }])) as CertificateChallenge;
  }

  async certificateComplete(completion: CertificateCompletion): Promise<string> {
    return (await this.callRpc("certificate.complete", [completion])) as string;
  }

  async version(): Promise<string> {
    return (await this.callRpc("version", [])) as string;
  }

  // CRUD helpers via RPC
  async select(what: string): Promise<unknown> {
    return this.callRpc("select", [what]);
  }

  async create(what: string, data: Record<string, unknown>): Promise<unknown> {
    return this.callRpc("create", [what, data]);
  }

  async update(what: string, data: Record<string, unknown>): Promise<unknown> {
    return this.callRpc("update", [what, data]);
  }

  async upsert(what: string, data: Record<string, unknown>): Promise<unknown> {
    return this.callRpc("upsert", [what, data]);
  }

  async delete(what: string): Promise<unknown> {
    return this.callRpc("delete", [what]);
  }

  async insert(table: string, data: Record<string, unknown>): Promise<unknown> {
    return this.callRpc("insert", [table, data]);
  }

  async relate(from: string, kind: string, to: string, data?: Record<string, unknown>): Promise<unknown> {
    return this.callRpc("relate", [from, kind, to, data ?? {}]);
  }

  // Variáveis de sessão
  async letVar(name: string, value: unknown): Promise<void> {
    const cleanName = name.startsWith("$") ? name.slice(1) : name;
    await this.callRaw("let", [cleanName, value]);
  }

  async unset(name: string): Promise<void> {
    const cleanName = name.startsWith("$") ? name.slice(1) : name;
    await this.callRaw("unset", [cleanName]);
  }

  // Transações
  async begin(): Promise<void> {
    await this.callRpc("begin", []);
  }

  async commit(): Promise<void> {
    await this.callRpc("commit", []);
  }

  async cancel(): Promise<void> {
    await this.callRpc("cancel", []);
  }

  // Key-Value
  async kvGet(ns: string, db: string, key: string): Promise<unknown> {
    return this.callRpc("kv_get", [ns, db, key]);
  }

  async kvSet(ns: string, db: string, key: string, value: unknown, ttl?: number): Promise<unknown> {
    return this.callRpc("kv_set", [ns, db, key, value, ttl ?? null]);
  }

  async kvDel(ns: string, db: string, key: string): Promise<boolean> {
    return (await this.callRpc("kv_del", [ns, db, key])) as boolean;
  }

  async kvScan(ns: string, db: string, prefix: string): Promise<KvEntry[]> {
    return (await this.callRpc("kv_scan", [ns, db, prefix])) as KvEntry[];
  }

  // Live queries
  async live(table: string, handler: LiveHandler, diff?: boolean): Promise<string> {
    const id = await this.callRpc("live", [table, diff ?? false]) as string;
    this.liveHandlers.set(id, handler);
    return id;
  }

  async kill(id: string): Promise<boolean> {
    const ok = await this.callRpc("kill", [id]) as boolean;
    if (ok) this.liveHandlers.delete(id);
    return ok;
  }

  // ------------------------------------------------------------------
  // Getters
  // ------------------------------------------------------------------

  get namespace(): string { return this._namespace; }
  get database(): string { return this._database; }
  get isConnected(): boolean { return this.ws?.readyState === WebSocket.OPEN; }
  get helloInfo(): HelloFrame["hello"] | null { return this.hello; }

  // ------------------------------------------------------------------
  // Encerramento
  // ------------------------------------------------------------------

  close(): void {
    this.closed = true;
    this.liveHandlers.clear();
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }
}

function isHello(v: unknown): v is HelloFrame {
  return typeof v === "object" && v !== null && "hello" in v;
}

function isNotification(v: unknown): v is NotificationFrame {
  return typeof v === "object" && v !== null && "notification" in v;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
