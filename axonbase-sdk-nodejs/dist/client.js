"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.Axon = void 0;
const ws_1 = __importDefault(require("ws"));
const protocol_js_1 = require("./protocol.js");
const errors_js_1 = require("./errors.js");
const codec_js_1 = require("./codec.js");
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
class Axon {
    ws = null;
    url;
    options;
    nextId = 1;
    pending = new Map();
    liveHandlers = new Map();
    handshakeReceived = false;
    _namespace = "";
    _database = "";
    _auth = null;
    hello = null;
    openPromise = null;
    openResolve = null;
    closed = false;
    constructor(url, options) {
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
    static async connect(url, options) {
        const axon = new Axon(url, options);
        await axon.open();
        return axon;
    }
    async open() {
        if (this.openPromise)
            return this.openPromise;
        this.openPromise = new Promise((resolve, reject) => {
            this.openResolve = resolve;
            try {
                this.ws = new ws_1.default(this.url, this.options.tls);
                this.ws.on("open", () => this.onOpen());
                this.ws.on("message", (data) => this.onMessage(data.toString()));
                this.ws.on("close", () => this.onClose());
                this.ws.on("error", (err) => {
                    if (!this.handshakeReceived)
                        reject(err);
                });
            }
            catch (err) {
                reject(err);
            }
        });
        return this.openPromise;
    }
    onOpen() {
        // o handshake hello chega como primeiro frame
    }
    onMessage(text) {
        let parsed;
        try {
            parsed = JSON.parse(text);
        }
        catch {
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
        const rpc = parsed;
        if (rpc.id != null) {
            const pending = this.pending.get(rpc.id);
            if (pending) {
                clearTimeout(pending.timer);
                this.pending.delete(rpc.id);
                pending.resolve(rpc);
            }
        }
    }
    onClose() {
        for (const [, pending] of this.pending) {
            clearTimeout(pending.timer);
            pending.reject(new errors_js_1.AxonSdkError("conexão fechada"));
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
    async reconnectLoop(attempt = 0) {
        if (attempt >= this.options.maxReconnectAttempts)
            return;
        await sleep(this.options.reconnectInterval);
        try {
            await this.open();
            // Re-registrar live queries
            for (const [id] of this.liveHandlers) {
                try {
                    await this.call("live", [id, false]);
                }
                catch {
                    // A subscription expirou; remover
                    this.liveHandlers.delete(id);
                }
            }
        }
        catch {
            await this.reconnectLoop(attempt + 1);
        }
    }
    // ------------------------------------------------------------------
    // Chamada RPC
    // ------------------------------------------------------------------
    async call(method, params) {
        if (!this.ws || this.ws.readyState !== ws_1.default.OPEN) {
            throw new errors_js_1.AxonSdkError("conexão não aberta");
        }
        const id = this.nextId++;
        const request = { id, method, params: params.map(codec_js_1.encodeTyped), version: protocol_js_1.PROTOCOL_VERSION };
        const text = JSON.stringify(request);
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                this.pending.delete(id);
                reject(new errors_js_1.AxonSdkError(`timeout em ${method}`));
            }, this.options.timeout);
            this.pending.set(id, { resolve: (r) => resolve(r.result), reject, timer });
            this.ws.send(text, (err) => {
                if (err) {
                    clearTimeout(timer);
                    this.pending.delete(id);
                    reject(err);
                }
            });
        }).then((result) => {
            return result;
        }, (err) => {
            throw err;
        });
    }
    async callRpc(method, params) {
        const maxRetries = this.options.reconnect ? 3 : 1;
        for (let attempt = 0; attempt < maxRetries; attempt++) {
            try {
                const response = await this.callRaw(method, params);
                if (response.error) {
                    throw (0, errors_js_1.fromRpcError)(response.error);
                }
                return response.result;
            }
            catch (err) {
                if (err instanceof errors_js_1.NotLeaderError && err.leaderAddress && attempt < maxRetries - 1) {
                    // Reconectar no líder automaticamente
                    const newUrl = this.url.startsWith("ws://") ? `ws://${err.leaderAddress}/rpc/ws` : `wss://${err.leaderAddress}/rpc/ws`;
                    await this.reconnectTo(newUrl);
                    continue;
                }
                throw err;
            }
        }
        throw new errors_js_1.AxonSdkError("falha após tentativas de reconexão");
    }
    /**
     * Reconecta o WebSocket a um novo URL, preservando sessão, auth e live queries.
     */
    async reconnectTo(newUrl) {
        if (this.ws) {
            try {
                this.ws.close();
            }
            catch { /* ignorar */ }
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
    async callRaw(method, params) {
        if (!this.ws || this.ws.readyState !== ws_1.default.OPEN) {
            throw new errors_js_1.AxonSdkError("conexão não aberta");
        }
        const id = this.nextId++;
        const request = { id, method, params: params.map(codec_js_1.encodeTyped), version: protocol_js_1.PROTOCOL_VERSION };
        const text = JSON.stringify(request);
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                this.pending.delete(id);
                reject(new errors_js_1.AxonSdkError(`timeout em ${method}`));
            }, this.options.timeout);
            this.pending.set(id, { resolve, reject, timer });
            this.ws.send(text, (err) => {
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
    async ping() {
        return (await this.callRpc("ping", []));
    }
    async use(ns, db) {
        const result = await this.callRpc("use", [ns, db]);
        this._namespace = result.namespace;
        this._database = result.database;
        return result;
    }
    async query(sql, vars) {
        const params = vars ? [sql, vars] : [sql];
        return this.callRpc("query", params);
    }
    async signin(user, pass, access) {
        const cred = { user, pass };
        if (access)
            cred.access = access;
        const token = await this.callRpc("signin", [cred]);
        this._auth = token;
        return token;
    }
    async authenticate(token) {
        await this.callRpc("authenticate", [token]);
        this._auth = token;
    }
    async signup(user, pass) {
        const token = await this.callRpc("signup", [{ user, pass }]);
        this._auth = token;
        return token;
    }
    async invalidate() {
        await this.callRpc("invalidate", []);
        this._auth = null;
    }
    async certificateBegin(store) {
        return (await this.callRpc("certificate.begin", [{ store }]));
    }
    async certificateComplete(completion) {
        return (await this.callRpc("certificate.complete", [completion]));
    }
    async version() {
        return (await this.callRpc("version", []));
    }
    // CRUD helpers via RPC
    async select(what) {
        return this.callRpc("select", [what]);
    }
    async create(what, data) {
        return this.callRpc("create", [what, data]);
    }
    async update(what, data) {
        return this.callRpc("update", [what, data]);
    }
    async upsert(what, data) {
        return this.callRpc("upsert", [what, data]);
    }
    async delete(what) {
        return this.callRpc("delete", [what]);
    }
    async insert(table, data) {
        return this.callRpc("insert", [table, data]);
    }
    async relate(from, kind, to, data) {
        return this.callRpc("relate", [from, kind, to, data ?? {}]);
    }
    // Variáveis de sessão
    async letVar(name, value) {
        const cleanName = name.startsWith("$") ? name.slice(1) : name;
        await this.callRaw("let", [cleanName, value]);
    }
    async unset(name) {
        const cleanName = name.startsWith("$") ? name.slice(1) : name;
        await this.callRaw("unset", [cleanName]);
    }
    // Transações
    async begin() {
        await this.callRpc("begin", []);
    }
    async commit() {
        await this.callRpc("commit", []);
    }
    async cancel() {
        await this.callRpc("cancel", []);
    }
    // Key-Value
    async kvGet(ns, db, key) {
        return this.callRpc("kv_get", [ns, db, key]);
    }
    async kvSet(ns, db, key, value, ttl) {
        return this.callRpc("kv_set", [ns, db, key, value, ttl ?? null]);
    }
    async kvDel(ns, db, key) {
        return (await this.callRpc("kv_del", [ns, db, key]));
    }
    async kvScan(ns, db, prefix) {
        return (await this.callRpc("kv_scan", [ns, db, prefix]));
    }
    // Live queries
    async live(table, handler, diff) {
        const id = await this.callRpc("live", [table, diff ?? false]);
        this.liveHandlers.set(id, handler);
        return id;
    }
    async kill(id) {
        const ok = await this.callRpc("kill", [id]);
        if (ok)
            this.liveHandlers.delete(id);
        return ok;
    }
    // ------------------------------------------------------------------
    // Getters
    // ------------------------------------------------------------------
    get namespace() { return this._namespace; }
    get database() { return this._database; }
    get isConnected() { return this.ws?.readyState === ws_1.default.OPEN; }
    get helloInfo() { return this.hello; }
    // ------------------------------------------------------------------
    // Encerramento
    // ------------------------------------------------------------------
    close() {
        this.closed = true;
        this.liveHandlers.clear();
        if (this.ws) {
            this.ws.close();
            this.ws = null;
        }
    }
}
exports.Axon = Axon;
function isHello(v) {
    return typeof v === "object" && v !== null && "hello" in v;
}
function isNotification(v) {
    return typeof v === "object" && v !== null && "notification" in v;
}
function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}
