package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.server.auth.Jwt;
import com.axonbase.server.auth.AuthService;
import com.axonbase.server.auth.UserStore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Servidor HTTP do AxonBase baseado en Jetty. Expón os endpoints de health,
 * version, SQL, REST de táboas, signin e RPC JSON.
 */
public final class AxonServer {

    private final Datastore ds;
    private final String secret;
    private final int port;
    private final String bind;
    private final AuthService auth;
    private final boolean requireAuth;
    private volatile com.axonbase.core.cluster.ClusterStatusProvider clusterStatus;
    private final java.util.concurrent.atomic.AtomicLong requests = new java.util.concurrent.atomic.AtomicLong();
    private Server jetty;

    private AxonServer(Datastore ds, String secret, int port, String bind, AuthService auth, boolean requireAuth) {
        this.ds = ds;
        this.secret = secret;
        this.port = port;
        this.bind = bind;
        this.auth = auth;
        this.requireAuth = requireAuth;
        this.clusterStatus = () -> com.axonbase.core.cluster.ClusterStatusProvider.Status.local();
    }

    public static AxonServer start(Datastore ds, String secret, int port) throws Exception {
        AxonServer srv = new AxonServer(ds, secret, port, "127.0.0.1", null, false);
        srv.listen();
        return srv;
    }

    /** Inicia con autenticación: crea usuario root e exige JWT nas queries. */
    public static AxonServer start(Datastore ds, String secret, int port, String user, String pass,
                                   boolean requireAuth) throws Exception {
        UserStore store = new UserStore(ds.authCatalog());
        store.register(user == null ? "root" : user, pass == null ? "" : pass);
        AuthService service = requireAuth ? new AuthService(secret, store) : null;
        AxonServer srv = new AxonServer(ds, secret, port, "127.0.0.1", service, requireAuth);
        srv.listen();
        return srv;
    }

    /** Inicia num porto efémero (sen auth) e devolve a instancia co porto real. */
    public static AxonServer startRandomPort(Datastore ds, String secret) throws Exception {
        return start(ds, secret, 0);
    }

    /** Inicia num porto efémero con autenticación e devolve a instancia co porto real. */
    public static AxonServer startRandomPort(Datastore ds, String secret, String user, String pass,
                                             boolean requireAuth) throws Exception {
        UserStore store = new UserStore(ds.authCatalog());
        store.register(user == null ? "root" : user, pass == null ? "" : pass);
        AuthService service = requireAuth ? new AuthService(secret, store) : null;
        AxonServer srv = new AxonServer(ds, secret, 0, "127.0.0.1", service, requireAuth);
        srv.listen();
        return srv;
    }

    /** Inicia com endereço de bind explícito, usado pela CLI e pelo arquivo de configuração. */
    public static AxonServer start(Datastore ds, String secret, int port, String user, String pass,
                                   boolean requireAuth, String bind) throws Exception {
        UserStore store = new UserStore(ds.authCatalog());
        store.register(user == null ? "root" : user, pass == null ? "" : pass);
        AuthService service = requireAuth ? new AuthService(secret, store) : null;
        AxonServer srv = new AxonServer(ds, secret, port,
            bind == null || bind.isBlank() ? "127.0.0.1" : bind, service, requireAuth);
        srv.listen();
        return srv;
    }

    private void listen() throws Exception {
        Server jetty = new Server();
        ServerConnector connector = new ServerConnector(jetty);
        connector.setHost(bind);
        connector.setPort(port);
        jetty.addConnector(connector);
        jetty.setHandler(handlers());
        jetty.start();
        this.jetty = jetty;
    }

    private org.eclipse.jetty.server.Handler handlers() {
        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");
        ctx.addServlet(new ServletHolder(new HealthServlet()), "/health");
        ctx.addServlet(new ServletHolder(new ReadyServlet()), "/ready");
        ctx.addServlet(new ServletHolder(new StatusServlet()), "/status");
        ctx.addServlet(new ServletHolder(new VersionServlet()), "/version");
        ctx.addServlet(new ServletHolder(new MetricsServlet()), "/metrics");
        ctx.addServlet(new ServletHolder(new SqlServlet()), "/sql/*");
        ctx.addServlet(new ServletHolder(new TableServlet()), "/table/*");
        ctx.addServlet(new ServletHolder(new SigninServlet()), "/signin");
        ctx.addServlet(new ServletHolder(new RpcServlet(ds, auth, requireAuth)), "/rpc");
        ctx.addServlet(new ServletHolder(new ExportServlet()), "/export");
        ctx.addServlet(new ServletHolder(new ImportServlet()), "/import");
        JettyWebSocketServletContainerInitializer.configure(ctx, (sc, wsContainer) -> {
            // configuración extra do contenedor websocket (baleira no MVP)
        });
        ctx.addServlet(new ServletHolder(new WsRpcServlet(ds, auth, requireAuth)), "/rpc/ws/*");
        return ctx;
    }

    public int port() {
        return ((ServerConnector) jetty.getConnectors()[0]).getLocalPort();
    }
    /** Liga o status de um grupo Raft real aos endpoints /ready e /status. */
    public void clusterStatus(com.axonbase.core.cluster.ClusterStatusProvider provider) {
        this.clusterStatus = provider == null ? () -> com.axonbase.core.cluster.ClusterStatusProvider.Status.local() : provider;
    }

    public void stop() throws Exception {
        if (jetty != null) {
            jetty.stop();
        }
    }

    // ------------------------------------------------------------------
    // servlets
    // ------------------------------------------------------------------

    private static final class HealthServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            json(resp, 200, "{\"status\":\"ok\"}");
        }
    }

    private final class ReadyServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            var status=clusterStatus.status();
            json(resp,status.ready()?200:503,"{\"ready\":"+status.ready()+",\"leader\":\""+esc(String.valueOf(status.leader()))+"\"}");
        }
    }
    private final class StatusServlet extends HttpServlet {
        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            var s=clusterStatus.status();
            json(resp,200,"{\"node_id\":\""+esc(s.nodeId())+"\",\"cluster_id\":\""+esc(s.clusterId())+"\",\"leader\":\""+esc(String.valueOf(s.leader()))+"\",\"term\":"+s.term()+",\"commit_index\":"+s.commitIndex()+",\"active\":"+s.active()+",\"quorum\":"+s.quorum()+"}");
        }
    }

    private static final class VersionServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            json(resp, 200, "\"0.1.0-SNAPSHOT\"");
        }
    }

    private final class MetricsServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            json(resp, 200,
                "axe_requests_total " + requests.get() + "\n"
                + "axe_ws_open 0\n"
                + "axe_uptime_seconds 1\n");
        }
    }

    private final class SqlServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            requests.incrementAndGet();
            String body = readBody(req);
            Session s = withNames(req);
            String error = null;
            AxonValue result = null;
            try {
                result = ds.execute(body, s, null);
            } catch (RuntimeException e) {
                error = e.getMessage();
            }
            if (error != null) {
                json(resp, 400, "{\"status\":\"ERR\",\"detail\":\"" + esc(error) + "\"}");
            } else {
                json(resp, 200, AxonJson.write(result));
            }
        }
    }

    private final class TableServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String table = tablePath(req);
            Session s = withNames(req);
            AxonValue rows = ds.execute("SELECT * FROM " + table, s, null);
            json(resp, 200, AxonJson.write(rows));
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String table = tablePath(req);
            Session s = withNames(req);
            AxonValue data = AxonJson.parseDocument(readBody(req));
            String sql = "CREATE " + table + " CONTENT " + AxonJson.write(data);
            AxonValue out = ds.execute(sql, s, null);
            json(resp, 200, AxonJson.write(out));
        }
    }

    private final class SigninServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String body = readBody(req);
            AxonValue v = AxonJson.parseDocument(body);
            if (!v.isObject() || !v.asObject().containsKey("user") || !v.asObject().containsKey("pass")) {
                json(resp, 400, "{\"code\":-32002,\"message\":\"credenciais inválidas\"}");
                return;
            }
            String user = v.asObject().get("user").asString();
            String pass = v.asObject().get("pass").asString();
            String token;
            if (auth != null) {
                token = auth.signin(user, pass);
            } else {
                token = Jwt.sign(secret, Map.of("id", user), 3600_000L).value();
            }
            if (token == null) {
                json(resp, 401, "{\"code\":-32002,\"message\":\"credenciais inválidas\"}");
                return;
            }
            json(resp, 200, "{\"code\":200,\"details\":\"Authentication succeeded\",\"token\":\"" + token + "\"}");
        }
    }

    private final class RpcServlet extends HttpServlet {
        private final RpcDispatcher dispatcher;

        RpcServlet(Datastore ds) {
            this.dispatcher = new RpcDispatcher(ds);
        }

        RpcServlet(Datastore ds, com.axonbase.server.auth.AuthService auth, boolean requireAuth) {
            this.dispatcher = new RpcDispatcher(ds, auth, requireAuth);
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            requests.incrementAndGet();
            Session s = withNames(req);
            String respBody = dispatcher.dispatch(readBody(req), s);
            resp.setStatus(200);
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            resp.getWriter().write(respBody);
        }
    }

    private final class ExportServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            Session s = withNames(req);
            String ns = s.namespace() != null ? s.namespace() : "";
            String db = s.database() != null ? s.database() : "";
            String dump = ds.exportDatabase(ns, db);
            resp.setStatus(200);
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("UTF-8");
            resp.getWriter().write(dump);
        }
    }

    private final class ImportServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            Session s = withNames(req);
            String ns = s.namespace() != null ? s.namespace() : "";
            String db = s.database() != null ? s.database() : "";
            String dump = readBody(req);
            try {
                ds.importDatabase(ns, db, dump);
                json(resp, 200, "{\"status\":\"ok\"}");
            } catch (RuntimeException e) {
                json(resp, 400, "{\"status\":\"ERR\",\"detail\":\"" + esc(e.getMessage()) + "\"}");
            }
        }
    }

    private String readBody(HttpServletRequest req) throws IOException {
        return new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private Session withNames(HttpServletRequest req) {
        Session s = Session.create();
        String ns = req.getHeader("Axon-Ns");
        String db = req.getHeader("Axon-Db");
        if (ns != null) {
            s.namespace(ns);
        }
        if (db != null) {
            s.database(db);
        }
        // HTTP não mantém sessão entre requests: o token vem em cada chamada.
        String authorization = req.getHeader("Authorization");
        if (auth != null && authorization != null && authorization.startsWith("Bearer ")) {
            Map<String, Object> claims = auth.claims(authorization.substring("Bearer ".length()));
            if (claims != null && auth.allows(claims, s.namespace(), s.database())) {
                s.auth(claimsValue(claims));
            }
        }
        return s;
    }

    private static AxonValue claimsValue(Map<String, Object> claims) {
        Map<String, AxonValue> out = new java.util.LinkedHashMap<>();
        claims.forEach((key, value) -> out.put(key, value instanceof Number n
            ? AxonValue.num(n.longValue()) : value instanceof Boolean b
                ? AxonValue.bool(b) : value == null ? AxonValue.nul() : AxonValue.str(String.valueOf(value))));
        return AxonValue.object(out);
    }

    private String tablePath(HttpServletRequest req) {
        String rest = req.getPathInfo() == null ? "" : req.getPathInfo();
        return rest.startsWith("/") ? rest.substring(1) : rest;
    }

    private static String orEmpty(String v) {
        return v == null ? "" : v;
    }

    private static Map<String, AxonValue> mapOf(AxonValue v) {
        return v.isObject() ? v.asObject() : Map.of();
    }

    private static void json(HttpServletResponse resp, int status, String body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(body);
    }

    private static String rpcError(AxonValue id, int code, String msg) {
        return "{\"id\":" + idJson(id) + ",\"error\":{\"code\":" + code + ",\"message\":\"" + esc(msg) + "\"}}";
    }

    private static String idJson(AxonValue id) {
        return id == null ? "null" : AxonJson.write(id);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
