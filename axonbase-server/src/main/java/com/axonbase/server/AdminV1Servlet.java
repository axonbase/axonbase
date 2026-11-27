package com.axonbase.server;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.axonbase.core.Session;
import com.axonbase.core.audit.AiAuditCatalog;
import com.axonbase.core.audit.AuditCase;
import com.axonbase.core.catalog.Catalog;
import com.axonbase.core.catalog.Database;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.cluster.ClusterStatusProvider;
import com.axonbase.core.cluster.NotLeaderException;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.core.security.Authz;
import com.axonbase.server.auth.ServiceTokenStore;
import com.axonbase.server.auth.TrustStoreRegistry;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Roteador da API de gestão versionada ({@code /admin/v1}). Reaproveita o {@code Executor} e o
 * {@code Datastore} em vez de duplicar regras: escritas passam por AxonQL com os claims do chamador,
 * então papéis e escopo são os do motor.
 */
final class AdminV1Servlet extends HttpServlet {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern TOKEN_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Pattern STORE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** Erro de API já com status HTTP e código do contrato. */
    static final class ApiError extends RuntimeException {
        final int status;
        final String code;
        final transient Map<String, Object> extra;

        ApiError(int status, String code, String message) {
            this(status, code, message, Map.of());
        }

        ApiError(int status, String code, String message, Map<String, Object> extra) {
            super(message);
            this.status = status;
            this.code = code;
            this.extra = extra;
        }
    }

    /** Identidade que chamou a API. */
    record Caller(Map<String, Object> claims, AxonValue auth, Authz.Role role) {
        boolean root() {
            return auth == null || Authz.scope(auth) == AuthCatalog.Scope.ROOT;
        }

        String name() {
            if (auth != null && auth.isObject() && auth.asObject().get("id") != null) {
                return auth.asObject().get("id").asString();
            }
            return "anonymous";
        }
    }

    private final AxonServer srv;

    AdminV1Servlet(AxonServer srv) {
        this.srv = srv;
    }

    // ------------------------------------------------------------------
    // entrada
    // ------------------------------------------------------------------

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            Caller caller = authenticate(req);
            String method = req.getMethod().toUpperCase();
            String info = req.getPathInfo() == null ? "" : req.getPathInfo();
            List<String> seg = new ArrayList<>();
            for (String part : info.split("/")) {
                if (!part.isEmpty()) {
                    seg.add(part);
                }
            }
            if (method.equals("GET") && seg.size() == 1 && seg.get(0).equals("events")) {
                needRoot(caller, Authz.Role.VIEWER);
                sse(req, resp, srv.events()::subscribe);
                return;
            }
            if (method.equals("GET") && seg.size() == 2 && seg.get(0).equals("logs") && seg.get(1).equals("stream")) {
                needRoot(caller, Authz.Role.VIEWER);
                sse(req, resp, sink -> AxonLog.buffer().subscribe(e -> sink.accept(e.toJson())));
                return;
            }
            if (ClusterOpsApi.handle(srv, this, method, seg, req, resp, caller)) {
                return;
            }
            if (PhysicalBackupApi.handle(srv, this, method, seg, req, resp, caller)) {
                return;
            }
            if (PitrApi.handle(srv, this, method, seg, req, resp, caller)) {
                return;
            }
            if (BackupStreamApi.handle(srv, this, method, seg, req, resp, caller)) {
                return;
            }
            AdminV1Response out = route(method, seg, req, caller);
            if (out.status() == 204) {
                resp.setStatus(204);
                return;
            }
            write(resp, out.status(), out.body());
        } catch (ApiError e) {
            writeError(resp, e);
        } catch (NotLeaderException e) {
            writeError(resp, notLeader());
        } catch (com.axonbase.core.cluster.CommitOutcomeUnknownException e) {
            writeError(resp, new ApiError(504, "OUTCOME_UNKNOWN", e.getMessage()));
        } catch (AxonError e) {
            writeError(resp, mapEngine(e));
        } catch (IllegalArgumentException e) {
            writeError(resp, new ApiError(400, "BAD_REQUEST", e.getMessage()));
        } catch (RuntimeException e) {
            System.err.println(AxonLog.json("error", "admin_v1_internal", e.getMessage()));
            writeError(resp, new ApiError(500, "INTERNAL", Messages.get("admin_internal_error")));
        }
    }

    private AdminV1Response route(String method, List<String> seg, HttpServletRequest req, Caller c)
            throws IOException {
        if (seg.isEmpty()) {
            throw notFound();
        }
        String head = seg.get(0);
        int n = seg.size();
        boolean get = method.equals("GET");
        boolean post = method.equals("POST");
        boolean delete = method.equals("DELETE");

        if (get && head.equals("info") && n == 1) {
            return ok(info(c));
        }
        if (get && head.equals("namespaces") && n == 1) {
            needRoot(c, Authz.Role.VIEWER);
            return ok(namespaces());
        }
        if (get && head.equals("databases") && n == 4 && seg.get(3).equals("tables")) {
            return ok(tables(c, seg.get(1), seg.get(2)));
        }
        if (get && head.equals("databases") && n == 4 && seg.get(3).equals("programs")) {
            return ok(programs(c, seg.get(1), seg.get(2)));
        }
        if (get && head.equals("databases") && n == 6 && seg.get(3).equals("tables") && seg.get(5).equals("stats")) {
            needScope(c, seg.get(1), seg.get(2), Authz.Role.VIEWER);
            Database database = database(seg.get(1), seg.get(2));
            Catalog.TableDef def = database.catalog().table(seg.get(4));
            if (def == null) {
                throw notFound();
            }
            return ok(TableStats.compute(database, def));
        }
        if (head.equals("users")) {
            if (get && n == 1) {
                needRoot(c, Authz.Role.VIEWER);
                return ok(users());
            }
            if (post && n == 1) {
                needRoot(c, Authz.Role.ADMIN);
                return created(createUser(c, body(req)));
            }
            if (delete && n == 3) {
                needRoot(c, Authz.Role.ADMIN);
                deleteUser(c, seg.get(1), seg.get(2), req);
                return noContent();
            }
        }
        if (head.equals("service-tokens")) {
            if (get && n == 1) {
                needRoot(c, Authz.Role.ADMIN);
                return ok(serviceTokens());
            }
            if (post && n == 1) {
                needRoot(c, Authz.Role.ADMIN);
                // o token de serviço nasce sem auditoria
                needUnaudited(c);
                return created(createServiceToken(body(req)));
            }
            if (delete && n == 2) {
                needRoot(c, Authz.Role.ADMIN);
                if (!TOKEN_ID.matcher(seg.get(1)).matches() || !srv.auth().serviceTokens().revoke(seg.get(1))) {
                    throw notFound();
                }
                return noContent();
            }
        }
        if (head.equals("activity")) {
            return activity(method, seg, req, c);
        }
        if (get && head.equals("cluster") && n == 1) {
            needRoot(c, Authz.Role.VIEWER);
            return ok(cluster());
        }
        if (get && head.equals("saga-definitions") && n == 1) {
            needRoot(c, Authz.Role.VIEWER);
            return ok(sagaDefinitions());
        }
        if (head.equals("sagas")) {
            if (get && n == 1) {
                needRoot(c, Authz.Role.VIEWER);
                return ok(sagas(req));
            }
            if (get && n == 2) {
                needRoot(c, Authz.Role.VIEWER);
                return ok(sagaDetail(seg.get(1)));
            }
            if (post && n == 4 && seg.get(3).equals("cancel")) {
                needRoot(c, Authz.Role.OPERATOR);
                return ok(cancelSaga(c, seg.get(1), seg.get(2)));
            }
        }
        if (head.equals("audit")) {
            return audit(method, seg, req, c);
        }
        if (get && head.equals("data-rules") && n == 1) {
            return ok(dataRules(c, req));
        }
        if (head.equals("truststores")) {
            if (get && n == 1) {
                needRoot(c, Authz.Role.VIEWER);
                return ok(truststores());
            }
            if (post && n == 1) {
                needRoot(c, Authz.Role.ADMIN);
                return created(uploadTruststore(req));
            }
            if (delete && n == 2) {
                needRoot(c, Authz.Role.ADMIN);
                if (!srv.trustStores().remove(seg.get(1))) {
                    throw notFound();
                }
                srv.ds().dropJks(seg.get(1));
                return noContent();
            }
        }
        if (get && head.equals("certificates") && n == 2 && seg.get(1).equals("expiring")) {
            needRoot(c, Authz.Role.VIEWER);
            return ok(expiringCertificates(req));
        }
        if (get && head.equals("logs") && n == 1) {
            needRoot(c, Authz.Role.VIEWER);
            return ok(logs(req));
        }
        AdminV1Response debug = AdminV1Debug.route(srv, this, method, seg, req, c);
        if (debug != null) {
            return debug;
        }
        AdminV1Response extra = AdminV1Extensions.route(srv, this, method, seg, req, c);
        if (extra != null) {
            return extra;
        }
        throw notFound();
    }

    // ------------------------------------------------------------------
    // autenticação e papéis
    // ------------------------------------------------------------------

    private Caller authenticate(HttpServletRequest req) {
        // Teto de papel (Axon-Role-Cap): só rebaixa, como nas demais rotas. Valor inválido dá IllegalArgumentException.
        Authz.Role cap = AxonServer.parseRoleCap(req.getHeader(AxonServer.ROLE_CAP_HEADER));
        if (!srv.requireAuth() || srv.auth() == null) {
            return new Caller(null, null, cap == null ? Authz.Role.ADMIN : cap);
        }
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ApiError(401, "UNAUTHENTICATED", Messages.get("auth_required"));
        }
        Map<String, Object> claims = srv.auth().claims(header.substring("Bearer ".length()).trim());
        if (claims == null) {
            throw new ApiError(401, "UNAUTHENTICATED", Messages.get("auth_invalid"));
        }
        // o auth rebaixado segue para o motor em exec() e na depuração
        AxonValue auth = Authz.cap(AxonServer.claimsValue(claims), cap);
        return new Caller(claims, auth, Authz.role(auth));
    }

    /** Exige escopo ROOT e o papel mínimo. */
    void needRoot(Caller c, Authz.Role min) {
        if (!c.root()) {
            throw new ApiError(403, "FORBIDDEN", Messages.get("auth_forbidden"));
        }
        needRole(c, min);
    }

    void needRole(Caller c, Authz.Role min) {
        if (c.role().compareTo(min) < 0) {
            throw new ApiError(403, "FORBIDDEN",
                Messages.get("auth_role_required", min.name().toLowerCase(java.util.Locale.ROOT)));
        }
    }

    /** Ações que mudam a auditoria sem passar pelo motor: um usuário auditado não as faz. */
    private static void needUnaudited(Caller c) {
        if (Authz.audited(c.auth())) {
            throw new ApiError(403, "FORBIDDEN", Messages.get("audit_user_restricted"));
        }
    }

    /** Rotas por ns/db: ROOT, ou um claim cujo escopo cobre o banco. */
    void needScope(Caller c, String ns, String db, Authz.Role min) {
        if (c.claims() != null && !srv.auth().allows(c.claims(), ns, db)) {
            throw new ApiError(403, "FORBIDDEN", Messages.get("rpc_scope_forbidden"));
        }
        needRole(c, min);
    }

    /** Escritas só no líder; seguidor responde 503 com o endereço do líder. */
    void needLeader() {
        if (!srv.clusterStatus().status().isLeader()) {
            throw notLeader();
        }
    }

    private ApiError notLeader() {
        var s = srv.clusterStatus().status();
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("leaderAddress", s.leaderAddress());
        extra.put("leader", s.leader());
        return new ApiError(503, "NOT_LEADER", Messages.get("server_no_leader"), extra);
    }

    /** Fluxo Server-Sent Events: cada evento do barramento vira uma linha {@code data:}. */
    private void sse(HttpServletRequest req, HttpServletResponse resp,
                     java.util.function.Function<java.util.function.Consumer<String>, AutoCloseable> source)
            throws IOException {
        jakarta.servlet.AsyncContext async = req.startAsync();
        async.setTimeout(0);
        resp.setStatus(200);
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("X-Accel-Buffering", "no");
        java.io.PrintWriter out = resp.getWriter();
        out.write(": connected\n\n");
        out.flush();
        resp.flushBuffer();
        java.util.concurrent.atomic.AtomicReference<AutoCloseable> sub = new java.util.concurrent.atomic.AtomicReference<>();
        sub.set(source.apply(line -> {
            synchronized (out) {
                out.write(line.startsWith(":") ? line + "\n\n" : "data: " + line + "\n\n");
                out.flush();
                if (out.checkError()) {
                    close(sub.get());
                    async.complete();
                }
            }
        }));
        async.addListener(new jakarta.servlet.AsyncListener() {
            @Override public void onComplete(jakarta.servlet.AsyncEvent e) { close(sub.get()); }
            @Override public void onTimeout(jakarta.servlet.AsyncEvent e) { close(sub.get()); }
            @Override public void onError(jakarta.servlet.AsyncEvent e) { close(sub.get()); }
            @Override public void onStartAsync(jakarta.servlet.AsyncEvent e) { }
        });
    }

    private static void close(AutoCloseable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Exception ignored) {
            // cancelar assinatura não falha
        }
    }

    // ------------------------------------------------------------------
    // informação
    // ------------------------------------------------------------------

    private AxonValue info(Caller c) {
        needRoot(c, Authz.Role.VIEWER);
        var s = srv.clusterStatus().status();
        ServerConfig cfg = srv.config();
        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put("path", cfg.path());
        conf.put("port", cfg.port());
        conf.put("bind", cfg.bind());
        conf.put("requireAuth", cfg.requireAuth());
        conf.put("queryTimeoutMs", cfg.queryTimeout());
        conf.put("txnTimeoutMs", cfg.txnTimeout());
        conf.put("rateLimit", cfg.rateLimit());
        conf.put("corsOrigins", cfg.corsOrigins());
        conf.put("tls", !cfg.tlsCert().isBlank());
        conf.put("httpThreads", cfg.httpThreads());
        conf.put("lang", cfg.lang());
        conf.put("s3Configured", !cfg.s3Bucket().isBlank() && !cfg.s3Endpoint().isBlank());
        conf.put("s3Interval", cfg.s3Interval());
        conf.put("s3Retention", cfg.s3Retention());
        conf.put("s3Encrypted", !cfg.s3EncryptKey().isBlank());
        conf.put("auditEnabled", cfg.auditEnabled());
        conf.put("aiProvider", cfg.aiProvider());
        conf.put("aiModel", cfg.aiModel());
        return AdminJson.obj(
            "version", BuildInfo.version(),
            "nodeId", s.nodeId(),
            "clusterId", s.clusterId(),
            "role", s.role().name(),
            "uptimeSeconds", srv.uptimeSeconds(),
            "mode", clusterMode() ? "cluster" : "standalone",
            "config", conf);
    }

    /** Últimas linhas do buffer de log, com filtros {@code limit}, {@code level} (mínimo) e {@code since} (RFC 3339). */
    private AxonValue logs(HttpServletRequest req) {
        int limit = 500;
        String l = req.getParameter("limit");
        if (l != null && !l.isBlank()) {
            try {
                limit = Math.min(Math.max(1, Integer.parseInt(l.trim())), AxonLog.buffer().capacity());
            } catch (NumberFormatException e) {
                throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_limit"));
            }
        }
        long since = 0;
        String sv = req.getParameter("since");
        if (sv != null && !sv.isBlank()) {
            try {
                since = java.time.Instant.parse(sv.trim()).toEpochMilli();
            } catch (java.time.format.DateTimeParseException e) {
                throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_since"));
            }
        }
        List<Object> out = new ArrayList<>();
        for (LogBuffer.Entry e : AxonLog.buffer().snapshot(limit, req.getParameter("level"), since)) {
            out.add(e.toValue());
        }
        return AdminJson.of(out);
    }

    private boolean clusterMode() {
        return !"local".equals(srv.clusterStatus().status().clusterId());
    }

    private AxonValue namespaces() {
        List<Object> out = new ArrayList<>();
        for (String ns : srv.ds().namespaces().stream().sorted().toList()) {
            out.add(AdminJson.obj("name", ns,
                "databases", srv.ds().databases(ns).stream().sorted().toList()));
        }
        return AdminJson.of(out);
    }

    /** Funções, procedures e packages AxonPL do banco, para o painel do Enterprise. */
    private AxonValue programs(Caller c, String ns, String db) {
        needScope(c, ns, db, Authz.Role.VIEWER);
        Database database = database(ns, db);
        List<AxonValue> out = new ArrayList<>();
        for (com.axonbase.core.pl.ProgramDef d : database.programs().all()) {
            out.add(com.axonbase.core.pl.ProgramInfo.listing(d));
        }
        return AxonValue.array(out);
    }

    private AxonValue tables(Caller c, String ns, String db) {
        needScope(c, ns, db, Authz.Role.VIEWER);
        Database database = database(ns, db);
        List<Object> out = new ArrayList<>();
        for (Catalog.TableDef t : database.catalog().tables().stream()
                .sorted(Comparator.comparing(Catalog.TableDef::name)).toList()) {
            List<Object> fields = new ArrayList<>();
            t.fields().values().stream().sorted(Comparator.comparing(Catalog.FieldDef::name))
                .forEach(f -> fields.add(fieldInfo(f)));
            List<Object> indexes = new ArrayList<>();
            t.indexes().values().stream().sorted(Comparator.comparing(Catalog.IndexDef::name))
                .forEach(i -> indexes.add(indexInfo(i)));
            out.add(AdminJson.obj("name", t.name(),
                "schemaMode", t.schemafull() ? "SCHEMAFULL" : "SCHEMALESS",
                "fields", fields, "indexes", indexes));
        }
        return AdminJson.of(out);
    }

    /**
     * Índice como o painel do Enterprise o lê: nome, colunas, tipo e os detalhes do tipo (analisador da busca,
     * dimensão e distância do vetor, parâmetros do HNSW), só quando existem.
     */
    /** Campo para o painel do Enterprise: nome, tipo e, se segue um embedding, qual e de que campo vem o texto. */
    static AxonValue fieldInfo(Catalog.FieldDef f) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", f.name());
        m.put("type", f.type() == null ? "any" : f.type());
        if (f.embeddingName() != null) {
            m.put("embedding", Map.of("name", f.embeddingName(), "from", f.embeddingFrom()));
        }
        return AdminJson.of(m);
    }

    static AxonValue indexInfo(Catalog.IndexDef i) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", i.name());
        m.put("columns", i.columns());
        m.put("kind", indexKind(i));
        if (i.search()) {
            m.put("analyzer", i.searchAnalyzer());
        }
        if (i.vector()) {
            m.put("dimension", i.vectorDimension());
            m.put("distance", i.vectorDistance());
            m.put("hnsw", Map.of("m", i.hnswM(), "efc", i.hnswEfc(), "efs", i.hnswEfs()));
        }
        return AdminJson.of(m);
    }

    static String indexKind(Catalog.IndexDef i) {
        if (i.vector()) {
            return "vector";
        }
        if (i.search()) {
            return "search";
        }
        if (i.geo()) {
            return "geo";
        }
        if (i.columnar()) {
            return "columnar";
        }
        return i.unique() ? "unique" : "standard";
    }

    private Database database(String ns, String db) {
        if (!srv.ds().hasDatabase(ns, db)) {
            throw notFound();
        }
        return srv.ds().ensureDatabase(ns, db);
    }

    // ------------------------------------------------------------------
    // usuários e tokens de serviço
    // ------------------------------------------------------------------

    private AxonValue users() {
        List<Object> out = new ArrayList<>();
        for (AuthCatalog.User u : srv.ds().authCatalog().users()) {
            out.add(AdminJson.obj("name", u.name(), "scope", u.scope().name(), "ns", u.namespace(),
                "db", u.database(), "roles", u.roles(),
                "auth", u.certificateBased() ? "certificate" : "password"));
        }
        return AdminJson.of(out);
    }

    private AxonValue createUser(Caller c, AxonValue body) {
        needLeader();
        Map<String, AxonValue> b = object(body);
        String name = text(b, "name", true);
        String scope = text(b, "scope", true).toUpperCase();
        String password = text(b, "password", true);
        if (!IDENT.matcher(name).matches()) {
            throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_name"));
        }
        String ns = text(b, "ns", false);
        String db = text(b, "db", false);
        String target = switch (scope) {
            case "ROOT" -> "ROOT";
            case "NAMESPACE" -> {
                ident(ns, "ns");
                yield "NAMESPACE " + ns;
            }
            case "DATABASE" -> {
                ident(ns, "ns");
                ident(db, "db");
                yield "DATABASE " + ns + " " + db;
            }
            default -> throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_scope"));
        };
        List<String> roles = new ArrayList<>();
        AxonValue rv = b.get("roles");
        if (rv != null && rv.isArray()) {
            for (AxonValue r : rv.asArray()) {
                if (!r.isString() || !IDENT.matcher(r.asString()).matches()) {
                    throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_role"));
                }
                roles.add(r.asString());
            }
        }
        String sql = "DEFINE USER " + name + " ON " + target + " PASSWORD " + quote(password)
            + (roles.isEmpty() ? "" : " ROLES " + String.join(", ", roles));
        exec(c, "ROOT".equals(scope) ? null : ns, "DATABASE".equals(scope) ? db : null, sql);
        return AdminJson.obj("name", name, "scope", scope, "ns", ns, "db", db, "roles", roles);
    }

    private void deleteUser(Caller c, String scopeRaw, String name, HttpServletRequest req) {
        needUnaudited(c);
        needLeader();
        AuthCatalog.Scope scope;
        try {
            scope = AuthCatalog.Scope.valueOf(scopeRaw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_scope"));
        }
        String ns = scope == AuthCatalog.Scope.ROOT ? null : req.getParameter("ns");
        String db = scope == AuthCatalog.Scope.DATABASE ? req.getParameter("db") : null;
        if (scope != AuthCatalog.Scope.ROOT && (ns == null || ns.isBlank())
            || scope == AuthCatalog.Scope.DATABASE && (db == null || db.isBlank())) {
            throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_scope_params_required"));
        }
        if (!srv.ds().dropUser(null, name, scope, ns, db)) {
            throw notFound();
        }
    }

    private AxonValue serviceTokens() {
        List<Object> out = new ArrayList<>();
        for (ServiceTokenStore.Info t : srv.auth().serviceTokens().list()) {
            out.add(AdminJson.obj("id", t.id(), "name", t.name(), "scope", t.scope(), "ns", t.ns(), "db", t.db(),
                "roles", t.roles(), "createdAt", AdminJson.iso(t.createdAt()),
                "expiresAt", AdminJson.iso(t.expiresAt()),
                "lastUsedAt", t.lastUsedAt() == null ? null : AdminJson.iso(t.lastUsedAt()),
                "revoked", t.revoked()));
        }
        return AdminJson.of(out);
    }

    private AxonValue createServiceToken(AxonValue body) {
        Map<String, AxonValue> b = object(body);
        String name = text(b, "name", true);
        String scope = b.containsKey("scope") ? text(b, "scope", true).toUpperCase() : "ROOT";
        if (!List.of("ROOT", "NAMESPACE", "DATABASE").contains(scope)) {
            throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_scope"));
        }
        String ns = text(b, "ns", false);
        String db = text(b, "db", false);
        if (!scope.equals("ROOT") && (ns == null || ns.isBlank())
            || scope.equals("DATABASE") && (db == null || db.isBlank())) {
            throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_scope_params_required"));
        }
        List<String> roles = new ArrayList<>();
        AxonValue rv = b.get("roles");
        if (rv != null && rv.isArray()) {
            for (AxonValue r : rv.asArray()) {
                String role = r.isString() ? r.asString().toLowerCase() : "";
                if (!List.of("viewer", "operator", "admin").contains(role)) {
                    throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_role"));
                }
                roles.add(role);
            }
        }
        if (roles.isEmpty()) {
            roles.add("viewer");
        }
        int days = b.containsKey("expiresInDays") && b.get("expiresInDays").isNumber()
            ? (int) b.get("expiresInDays").asLong() : 90;
        if (days < 1 || days > 3650) {
            throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_expiry"));
        }
        ServiceTokenStore.Created created = srv.auth().serviceTokens().create(name, scope,
            scope.equals("ROOT") ? null : ns, scope.equals("DATABASE") ? db : null, roles, days);
        return AdminJson.obj("id", created.id(), "token", created.token(),
            "expiresAt", AdminJson.iso(created.expiresAt()));
    }

    // ------------------------------------------------------------------
    // cluster
    // ------------------------------------------------------------------

    private AxonValue cluster() {
        ClusterStatusProvider.Status s = srv.clusterStatus().status();
        List<Object> members = new ArrayList<>();
        ClusterRuntime rt = srv.clusterRuntime();
        if (rt != null) {
            for (ClusterRuntime.PeerInfo p : rt.peerInfos()) {
                members.add(AdminJson.obj("node", p.node(), "address", p.address(),
                    "role", "FOLLOWER", "lastContactMs", p.lastContactMs() < 0 ? null : p.lastContactMs(),
                    "lagEntries", p.lagEntries(), "active", p.active()));
            }
        }
        return AdminJson.obj("mode", clusterMode() ? "cluster" : "standalone", "nodeId", s.nodeId(),
            "clusterId", s.clusterId(), "role", s.role().name(), "leader", s.leader(),
            "leaderAddress", s.leaderAddress(), "term", s.term(), "commitIndex", s.commitIndex(),
            "quorum", s.quorum(), "members", members);
    }

    // ------------------------------------------------------------------
    // SAGA
    // ------------------------------------------------------------------

    private AxonValue sagaDefinitions() {
        List<Object> out = new ArrayList<>();
        for (String name : srv.ds().sagaNames().stream().sorted().toList()) {
            var def = srv.ds().saga(name);
            if (def != null) {
                out.add(AdminJson.obj("name", def.name(), "links", def.links()));
            }
        }
        return AdminJson.of(out);
    }

    private Session sagaSession() {
        return new Session("system", "saga");
    }

    private AxonValue sagas(HttpServletRequest req) {
        int limit = limit(req);
        if (!srv.ds().hasDatabase("system", "saga")) {
            return AdminJson.of(List.of());
        }
        String status = req.getParameter("status");
        String where = "";
        if (status != null && !status.isBlank()) {
            if (!status.matches("[A-Za-z_]+")) {
                throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_status"));
            }
            where = " WHERE status = \"" + status.toUpperCase() + "\"";
        }
        AxonValue rows = srv.ds().execute("SELECT * FROM saga" + where + " ORDER BY created_at DESC LIMIT " + limit,
            sagaSession(), null);
        List<Object> out = new ArrayList<>();
        if (rows.isArray()) {
            rows.asArray().forEach(r -> out.add(sagaRow(r)));
        }
        return AdminJson.of(out);
    }

    private static Object sagaRow(AxonValue r) {
        Map<String, AxonValue> o = r.asObject();
        return AdminJson.obj("name", o.get("name"), "correlationId", o.get("correlation_id"),
            "status", o.get("status"), "createdAt", o.get("created_at"));
    }

    private AxonValue sagaDetail(String correlationId) {
        if (!TOKEN_ID.matcher(correlationId).matches() || !srv.ds().hasDatabase("system", "saga")) {
            throw notFound();
        }
        String esc = correlationId.replace("\\", "\\\\").replace("\"", "\\\"");
        AxonValue rows = srv.ds().execute("SELECT * FROM saga WHERE correlation_id = \"" + esc + "\"",
            sagaSession(), null);
        if (!rows.isArray() || rows.asArray().isEmpty()) {
            throw notFound();
        }
        AxonValue steps = srv.ds().execute("SELECT * FROM saga_step WHERE correlation_id = \"" + esc
            + "\" ORDER BY step_order ASC", sagaSession(), null);
        List<Object> stepOut = new ArrayList<>();
        if (steps.isArray()) {
            for (AxonValue s : steps.asArray()) {
                Map<String, AxonValue> o = s.asObject();
                stepOut.add(AdminJson.obj("order", o.get("step_order"), "ns", o.get("step_ns"),
                    "db", o.get("step_db"), "link", o.get("link_name"), "table", o.get("table_name"),
                    "recordKey", o.get("record_key"), "operation", o.get("operation"), "status", o.get("status")));
            }
        }
        Map<String, AxonValue> saga = rows.asArray().get(0).asObject();
        return AdminJson.obj("name", saga.get("name"), "correlationId", saga.get("correlation_id"),
            "status", saga.get("status"), "createdAt", saga.get("created_at"), "steps", stepOut);
    }

    private AxonValue cancelSaga(Caller c, String name, String correlationId) {
        needLeader();
        if (!IDENT.matcher(name).matches() || !TOKEN_ID.matcher(correlationId).matches()) {
            throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_name"));
        }
        if (!srv.ds().hasDatabase("system", "saga")) {
            throw notFound();
        }
        AxonValue found = srv.ds().execute("SELECT * FROM saga WHERE correlation_id = \"" + correlationId + "\"",
            sagaSession(), null);
        if (!found.isArray() || found.asArray().isEmpty()) {
            throw notFound();
        }
        try {
            exec(c, "system", "saga", "CANCEL SAGA " + name + " WITH CORRELATION '" + correlationId + "'");
        } catch (ApiError e) {
            if (e.status == 400) {
                throw new ApiError(409, "CONFLICT", e.getMessage());
            }
            throw e;
        }
        return AdminJson.obj("status", "FAILED");
    }

    // ------------------------------------------------------------------
    // auditoria
    // ------------------------------------------------------------------

    private AdminV1Response audit(String method, List<String> seg, HttpServletRequest req, Caller c)
            throws IOException {
        int n = seg.size();
        boolean get = method.equals("GET");
        boolean post = method.equals("POST");
        if (n >= 2 && seg.get(1).equals("cases")) {
            if (get && n == 2) {
                needRoot(c, Authz.Role.VIEWER);
                return ok(auditCases(req));
            }
            if (post && n == 4 && seg.get(3).equals("decision")) {
                needRoot(c, Authz.Role.OPERATOR);
                return ok(decideCase(c, seg.get(2), body(req)));
            }
        }
        if (n >= 2 && seg.get(1).equals("blocked-users")) {
            if (get && n == 2) {
                needRoot(c, Authz.Role.VIEWER);
                return ok(blockedUsers());
            }
            if (post && n == 4 && seg.get(3).equals("unblock")) {
                needRoot(c, Authz.Role.OPERATOR);
                needUnaudited(c);
                unblock(seg.get(2));
                return noContent();
            }
        }
        if (get && n == 2 && seg.get(1).equals("operations")) {
            String ns = required(req, "ns");
            String db = required(req, "db");
            needScope(c, ns, db, Authz.Role.VIEWER);
            return ok(auditOperations(ns, db, req));
        }
        if (get && n == 2 && seg.get(1).equals("definitions")) {
            String ns = required(req, "ns");
            String db = required(req, "db");
            needScope(c, ns, db, Authz.Role.VIEWER);
            return ok(auditDefinitions(ns, db));
        }
        throw notFound();
    }

    private AxonValue auditCases(HttpServletRequest req) {
        int limit = limit(req);
        String status = req.getParameter("status");
        Map<String, AuditCase> byHash = new LinkedHashMap<>();
        for (Database db : srv.ds().allDatabases()) {
            for (AuditCase ac : db.auditCatalog().listCases()) {
                byHash.putIfAbsent(ac.hash(), ac);
            }
            for (AuditCase ac : db.auditCatalog().listHistory()) {
                byHash.putIfAbsent(ac.hash(), ac);
            }
        }
        List<Object> out = new ArrayList<>();
        byHash.values().stream()
            .filter(ac -> status == null || status.isBlank() || ac.status().equalsIgnoreCase(status))
            .sorted(Comparator.comparingLong(AuditCase::createdAt).reversed())
            .limit(limit)
            .forEach(ac -> out.add(AdminJson.obj("hash", ac.hash(), "user", ac.userId(), "sql", ac.sql(),
                "auditName", ac.auditName(), "classification", ac.classification(), "status", ac.status(),
                "reason", ac.reason(), "resolvedBy", ac.resolvedBy().isEmpty() ? null : ac.resolvedBy(),
                "createdAt", AdminJson.iso(ac.createdAt()))));
        return AdminJson.of(out);
    }

    private AxonValue decideCase(Caller c, String hash, AxonValue body) {
        needLeader();
        Map<String, AxonValue> b = object(body);
        String decision = text(b, "decision", true).toUpperCase();
        String reason = text(b, "reason", true);
        if (!decision.equals("AUTHORIZED") && !decision.equals("DENIED")) {
            throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_decision"));
        }
        for (Database db : srv.ds().allDatabases()) {
            AuditCase ac = db.auditCatalog().getCase(hash);
            if (ac == null) {
                continue;
            }
            if (!"PENDING".equals(ac.status())) {
                throw new ApiError(409, "CONFLICT", Messages.get("audit_case_resolved", ac.status()));
            }
            exec(c, db.ns(), db.db(), "SET AUDIT CASE " + quote(hash) + " " + decision + " REASON " + quote(reason));
            return AdminJson.obj("hash", hash, "status", decision);
        }
        throw notFound();
    }

    private AxonValue blockedUsers() {
        List<Object> out = new ArrayList<>();
        for (Database db : srv.ds().allDatabases()) {
            for (var bu : db.auditCatalog().listBlockedUsers()) {
                out.add(AdminJson.obj("user", bu.userId(), "auditName", bu.auditName(), "reason", bu.reason(),
                    "blockedAt", AdminJson.iso(bu.blockedAt()), "ns", db.ns(), "db", db.db()));
            }
        }
        return AdminJson.of(out);
    }

    private void unblock(String user) {
        needLeader();
        boolean found = false;
        for (Database db : srv.ds().allDatabases()) {
            AiAuditCatalog cat = db.auditCatalog();
            if (cat.isBlocked(user)) {
                cat.unblockUser(user);
                found = true;
            }
        }
        if (!found) {
            throw notFound();
        }
    }

    private AxonValue auditOperations(String ns, String db, HttpServletRequest req) {
        database(ns, db);
        int limit = limit(req);
        long from = 0;
        String fromRaw = req.getParameter("from");
        if (fromRaw != null && !fromRaw.isBlank()) {
            try {
                from = fromRaw.matches("\\d+") ? Long.parseLong(fromRaw) : java.time.Instant.parse(fromRaw).toEpochMilli();
            } catch (java.time.format.DateTimeParseException e) {
                throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_from"));
            }
        }
        List<AxonValue> entries = new ArrayList<>(srv.ds().readAuditEntries(ns, db));
        java.util.Collections.reverse(entries);
        List<Object> out = new ArrayList<>();
        for (AxonValue e : entries) {
            AxonValue ts = e.isObject() ? e.asObject().get("ts") : null;
            if (from > 0 && ts != null && ts.isNumber() && ts.asLong() < from) {
                continue;
            }
            out.add(e);
            if (out.size() >= limit) {
                break;
            }
        }
        return AdminJson.of(out);
    }

    private AxonValue auditDefinitions(String ns, String db) {
        List<Object> out = new ArrayList<>();
        for (var d : database(ns, db).catalog().audits()) {
            out.add(AdminJson.obj("name", d.name(), "warningWhen", d.warningPrompt(), "dangerWhen", d.dangerPrompt(),
                "createdAt", AdminJson.iso(d.createdAt()), "createdBy", d.createdBy()));
        }
        return AdminJson.of(out);
    }

    // ------------------------------------------------------------------
    // segurança
    // ------------------------------------------------------------------

    private AxonValue dataRules(Caller c, HttpServletRequest req) {
        String ns = required(req, "ns");
        String db = required(req, "db");
        needScope(c, ns, db, Authz.Role.VIEWER);
        List<Object> out = new ArrayList<>();
        for (Catalog.DataRuleDef r : database(ns, db).catalog().dataRules()) {
            Map<String, Object> inj = new LinkedHashMap<>();
            r.injections().forEach((k, v) -> inj.put(k, com.axonbase.parser.Render.expr(v)));
            out.add(AdminJson.obj("name", r.name(), "predicate", com.axonbase.parser.Render.expr(r.predicate()),
                "maskPatterns", r.maskPatterns(), "injections", inj));
        }
        return AdminJson.of(out);
    }

    private AxonValue truststores() {
        List<Object> out = new ArrayList<>();
        for (TrustStoreRegistry.StoreInfo s : srv.trustStores().list()) {
            List<Object> certs = new ArrayList<>();
            s.certificates().forEach(ci -> certs.add(AdminJson.obj("subject", ci.subject(),
                "notAfter", AdminJson.iso(ci.notAfter()))));
            out.add(AdminJson.obj("name", s.name(), "loadedAt", AdminJson.iso(s.loadedAt()), "certificates", certs));
        }
        return AdminJson.of(out);
    }

    private AxonValue uploadTruststore(HttpServletRequest req) throws IOException {
        String ct = req.getContentType();
        if (ct == null || !ct.toLowerCase().startsWith("multipart/form-data")) {
            throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_multipart_required"));
        }
        try {
            String name = partText(req, "name");
            String password = partText(req, "password");
            Part file = req.getPart("file");
            if (name == null || !STORE_NAME.matcher(name).matches() || password == null || file == null) {
                throw new ApiError(400, "BAD_REQUEST", Messages.get("http_truststore_fields_required"));
            }
            Path dir = srv.truststoreDir();
            Files.createDirectories(dir);
            Path target = dir.resolve(name + ".jks");
            // grava num temporário, valida e só então troca de forma atômica: um reenvio inválido
            // nunca destrói o truststore anterior
            Path temp = Files.createTempFile(dir, name + "-", ".jks.tmp");
            try {
                try (InputStream in = file.getInputStream()) {
                    Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    srv.registerTrustStore(name, temp.toString(), password.toCharArray());
                } catch (IllegalArgumentException e) {
                    throw new ApiError(400, "BAD_REQUEST", e.getMessage());
                }
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
            srv.ds().persistJks(name, target.toString());
            return AdminJson.obj("name", name);
        } catch (jakarta.servlet.ServletException e) {
            throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_multipart_required"));
        }
    }

    private static String partText(HttpServletRequest req, String name) throws IOException, jakarta.servlet.ServletException {
        Part p = req.getPart(name);
        if (p == null) {
            return null;
        }
        try (InputStream in = p.getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private AxonValue expiringCertificates(HttpServletRequest req) {
        int days = 30;
        String raw = req.getParameter("days");
        if (raw != null && !raw.isBlank()) {
            try {
                days = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_days"));
            }
        }
        long now = System.currentTimeMillis();
        long limit = now + days * 86_400_000L;
        List<Object> out = new ArrayList<>();
        for (TrustStoreRegistry.StoreInfo s : srv.trustStores().list()) {
            for (TrustStoreRegistry.CertInfo ci : s.certificates()) {
                if (ci.notAfter() <= limit) {
                    out.add(AdminJson.obj("truststore", s.name(), "subject", ci.subject(),
                        "notAfter", AdminJson.iso(ci.notAfter()),
                        "daysLeft", (ci.notAfter() - now) / 86_400_000L));
                }
            }
        }
        return AdminJson.of(out);
    }

    // ------------------------------------------------------------------
    // utilidades
    // ------------------------------------------------------------------

    /** Executa AxonQL com os claims do chamador, para que papéis e escopo valham no motor. */
    AxonValue exec(Caller c, String ns, String db, String sql) {
        Session s = Session.create();
        s.namespace(ns);
        s.database(db);
        s.auth(c.auth());
        try {
            return srv.ds().execute(sql, s, null);
        } catch (AxonError e) {
            throw mapEngine(e);
        }
    }

    private static ApiError mapEngine(AxonError e) {
        if (e.code() == -32002) {
            return new ApiError(403, "FORBIDDEN", e.getMessage());
        }
        return new ApiError(400, "BAD_REQUEST", e.getMessage());
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static void ident(String v, String field) {
        if (v == null || !IDENT.matcher(v).matches()) {
            throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_field", field));
        }
    }

    private static String required(HttpServletRequest req, String name) {
        String v = req.getParameter(name);
        if (v == null || v.isBlank()) {
            throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_param_required", name));
        }
        return v;
    }

    private static int limit(HttpServletRequest req) {
        String raw = req.getParameter("limit");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < 1) {
                throw new NumberFormatException();
            }
            return Math.min(v, MAX_LIMIT);
        } catch (NumberFormatException e) {
            throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_limit"));
        }
    }

    AxonValue body(HttpServletRequest req) throws IOException {
        String raw = new String(req.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (raw.isBlank()) {
            return AxonValue.object(Map.of());
        }
        try {
            return AxonJson.parseDocument(raw);
        } catch (RuntimeException e) {
            throw new ApiError(400, "BAD_REQUEST", Messages.get("http_json_invalid"));
        }
    }

    Map<String, AxonValue> object(AxonValue v) {
        if (v == null || !v.isObject()) {
            throw new ApiError(400, "BAD_REQUEST", Messages.get("http_body_object_required"));
        }
        return v.asObject();
    }

    String text(Map<String, AxonValue> b, String key, boolean required) {
        AxonValue v = b.get(key);
        String s = v != null && v.isString() ? v.asString() : null;
        if (required && (s == null || s.isBlank())) {
            throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_field_required", key));
        }
        return s;
    }

    static ApiError notFound() {
        return new ApiError(404, "NOT_FOUND", Messages.get("admin_not_found"));
    }

    static AdminV1Response ok(AxonValue v) {
        return new AdminV1Response(200, v);
    }

    static AdminV1Response created(AxonValue v) {
        return new AdminV1Response(201, v);
    }

    static AdminV1Response accepted(AxonValue v) {
        return new AdminV1Response(202, v);
    }

    static AdminV1Response noContent() {
        return new AdminV1Response(204, null);
    }

    static void write(HttpServletResponse resp, int status, AxonValue body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(AxonJson.write(body));
    }

    // ------------------------------------------------------------------
    // atividade: consultas em execução e lentas, sessões, transações e conflitos
    // ------------------------------------------------------------------

    /**
     * Visibilidade do que o nó está fazendo e ações operacionais. Leitura: VIEWER. Cancelar consulta e reverter transação:
     * OPERATOR. Encerrar sessão: ADMIN. O texto da consulta só vai para OPERATOR ou ADMIN (VIEWER recebe o fingerprint).
     * O AxonBase não tem locks (concorrência otimista): a disputa aparece como transações abertas e conflitos de versão.
     */
    private AdminV1Response activity(String method, List<String> seg, HttpServletRequest req, Caller c) {
        ActivityRegistry reg = srv.activity();
        int n = seg.size();
        boolean get = method.equals("GET");
        boolean post = method.equals("POST");
        boolean delete = method.equals("DELETE");
        boolean withSql = c.role().compareTo(Authz.Role.OPERATOR) >= 0;
        if (get && n == 2) {
            needRoot(c, Authz.Role.VIEWER);
            switch (seg.get(1)) {
                case "summary" -> {
                    return ok(AdminJson.obj("running", reg.runningCount(), "slowTotal", reg.slowTotal(),
                        "cancelledTotal", reg.cancelledTotal(), "sessionsWs", reg.wsCount(),
                        "sessionsToken", srv.auth() == null ? 0 : srv.auth().sessionCount(), "openTransactions", reg.openTransactions(),
                        "conflictsTotal", com.axonbase.core.storage.ConflictStats.total(),
                        "slowThresholdMs", reg.config().slowQueryMs(), "captureSql", reg.config().captureSql(),
                        "txnTimeoutMs", srv.config().txnTimeout(), "queryTimeoutMs", srv.config().queryTimeout()));
                }
                case "queries" -> {
                    List<Object> out = new ArrayList<>();
                    for (ActivityRegistry.Running r : reg.running()) {
                        out.add(AdminJson.obj("id", r.id(), "user", r.user(), "ns", r.ns(), "db", r.db(),
                            "startedAt", AdminJson.iso(r.startedAtMs()), "elapsedMs", r.elapsedMs(),
                            "protocol", r.protocol(), "remote", r.remote(), "sessionId", r.sessionId(),
                            "fingerprint", r.fingerprint(), "sql", withSql ? r.sql() : null,
                            "cancelRequested", r.handle().cancelled()));
                    }
                    return ok(AdminJson.of(out));
                }
                case "slow" -> {
                    long since = sinceMs(req);
                    List<Object> out = new ArrayList<>();
                    for (ActivityRegistry.SlowEntry e : reg.slow(limit(req), since)) {
                        out.add(AdminJson.obj("id", e.id(), "at", AdminJson.iso(e.tsMs()), "durationMs", e.durationMs(),
                            "user", e.user(), "ns", e.ns(), "db", e.db(), "protocol", e.protocol(),
                            "fingerprint", e.fingerprint(), "sql", withSql ? e.sql() : null,
                            "error", e.error(), "cancelled", e.cancelled()));
                    }
                    return ok(AdminJson.of(out));
                }
                case "top" -> {
                    List<Object> out = new ArrayList<>();
                    for (ActivityRegistry.Stat st : reg.top(req.getParameter("orderBy"), limit(req))) {
                        out.add(AdminJson.obj("fingerprint", st.fingerprint(), "ns", st.ns(), "db", st.db(),
                            "count", st.count(), "errors", st.errors(), "totalMs", st.totalMs(), "meanMs", st.meanMs(),
                            "p95Ms", st.p95Ms(), "maxMs", st.maxMs(), "lastSeenAt", AdminJson.iso(st.lastSeenMs())));
                    }
                    return ok(AdminJson.of(out));
                }
                case "sessions" -> {
                    String currentJti = c.claims() == null ? null : String.valueOf(c.claims().get("jti"));
                    List<Object> out = new ArrayList<>();
                    for (ActivityRegistry.WsSession w : reg.wsSessions()) {
                        out.add(AdminJson.obj("id", w.id(), "type", "ws", "user", w.user(), "ns", w.ns(), "db", w.db(),
                            "since", AdminJson.iso(w.connectedAtMs()), "lastActivityAt", AdminJson.iso(w.lastActivityMs()),
                            "remote", w.remote(), "inTransaction", w.inTransaction(), "busy", reg.sessionBusy(w.id()),
                            "current", false));
                    }
                    for (com.axonbase.server.auth.AuthService.TokenSession t : srv.auth() == null
                        ? List.<com.axonbase.server.auth.AuthService.TokenSession>of() : srv.auth().sessions()) {
                        out.add(AdminJson.obj("id", t.id(), "type", "token", "user", t.user(), "scope", t.scope(),
                            "ns", t.ns(), "db", t.db(), "since", AdminJson.iso(t.issuedAtMs()),
                            "lastActivityAt", AdminJson.iso(t.lastSeenMs()), "expiresAt", AdminJson.iso(t.expiresAtMs()),
                            "current", t.id().equals(currentJti)));
                    }
                    return ok(AdminJson.of(out));
                }
                case "transactions" -> {
                    List<Object> out = new ArrayList<>();
                    long now = System.currentTimeMillis();
                    for (ActivityRegistry.WsSession w : reg.wsSessions()) {
                        if (w.inTransaction()) {
                            long opened = w.txOpenedAtMs();
                            out.add(AdminJson.obj("sessionId", w.id(), "user", w.user(), "ns", w.ns(), "db", w.db(),
                                "openedAt", AdminJson.iso(opened), "ageMs", opened == 0 ? 0 : now - opened,
                                "writes", w.txWrites(), "deletes", w.txDeletes(), "busy", reg.sessionBusy(w.id()),
                                "remote", w.remote()));
                        }
                    }
                    return ok(AdminJson.of(out));
                }
                case "conflicts" -> {
                    List<Object> by = new ArrayList<>();
                    for (var e : com.axonbase.core.storage.ConflictStats.byTarget()) {
                        by.add(AdminJson.obj("target", e.target(), "count", e.count()));
                    }
                    return ok(AdminJson.obj("total", com.axonbase.core.storage.ConflictStats.total(), "byTarget", by));
                }
                default -> throw notFound();
            }
        }
        // cancelar consulta
        if (post && n == 4 && seg.get(1).equals("queries") && seg.get(3).equals("cancel")) {
            needRoot(c, Authz.Role.OPERATOR);
            if (!reg.cancel(seg.get(2), c.name())) {
                throw new ApiError(404, "NOT_FOUND", Messages.get("admin_activity_not_found"));
            }
            AxonLog.log("warn", "activity_query_cancel", "consulta cancelada", Map.of("id", seg.get(2), "by", c.name()));
            return ok(AdminJson.obj("cancelled", true));
        }
        // reverter transação
        if (post && n == 4 && seg.get(1).equals("transactions") && seg.get(3).equals("rollback")) {
            needRoot(c, Authz.Role.OPERATOR);
            ActivityRegistry.WsSession w = reg.wsSession(seg.get(2));
            if (w == null || !w.inTransaction()) {
                throw new ApiError(404, "NOT_FOUND", Messages.get("admin_activity_not_found"));
            }
            if (reg.sessionBusy(w.id())) {
                throw new ApiError(409, "CONFLICT", Messages.get("admin_activity_busy"));
            }
            w.rollback();
            AxonLog.log("warn", "activity_txn_rollback", "transação revertida",
                Map.of("session", w.id(), "user", String.valueOf(w.user()), "by", c.name()));
            return ok(AdminJson.obj("rolledBack", true));
        }
        // encerrar sessão
        if (delete && n == 3 && seg.get(1).equals("sessions")) {
            needRoot(c, Authz.Role.ADMIN);
            String id = seg.get(2);
            String root = srv.config().user();
            ActivityRegistry.WsSession w = reg.wsSession(id);
            if (w != null) {
                if (w.user() != null && w.user().equals(root)) {
                    throw new ApiError(409, "CONFLICT", Messages.get("admin_activity_root"));
                }
                w.kill(c.name());
            } else {
                com.axonbase.server.auth.AuthService.TokenSession t = srv.auth().session(id);
                if (t == null) {
                    throw new ApiError(404, "NOT_FOUND", Messages.get("admin_activity_not_found"));
                }
                String currentJti = c.claims() == null ? null : String.valueOf(c.claims().get("jti"));
                if (id.equals(currentJti)) {
                    throw new ApiError(409, "CONFLICT", Messages.get("admin_activity_self"));
                }
                if (t.user() != null && t.user().equals(root)) {
                    throw new ApiError(409, "CONFLICT", Messages.get("admin_activity_root"));
                }
                srv.auth().endSession(id);
            }
            AxonLog.log("warn", "activity_session_end", "sessão encerrada", Map.of("session", id, "by", c.name()));
            return noContent();
        }
        throw notFound();
    }

    /** {@code since}: instante RFC 3339 ou milissegundos desde a época. Ausente = sem filtro. */
    private static long sinceMs(HttpServletRequest req) {
        String raw = req.getParameter("since");
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return raw.matches("\\d+") ? Long.parseLong(raw) : java.time.Instant.parse(raw).toEpochMilli();
        } catch (java.time.format.DateTimeParseException e) {
            throw new ApiError(400, "BAD_REQUEST", Messages.get("admin_invalid_from"));
        }
    }

    private static void writeError(HttpServletResponse resp, ApiError e) throws IOException {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", e.code);
        err.put("message", e.getMessage());
        err.putAll(e.extra);
        write(resp, e.status, AdminJson.obj("error", err));
    }
}
