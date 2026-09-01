package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.cluster.ClusterStatusProvider;
import com.axonbase.core.cluster.NotLeaderException;
import com.axonbase.core.cluster.QuorumUnavailableException;
import com.axonbase.core.engine.Datastore;
import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.axonbase.server.auth.AuthService;
import com.axonbase.server.auth.CertificateChallengeService;
import com.axonbase.server.auth.TrustStoreRegistry;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Dispatcher da lóxica JSON-RPC do AxonBase. Comparte a mesma lóxica entre o
 * transporte HTTP ({@code /rpc}) e o WebSocket, permitindo que o SDK chame
 * {@code ping}, {@code use}, {@code query}, {@code let}/{@code set}/
 * {@code unset}, os CRUD ({@code select}, {@code create}, {@code insert},
 * {@code update}, {@code upsert}, {@code delete}, {@code relate}),
 * {@code signin}/{@code signup}/{@code authenticate}/{@code invalidate},
 * {@code live}, {@code kill}, {@code version}, {@code begin}, {@code commit} e
 * {@code cancel}, além do key-value (KV) público. Também executa o handshake de
 * protocolo: um request com {@code version} divergente é recusado com
 * {@code PROTOCOL_MISMATCH} antes de qualquer efeito.
 */
public final class RpcDispatcher {

/** Código JSON-RPC de "escrita enviada ao nó errado". */
    public static final int NOT_LEADER = -32010;
    /** Código JSON-RPC de "sem maioria para confirmar a escrita". */
    public static final int NO_QUORUM = -32011;
    /** Código JSON-RPC de "protocolo incompatível" (comparte -32600 da invalid request). */
    public static final int PROTOCOL_MISMATCH = -32600;

    /** Versión do protocolo de wire, independente da versión do artefacto. */
    public static final int PROTOCOL_VERSION = 1;
    /** Versión do artefacto servidor, exposta polo handshake e polo método version. */
    public static final String SERVER_VERSION = "0.1.0-SNAPSHOT";

    /** Métodos RPC suportados, expostos no handshake (hello) e en testes. */
    static final List<String> METHODS = List.of(
        "ping", "use", "query", "let", "set", "unset",
        "select", "create", "insert", "update", "upsert", "delete", "relate",
        "signin", "signup", "authenticate", "invalidate", "certificate.begin", "certificate.complete",
        "live", "kill", "version", "begin", "commit", "cancel",
        "kv_get", "kv_set", "kv_del", "kv_scan");

    /** Métodos que alteram estado replicado e portanto só rodam no líder. */
    private static final Set<String> WRITE_METHODS =
        Set.of("query", "begin", "commit", "kv_set", "kv_del",
            "create", "insert", "update", "upsert", "delete", "relate");

    private final Datastore ds;
    private final AuthService auth;
    private final boolean requireAuth;
    private final long queryTimeoutMs;
    private final CertificateChallengeService certificateChallenges;
    private final TrustStoreRegistry trustStores;
    private volatile Supplier<ClusterStatusProvider.Status> clusterStatus =
        ClusterStatusProvider.Status::local;

    public RpcDispatcher(Datastore ds) {
        this(ds, null, false, 0);
    }

    public RpcDispatcher(Datastore ds, AuthService auth, boolean requireAuth) {
        this(ds, auth, requireAuth, 0);
    }

    public RpcDispatcher(Datastore ds, AuthService auth, boolean requireAuth, long queryTimeoutMs) {
        this(ds, auth, requireAuth, queryTimeoutMs, new TrustStoreRegistry());
    }

    public RpcDispatcher(Datastore ds, AuthService auth, boolean requireAuth, long queryTimeoutMs,
                         TrustStoreRegistry trustStores) {
        this.ds = ds;
        this.auth = auth;
        this.requireAuth = requireAuth;
        this.queryTimeoutMs = queryTimeoutMs;
        this.certificateChallenges = new CertificateChallengeService();
        this.trustStores = trustStores == null ? new TrustStoreRegistry() : trustStores;
    }

    /** Liga o dispatcher ao estado real do cluster, para recusar escritas fora do líder. */
    public void clusterStatus(Supplier<ClusterStatusProvider.Status> status) {
        this.clusterStatus = status == null ? ClusterStatusProvider.Status::local : status;
    }

    /**
     * Decide se a chamada muda estado replicado.
     *
     * <p>{@code query} depende do texto: um SELECT roda em qualquer réplica, um
     * CREATE ou um DEFINE não.</p>
     */
    private boolean isWrite(String method, Map<String, AxonValue> request) {
        if (!WRITE_METHODS.contains(method)) {
            return false;
        }
        if (!"query".equals(method)) {
            return true;
        }
        AxonValue params = request.get("params");
        if (params == null || !params.isArray() || params.asArray().isEmpty()
            || !params.asArray().get(0).isString()) {
            return false;
        }
        return Datastore.isMutation(params.asArray().get(0).asString());
    }

    /**
     * Erro estruturado de redirecionamento, com o líder e o endereço dele.
     *
     * <p>É a mesma resposta no HTTP {@code /rpc} e no WebSocket, porque os dois
     * transportes compartilham este dispatcher.</p>
     */
    private static String notLeaderError(AxonValue id, String leader, String address) {
        String detail = leader == null || leader.isEmpty()
            ? "escrita recusada: nenhum líder conhecido neste momento"
            : "escrita deve ser enviada ao líder " + leader
                + (address == null || address.isEmpty() ? "" : " em " + address);
        return "{\"id\":" + idJson(id) + ",\"error\":{\"code\":" + NOT_LEADER
            + ",\"message\":\"" + esc(detail) + "\",\"kind\":\"NOT_LEADER\",\"leader\":\""
            + esc(leader == null ? "" : leader) + "\",\"leader_address\":\""
            + esc(address == null ? "" : address) + "\"}}";
    }

    private boolean authRequired() {
        return requireAuth && auth != null;
    }

    /**
     * Procesa unha request JSON-RPC.
     *
     * @param body  corpo JSON-RPC
     * @param session  sesión base xa inicializada cos headers/estado
     * @return o texto JSON de resposta (result ou error)
     */
    /** Datastore servido por este dispatcher (usado pelo transporte WebSocket). */
    public Datastore datastore() {
        return ds;
    }

    /** Versión do protocolo de wire que este servidor fala. */
    public int getProtocol() {
        return PROTOCOL_VERSION;
    }

    /** Alias semántico de {@link #getProtocol()}: versión do protocolo de wire. */
    public int protocolVersion() {
        return PROTOCOL_VERSION;
    }

    /** Versión do artefacto servidor. */
    public String serverVersion() {
        return SERVER_VERSION;
    }

    /** Métodos RPC suportados, expostos no handshake e em testes. */
    public List<String> getMethods() {
        return METHODS;
    }

    /** Alias de {@link #getMethods()}: lista de métodos RPC do handshake. */
    public List<String> methods() {
        return METHODS;
    }

    /**
     * Frame de boas-vindas enviado no WebSocket formal da conexión:
     * {@code {"hello": {"protocol", "server", "methods"}}}. É o handshake que
     * permite ao conector saber a versão e os métodos antes de chamar nada.
     */
    public String hello() {
        java.util.List<String> methods = METHODS;
        java.util.Map<String, AxonValue> body = new java.util.LinkedHashMap<>();
        body.put("protocol", AxonValue.num(PROTOCOL_VERSION));
        body.put("server", AxonValue.str(SERVER_VERSION));
        body.put("methods", AxonValue.array(methods.stream().map(AxonValue::str).toList()));
        return "{\"hello\":" + AxonJson.write(AxonValue.object(body)) + "}";
    }

    private static String protocolMismatch(AxonValue id, long got) {
        return "{\"id\":" + idJson(id) + ",\"error\":{\"code\":" + PROTOCOL_MISMATCH
            + ",\"message\":\"PROTOCOL_MISMATCH\",\"kind\":\"PROTOCOL_MISMATCH\",\"protocol\":"
            + PROTOCOL_VERSION + ",\"version\":" + got + "}}";
    }

    private AxonValue kvRead(String method, Map<String, AxonValue> obj, Session session) {
        String[] p = kvParams(method, obj, 3);
        if (p == null) {
            throw new IllegalArgumentException(Messages.get("rpc_parameters_required", method));
        }
        return "kv_get".equals(method)
            ? ds.kvGet(session, p[0], p[1], p[2])
            : ds.kvScan(session, p[0], p[1], p[2]);
    }

    private AxonValue kvWrite(String method, Map<String, AxonValue> obj, Session session) {
        AxonValue params = obj.get("params");
        if (params == null || !params.isArray() || params.asArray().size() < 3) {
            throw new IllegalArgumentException(Messages.get("rpc_parameters_required", method));
        }
        List<AxonValue> args = params.asArray();
        if ("kv_del".equals(method)) {
            return AxonValue.bool(ds.kvDel(session, args.get(0).asString(),
                args.get(1).asString(), args.get(2).asString()));
        }
        if (args.size() < 4) {
            throw new IllegalArgumentException(Messages.get("rpc_kv_set_value_required"));
        }
        Long ttl = args.size() > 4 && args.get(4).isNumber() ? args.get(4).asLong() : null;
        return ds.kvSet(session, args.get(0).asString(), args.get(1).asString(),
            args.get(2).asString(), args.get(3), ttl);
    }

    private static String[] kvParams(String method, Map<String, AxonValue> obj, int min) {
        AxonValue params = obj.get("params");
        if (params == null || !params.isArray() || params.asArray().size() < min) {
            return null;
        }
        List<AxonValue> args = params.asArray();
        String[] out = new String[min];
        for (int i = 0; i < min; i++) {
            if (!args.get(i).isString()) {
                throw new IllegalArgumentException(
                    Messages.get("rpc_parameter_string", i, method));
            }
            out[i] = args.get(i).asString();
        }
        return out;
    }

    /** Lista de parámetros do request, ou {@code null} se não for array. */
    private static List<AxonValue> paramsOf(Map<String, AxonValue> obj) {
        AxonValue params = obj.get("params");
        return params == null || !params.isArray() ? null : params.asArray();
    }

    private static final class WriteRedirect extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String body;

        WriteRedirect(String body) {
            super(body);
            this.body = body;
        }

        String body() {
            return body;
        }
    }

    /**
     * SELECT via RPC: recebe um SELECT AxonQL completo (executado como está) ou
     * o nome de uma tabela, que vira {@code SELECT * FROM <tabela>} com um WHERE
     * opcional. É o único dos novos CRUD que não muta, por isso não está em
     * {@link #WRITE_METHODS}.
     */
    private AxonValue rpcSelect(Map<String, AxonValue> obj, Session session) {
        List<AxonValue> pa = paramsOf(obj);
        if (pa == null || pa.isEmpty() || !pa.get(0).isString()) {
            throw new IllegalArgumentException(Messages.get("rpc_select_parameters_required"));
        }
        String what = pa.get(0).asString().trim();
        if (what.isEmpty()) {
            throw new IllegalArgumentException(Messages.get("rpc_select_empty"));
        }
        String sql;
        if (what.regionMatches(true, 0, "SELECT", 0, 6)
            && (what.length() == 6 || Character.isWhitespace(what.charAt(6)))) {
            sql = what;
        } else {
            StringBuilder sb = new StringBuilder("SELECT * FROM ").append(ident(pa.get(0)));
            if (pa.size() > 1 && pa.get(1) != null && !pa.get(1).isNone() && !pa.get(1).isNull()) {
                sb.append(" WHERE ").append(pa.get(1).asString());
            }
            sql = sb.toString();
        }
        return ds.execute(sql, session, Map.of());
    }

    /**
     * Executa os métodos de CRUD como AxonQL de escrita, delegando no
     * {@link Datastore#execute}. O roteamento ao líder já foi decidido pelo
     * {@code WRITE_METHODS} no topo do dispatch; se ainda assim o nó perder a
     * liderança no meio da escrita, o erro estruturado de NOT_LEADER/NO_QUORUM
     * viaja como WriteRedirect para o transporte.
     */
    private AxonValue rpcData(String method, Map<String, AxonValue> obj, Session session, AxonValue id) {
        String sql = buildSql(method, obj);
        try {
            return ds.execute(sql, session, Map.of());
        } catch (NotLeaderException moved) {
            throw new WriteRedirect(notLeaderError(id, moved.leader(), moved.leaderAddress()));
        } catch (QuorumUnavailableException noQuorum) {
            throw new WriteRedirect(rpcError(id, NO_QUORUM, noQuorum.getMessage()));
        }
    }

    /** Constrói o AxonQL conservador de cada método CRUD a partir dos params. */
    private static String buildSql(String method, Map<String, AxonValue> obj) {
        List<AxonValue> pa = paramsOf(obj);
        if (pa == null) {
            throw new IllegalArgumentException(Messages.get("rpc_parameters_required", method));
        }
        return switch (method) {
            case "create" -> {
                requireMin(pa, 2, method);
                yield "CREATE " + ident(pa.get(0)) + " CONTENT " + contentObject(pa.get(1));
            }
            case "insert" -> {
                requireMin(pa, 2, method);
                yield "INSERT INTO " + ident(pa.get(0)) + " " + contentObject(pa.get(1));
            }
            case "update" -> {
                requireMin(pa, 2, method);
                yield "UPDATE " + ident(pa.get(0)) + " MERGE " + contentObject(pa.get(1));
            }
            case "upsert" -> {
                requireMin(pa, 2, method);
                yield "UPSERT " + ident(pa.get(0)) + " MERGE " + contentObject(pa.get(1));
            }
            case "delete" -> {
                requireMin(pa, 1, method);
                StringBuilder sb = new StringBuilder("DELETE ").append(ident(pa.get(0)));
                if (pa.size() > 1 && pa.get(1) != null && !pa.get(1).isNone() && !pa.get(1).isNull()) {
                    sb.append(" WHERE ").append(pa.get(1).asString());
                }
                yield sb.toString();
            }
            case "relate" -> {
                requireMin(pa, 3, method);
                StringBuilder sb = new StringBuilder("RELATE ")
                    .append(ident(pa.get(0))).append("->").append(ident(pa.get(1)))
                    .append("->").append(ident(pa.get(2)));
                if (pa.get(3) != null && pa.get(3).isObject()) {
                    sb.append(" SET ");
                    boolean first = true;
                    for (Map.Entry<String, AxonValue> e : pa.get(3).asObject().entrySet()) {
                        if (!first) {
                            sb.append(", ");
                        }
                        first = false;
                        sb.append(e.getKey()).append(" = ").append(e.getValue().toString());
                    }
                }
                yield sb.toString();
            }
            default -> throw new IllegalArgumentException(Messages.get("rpc_data_method_unsupported", method));
        };
    }

    private static void requireMin(List<AxonValue> pa, int min, String method) {
        if (pa.size() < min) {
            throw new IllegalArgumentException(Messages.get("rpc_parameters_required", method));
        }
    }

    /** Nome de tabela ou record id, restrito a um conjunto seguro de caracteres. */
    private static String ident(AxonValue v) {
        if (v == null || !v.isString()) {
            throw new IllegalArgumentException(Messages.get("rpc_identifier_string"));
        }
        String s = v.asString().trim();
        if (!s.matches("[\\p{L}\\p{N}_.:\\-]+")) {
            throw new IllegalArgumentException(Messages.get("rpc_identifier_invalid", s));
        }
        return s;
    }

    /** Dados de escrita como literal AxonQL (mapa/objeto). */
    private static String contentObject(AxonValue v) {
        if (v == null || !v.isObject()) {
            throw new IllegalArgumentException(Messages.get("rpc_data_object"));
        }
        return v.toString();
    }

    public String dispatch(String body, Session session) {
        try {
            return dispatchUnchecked(body, session);
        } catch (AxonError e) {
            return rpcError(null, e.code(), e.getMessage());
        } catch (WriteRedirect redirect) {
            // Escrita que o nó não conseguiu confirmar (líder mudou ou sem quórum).
            return redirect.body();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return rpcError(null, -32602, Messages.get("rpc_invalid_parameters", e.getMessage()));
        }
    }

    private String dispatchUnchecked(String body, Session session) {
        AxonValue reqV;
        try {
            reqV = AxonJson.parseDocument(body);
        } catch (RuntimeException e) {
            return rpcError(null, -32700, Messages.get("rpc_parse_error", e.getMessage()));
        }
        if (!reqV.isObject()) {
            return rpcError(null, -32600, Messages.get("rpc_request_invalid"));
        }
        Map<String, AxonValue> obj = reqV.asObject();
        if (!obj.containsKey("method") || !obj.get("method").isString()) {
            return rpcError(obj.get("id"), -32600, Messages.get("rpc_request_invalid"));
        }
        String method = obj.get("method").asString();
        AxonValue id = obj.get("id");
        // Handshake de protocolo: cliente que declare outra versão não executa nada.
        AxonValue sentVersion = obj.get("version");
        if (sentVersion != null) {
            long sent = sentVersion.isNumber() ? sentVersion.asLong() : Long.MIN_VALUE;
            if (sent != PROTOCOL_VERSION) {
                return protocolMismatch(id, sent);
            }
        }
        ClusterStatusProvider.Status cluster = clusterStatus.get();
        if (!cluster.isLeader() && isWrite(method, obj)) {
            // Recusa antes de executar: um seguidor que aplicasse a escrita localmente
            // divergiria do log replicado.
            return notLeaderError(id, cluster.leader(), cluster.leaderAddress());
        }
        if (authRequired() && requiresAuthentication(method)
            && (session.auth() == null || !authorizedForSession(session))) {
            return rpcError(id, -32003, Messages.get("auth_required"));
        }
        AxonValue result = null;
        String error = null;
        int errorCode = -32003;
        switch (method) {
            case "ping" -> result = AxonValue.bool(true);
            case "use" -> {
                String previousNs = session.namespace();
                String previousDb = session.database();
                AxonValue params = obj.get("params");
                if (params != null && params.isArray() && params.asArray().size() >= 2) {
                    AxonValue nsp = params.asArray().get(0);
                    AxonValue dbp = params.asArray().get(1);
                    if (nsp.isString()) {
                        session.namespace(nsp.asString());
                    }
                    if (dbp.isString()) {
                        session.database(dbp.asString());
                    }
                }
                if (authRequired() && session.auth() != null && !authorizedForSession(session)) {
                    session.namespace(previousNs);
                    session.database(previousDb);
                    error = Messages.get("rpc_scope_forbidden");
                } else {
                    result = AxonValue.object(Map.of(
                        "namespace", AxonValue.str(orEmpty(session.namespace())),
                        "database", AxonValue.str(orEmpty(session.database()))));
                }
            }
            case "query" -> {
                if (authRequired() && (session.auth() == null || !authorizedForSession(session))) {
                    error = Messages.get("auth_required");
                    break;
                }
                AxonValue params = obj.get("params");
                if (params == null || !params.isArray() || params.asArray().isEmpty()) {
                    error = Messages.get("rpc_query_parameters_required");
                    break;
                }
                String sql = params.asArray().get(0).asString();
                AxonValue vars = params.asArray().size() > 1
                    ? params.asArray().get(1)
                    : AxonValue.object(Map.of());
                try {
                    result = ds.execute(sql, session, mapOf(vars));
                } catch (NotLeaderException moved) {
                    return notLeaderError(id, moved.leader(), moved.leaderAddress());
                } catch (QuorumUnavailableException noQuorum) {
                    return rpcError(id, NO_QUORUM, noQuorum.getMessage());
                } catch (RuntimeException e) {
                    error = e.getMessage();
                    errorCode = errorCode(e);
                }
            }
            case "signin" -> {
                AxonValue sp = obj.get("params");
                if (sp == null || !sp.isArray() || sp.asArray().isEmpty()) {
                    error = Messages.get("rpc_invalid");
                    break;
                }
                AxonValue cred = sp.asArray().get(0);
                if (cred.isObject() && cred.asObject().containsKey("user")
                    && cred.asObject().containsKey("pass")) {
                    String user = cred.asObject().get("user").asString();
                    String pass = cred.asObject().get("pass").asString();
                    String access = cred.asObject().containsKey("access")
                        ? cred.asObject().get("access").asString() : null;
                    String token = auth != null ? auth.signin(user, pass, session.namespace(),
                        session.database(), access) : null;
                    if (token == null) {
                        error = Messages.get("auth_invalid");
                    } else {
                        result = AxonValue.str(token);
                    }
                } else {
                    error = Messages.get("rpc_invalid_cred");
                }
            }
            case "authenticate" -> {
                AxonValue ap = obj.get("params");
                if (ap != null && ap.isArray() && !ap.asArray().isEmpty() && auth != null) {
                    Map<String, Object> claims = auth.claims(ap.asArray().get(0).asString());
                    if (claims != null && auth.allows(claims, session.namespace(), session.database())) {
                        session.auth(claimsValue(claims));
                        result = AxonValue.nul();
                    } else {
                        error = Messages.get("auth_invalid");
                    }
                } else {
                    error = Messages.get("auth_invalid");
                }
            }
            case "certificate.begin" -> {
                String store = certificateBeginStore(obj);
                CertificateChallengeService.Challenge challenge = certificateChallenges.begin(store,
                    session.namespace(), session.database());
                result = AxonValue.object(Map.of(
                    "id", AxonValue.str(challenge.id()),
                    "challenge", AxonValue.str(challenge.value()),
                    "expires_at", AxonValue.str(challenge.expiresAt().toString())));
            }
            case "certificate.complete" -> {
                if (auth == null) {
                    error = Messages.get("rpc_cert_handshake_unavailable");
                    break;
                }
                try {
                    CertificateCompletion completion = certificateCompletion(obj);
                    java.security.KeyStore trustStore = trustStores.get(completion.store());
                    if (trustStore == null) {
                        throw new IllegalArgumentException(Messages.get("rpc_truststore_unregistered", completion.store()));
                    }
                    var verified = certificateChallenges.complete(new CertificateChallengeService.Challenge(
                        completion.id(), completion.challenge(), Instant.EPOCH), completion.store(),
                        session.namespace(), session.database(), completion.chain(), completion.signature(), trustStore);
                    result = AxonValue.str(auth.issueTemporaryCredential(completion.user(), completion.store(), verified.fingerprint()));
                } catch (RuntimeException e) {
                    error = e.getMessage();
                }
            }
            case "live" -> {
                if (authRequired() && (session.auth() == null || !authorizedForSession(session))) {
                    error = Messages.get("auth_required");
                    break;
                }
                AxonValue lp = obj.get("params");
                if (lp == null || !lp.isArray() || lp.asArray().isEmpty()
                    || !lp.asArray().get(0).isString()) {
                    error = Messages.get("rpc_live_parameters_required");
                    break;
                }
                String table = lp.asArray().get(0).asString();
                boolean diff = lp.asArray().size() > 1 && lp.asArray().get(1).isBool()
                    && lp.asArray().get(1).asBool();
                String sql = "LIVE SELECT * FROM " + table + (diff ? " DIFF" : "");
                try {
                    result = ds.execute(sql, session, Map.of());
                } catch (RuntimeException e) {
                    error = e.getMessage();
                    errorCode = errorCode(e);
                }
            }
            case "kill" -> {
                AxonValue kp = obj.get("params");
                if (kp == null || !kp.isArray() || kp.asArray().isEmpty()) {
                    error = Messages.get("rpc_kill_parameters_required");
                    break;
                }
                result = AxonValue.bool(ds.liveBus().kill(kp.asArray().get(0).asString()));
            }
            case "version" -> result = AxonValue.str(SERVER_VERSION);
            case "let", "set" -> {
                List<AxonValue> pa = paramsOf(obj);
                if (pa == null || pa.size() < 2 || !pa.get(0).isString()) {
                    error = Messages.get("rpc_key_value_parameters_required", method);
                    break;
                }
                // LET/SET não tocam o backend: guardam a variável na sessão.
                session.vars().set(pa.get(0).asString(), pa.get(1));
                result = pa.get(1);
            }
            case "unset" -> {
                List<AxonValue> pa = paramsOf(obj);
                if (pa == null || pa.isEmpty() || !pa.get(0).isString()) {
                    error = Messages.get("rpc_unset_parameters_required");
                    break;
                }
                session.vars().unset(pa.get(0).asString());
                result = AxonValue.bool(true);
            }
            case "select" -> {
                try {
                    result = rpcSelect(obj, session);
                } catch (RuntimeException e) {
                    error = e.getMessage();
                    errorCode = errorCode(e);
                }
            }
            case "create", "insert", "update", "upsert", "delete", "relate" -> {
                try {
                    result = rpcData(method, obj, session, id);
                } catch (WriteRedirect r) {
                    throw r;
                } catch (RuntimeException e) {
                    error = e.getMessage();
                    errorCode = errorCode(e);
                }
            }
            case "signup" -> {
                if (auth == null) {
                    error = Messages.get("rpc_signup_unavailable");
                    break;
                }
                AxonValue sp = obj.get("params");
                AxonValue cred = sp != null && sp.isArray() && !sp.asArray().isEmpty()
                    ? sp.asArray().get(0) : null;
                if (cred == null || !cred.isObject()) {
                    error = Messages.get("rpc_signup_invalid");
                    break;
                }
                Map<String, AxonValue> m = cred.asObject();
                String user = m.get("user") != null && m.get("user").isString()
                    ? m.get("user").asString() : null;
                String pass = m.get("pass") != null && m.get("pass").isString()
                    ? m.get("pass").asString() : "";
                String ns = m.containsKey("ns") && m.get("ns").isString()
                    ? m.get("ns").asString() : session.namespace();
                String db = m.containsKey("db") && m.get("db").isString()
                    ? m.get("db").asString() : session.database();
                if (user == null) {
                    error = Messages.get("rpc_signup_user_invalid");
                    break;
                }
                try {
                    String token = auth.signup(user, pass, ns, db);
                    if (token == null) {
                        error = Messages.get("rpc_signup_scope_required");
                    } else {
                        result = AxonValue.str(token);
                    }
                } catch (IllegalArgumentException e) {
                    error = e.getMessage();
                }
            }
            case "invalidate" -> {
                // Só invalida a relação local: o token continua válido pelo servidor.
                session.auth(null);
                session.clearLive();
                result = AxonValue.nul();
            }
            case "begin" -> {
                try {
                    ds.beginSession(session);
                    result = AxonValue.nul();
                } catch (RuntimeException e) {
                    error = e.getMessage();
                    errorCode = errorCode(e);
                }
            }
            case "commit" -> {
                try {
                    ds.commitSession(session);
                    result = AxonValue.nul();
                } catch (NotLeaderException moved) {
                    ds.cancelSession(session);
                    return notLeaderError(id, moved.leader(), moved.leaderAddress());
                } catch (QuorumUnavailableException noQuorum) {
                    ds.cancelSession(session);
                    return rpcError(id, NO_QUORUM, noQuorum.getMessage());
                } catch (RuntimeException e) {
                    error = e.getMessage();
                    errorCode = errorCode(e);
                }
            }
            case "cancel" -> {
                ds.cancelSession(session);
                result = AxonValue.nul();
            }
            case "kv_get" -> result = kvRead("kv_get", obj, session);
            case "kv_set" -> result = kvWrite("kv_set", obj, session);
            case "kv_del" -> result = kvWrite("kv_del", obj, session);
            case "kv_scan" -> result = kvRead("kv_scan", obj, session);
            default -> {
                error = Messages.get("rpc_method_unsupported", method);
                errorCode = -32601;
            }
        }
        if (error != null) {
            return rpcError(id, errorCode, error);
        }
        return "{\"id\":" + idJson(id) + ",\"result\":" + AxonJson.write(result) + "}";
    }

    private boolean authorizedForSession(Session session) {
        return auth == null || auth.allows(claimsOf(session.auth()), session.namespace(), session.database());
    }

    private static boolean requiresAuthentication(String method) {
        return !switch (method) {
            case "ping", "version", "signin", "authenticate", "use", "certificate.begin", "certificate.complete" -> true;
            default -> false;
        };
    }

    private record CertificateCompletion(String id, String challenge, String store, String user,
                                         List<String> chain, String signature) {
    }

    private static String certificateBeginStore(Map<String, AxonValue> obj) {
        List<AxonValue> params = paramsOf(obj);
        if (params == null || params.isEmpty() || !params.getFirst().isObject()) {
            throw new IllegalArgumentException(Messages.get("rpc_certificate_begin_parameters"));
        }
        AxonValue store = params.getFirst().asObject().get("store");
        if (store == null || !store.isString() || store.asString().isBlank()) {
            throw new IllegalArgumentException(Messages.get("rpc_certificate_store_required"));
        }
        return store.asString();
    }

    private static CertificateCompletion certificateCompletion(Map<String, AxonValue> obj) {
        List<AxonValue> params = paramsOf(obj);
        if (params == null || params.isEmpty() || !params.getFirst().isObject()) {
            throw new IllegalArgumentException(Messages.get("rpc_certificate_complete_parameters"));
        }
        Map<String, AxonValue> values = params.getFirst().asObject();
        String id = certificateString(values, "id");
        String challenge = certificateString(values, "challenge");
        String store = certificateString(values, "store");
        String user = certificateString(values, "user");
        String signature = certificateString(values, "signature");
        if (!user.matches("[0-9]{11}|[0-9]{14}")) {
            throw new IllegalArgumentException(Messages.get("rpc_certificate_user_required"));
        }
        AxonValue chainValue = values.get("chain");
        if (chainValue == null || !chainValue.isArray()) {
            throw new IllegalArgumentException(Messages.get("rpc_certificate_chain_required"));
        }
        List<String> chain = chainValue.asArray().stream().map(value -> {
            if (!value.isString() || value.asString().isBlank()) {
                throw new IllegalArgumentException(Messages.get("rpc_certificate_chain_invalid"));
            }
            return value.asString();
        }).toList();
        return new CertificateCompletion(id, challenge, store, user, chain, signature);
    }

    private static String certificateString(Map<String, AxonValue> values, String name) {
        AxonValue value = values.get(name);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException(Messages.get("rpc_certificate_field_required", name));
        }
        return value.asString();
    }

    private static AxonValue claimsValue(Map<String, Object> claims) {
        Map<String, AxonValue> out = new java.util.LinkedHashMap<>();
        claims.forEach((key, value) -> out.put(key, value instanceof Number n
            ? AxonValue.num(n.longValue()) : value instanceof Boolean b
                ? AxonValue.bool(b) : value == null ? AxonValue.nul() : AxonValue.str(String.valueOf(value))));
        return AxonValue.object(out);
    }

    private static Map<String, Object> claimsOf(AxonValue value) {
        if (value == null || !value.isObject()) {
            return Map.of();
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        value.asObject().forEach((key, v) -> out.put(key, switch (v.type()) {
            case NUMBER -> v.asLong();
            case BOOL -> v.asBool();
            case NULL, NONE -> null;
            default -> v.asString();
        }));
        return out;
    }

    private static String orEmpty(String v) {
        return v == null ? "" : v;
    }

    private static Map<String, AxonValue> mapOf(AxonValue v) {
        return v.isObject() ? v.asObject() : Map.of();
    }

    private static String rpcError(AxonValue id, int code, String msg) {
        return "{\"id\":" + idJson(id) + ",\"error\":{\"code\":" + code
            + ",\"message\":\"" + esc(msg) + "\"}}";
    }

    private static String idJson(AxonValue id) {
        return id == null ? "null" : AxonJson.write(id);
    }

    private static int errorCode(RuntimeException error) {
        return error instanceof AxonError axonError ? axonError.code() : -32003;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
