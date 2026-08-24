package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.cluster.ClusterStatusProvider;
import com.axonbase.core.cluster.NotLeaderException;
import com.axonbase.core.cluster.QuorumUnavailableException;
import com.axonbase.core.engine.Datastore;
import com.axonbase.server.auth.AuthService;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Dispatcher da lóxica JSON-RPC do AxonBase. Comparte a mesma lóxica entre o
 * transporte HTTP ({@code /rpc}) e o WebSocket, permitindo que o SDK chame
 * {@code ping}, {@code use}, {@code query}, {@code version}, {@code signin},
 * {@code authenticate}, {@code begin}, {@code commit} e {@code cancel}.
 */
public final class RpcDispatcher {

    /** Código JSON-RPC de "escrita enviada ao nó errado". */
    public static final int NOT_LEADER = -32010;
    /** Código JSON-RPC de "sem maioria para confirmar a escrita". */
    public static final int NO_QUORUM = -32011;

    /** Métodos que alteram estado replicado e portanto só rodam no líder. */
    private static final Set<String> WRITE_METHODS = Set.of("query", "begin", "commit");

    private final Datastore ds;
    private final AuthService auth;
    private final boolean requireAuth;
    private volatile Supplier<ClusterStatusProvider.Status> clusterStatus =
        ClusterStatusProvider.Status::local;

    public RpcDispatcher(Datastore ds) {
        this(ds, null, false);
    }

    public RpcDispatcher(Datastore ds, AuthService auth, boolean requireAuth) {
        this.ds = ds;
        this.auth = auth;
        this.requireAuth = requireAuth;
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

    public String dispatch(String body, Session session) {
        AxonValue reqV;
        try {
            reqV = AxonJson.parseDocument(body);
        } catch (RuntimeException e) {
            return rpcError(null, -32700, "erro de parse: " + e.getMessage());
        }
        if (!reqV.isObject()) {
            return rpcError(null, -32600, "Requisição RPC inválida");
        }
        Map<String, AxonValue> obj = reqV.asObject();
        if (!obj.containsKey("method") || !obj.get("method").isString()) {
            return rpcError(obj.get("id"), -32600, "Requisição RPC inválida");
        }
        String method = obj.get("method").asString();
        AxonValue id = obj.get("id");
        ClusterStatusProvider.Status cluster = clusterStatus.get();
        if (!cluster.isLeader() && isWrite(method, obj)) {
            // Recusa antes de executar: um seguidor que aplicasse a escrita localmente
            // divergiria do log replicado.
            return notLeaderError(id, cluster.leader(), cluster.leaderAddress());
        }
        AxonValue result = null;
        String error = null;
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
                    error = "token sem permissão para o namespace/database selecionado";
                } else {
                    result = AxonValue.object(Map.of(
                        "namespace", AxonValue.str(orEmpty(session.namespace())),
                        "database", AxonValue.str(orEmpty(session.database()))));
                }
            }
            case "query" -> {
                if (authRequired() && (session.auth() == null || !authorizedForSession(session))) {
                    error = "usuario non autenticado";
                    break;
                }
                AxonValue params = obj.get("params");
                if (params == null || !params.isArray() || params.asArray().isEmpty()) {
                    error = "faltam parámetros para query";
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
                }
            }
            case "signin" -> {
                AxonValue sp = obj.get("params");
                if (sp == null || !sp.isArray() || sp.asArray().isEmpty()) {
                    error = "faltan credenciais para signin";
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
                        error = "credenciais inválidas";
                    } else {
                        result = AxonValue.str(token);
                    }
                } else {
                    error = "credenciais inválidas para signin";
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
                        error = "token inválido ou sem permissão para este escopo";
                    }
                } else {
                    error = "token inválido";
                }
            }
            case "live" -> {
                if (authRequired() && (session.auth() == null || !authorizedForSession(session))) {
                    error = "usuario non autenticado";
                    break;
                }
                AxonValue lp = obj.get("params");
                if (lp == null || !lp.isArray() || lp.asArray().isEmpty()
                    || !lp.asArray().get(0).isString()) {
                    error = "faltam parâmetros para live (tabela)";
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
                }
            }
            case "kill" -> {
                AxonValue kp = obj.get("params");
                if (kp == null || !kp.isArray() || kp.asArray().isEmpty()) {
                    error = "faltam parâmetros para kill (id da live query)";
                    break;
                }
                result = AxonValue.bool(ds.liveBus().kill(kp.asArray().get(0).asString()));
            }
            case "version" -> result = AxonValue.str("0.1.0-SNAPSHOT");
            case "begin" -> {
                try {
                    ds.beginSession(session);
                    result = AxonValue.nul();
                } catch (RuntimeException e) {
                    error = e.getMessage();
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
                }
            }
            case "cancel" -> {
                ds.cancelSession(session);
                result = AxonValue.nul();
            }
            default -> error = "método non suportado: " + method;
        }
        if (error != null) {
            return rpcError(id, -32003, error);
        }
        return "{\"id\":" + idJson(id) + ",\"result\":" + AxonJson.write(result) + "}";
    }

    private boolean authorizedForSession(Session session) {
        return auth == null || auth.allows(claimsOf(session.auth()), session.namespace(), session.database());
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

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
