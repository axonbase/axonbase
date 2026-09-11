package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.common.Messages;
import com.axonbase.server.auth.Jwt;
import com.axonbase.server.auth.AuthService;
import com.axonbase.server.auth.UserStore;
import com.axonbase.server.auth.CertificateChallengeService;
import com.axonbase.server.auth.TrustStoreRegistry;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import jakarta.servlet.DispatcherType;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;

public final class AxonServer {

    private final Datastore ds;
    private final String secret;
    private final int port;
    private final String bind;
    private final AuthService auth;
    private final boolean requireAuth;
    private final ServerConfig config;
    private final TrustStoreRegistry trustStores;
    private final CertificateChallengeService certificateChallenges = new CertificateChallengeService();
    private volatile com.axonbase.core.cluster.ClusterStatusProvider clusterStatus;
    private volatile com.axonbase.core.cluster.ClusterRuntime clusterRuntime;
    private final java.util.List<RpcDispatcher> dispatchers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong requests = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong wsOpen = new java.util.concurrent.atomic.AtomicLong();
    private final AtomicLong wsFramesReceived = new AtomicLong();
    private final AtomicLong wsFramesSent = new AtomicLong();
    private final AtomicLong wsResponseTimeNanos = new AtomicLong();
    private final AtomicLong wsResponseCount = new AtomicLong();
    private final AtomicLong wsQueryErrors = new AtomicLong();
    private final AtomicLong wsNotLeaderErrors = new AtomicLong();
    private final AtomicLong queryCount = new AtomicLong();
    private final AtomicLong querySlowCount = new AtomicLong();
    private final AtomicLong queryErrorCount = new AtomicLong();
    private final AtomicLong signinCount = new AtomicLong();
    private final AtomicLong signinFailCount = new AtomicLong();
    private final AtomicLong storageKeys = new AtomicLong();
    private volatile long startedAtNanos;
    private Server jetty;
    private volatile boolean stopping;
    private final ExecutorService queryExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private AxonServer(Datastore ds, String secret, int port, String bind, AuthService auth,
                       boolean requireAuth, ServerConfig config) {
        this.ds = ds;
        this.secret = secret;
        this.port = port;
        this.bind = bind;
        this.auth = auth;
        this.requireAuth = requireAuth;
        this.config = config;
        this.trustStores = new TrustStoreRegistry();
        this.clusterStatus = com.axonbase.core.cluster.ClusterStatusProvider.Status::local;
    }

    public static AxonServer start(Datastore ds, String secret, int port) throws Exception {
        ServerConfig cfg = ServerConfig.defaults();
        AxonServer srv = new AxonServer(ds, secret, port, "127.0.0.1", null, false, cfg);
        srv.listen();
        return srv;
    }

    public static AxonServer start(Datastore ds, String secret, int port, String user, String pass,
                                   boolean requireAuth) throws Exception {
        ServerConfig cfg = ServerConfig.defaults();
        UserStore store = new UserStore(ds.authCatalog());
        store.register(user == null ? "root" : user, pass == null ? "" : pass);
        AuthService service = requireAuth ? new AuthService(secret, store) : null;
        AxonServer srv = new AxonServer(ds, secret, port, "127.0.0.1", service, requireAuth, cfg);
        srv.listen();
        return srv;
    }

    public static AxonServer startRandomPort(Datastore ds, String secret) throws Exception {
        return start(ds, secret, 0);
    }

    public static AxonServer startRandomPort(Datastore ds, String secret, String user, String pass,
                                             boolean requireAuth) throws Exception {
        ServerConfig cfg = ServerConfig.defaults();
        UserStore store = new UserStore(ds.authCatalog());
        store.register(user == null ? "root" : user, pass == null ? "" : pass);
        AuthService service = requireAuth ? new AuthService(secret, store) : null;
        AxonServer srv = new AxonServer(ds, secret, 0, "127.0.0.1", service, requireAuth, cfg);
        srv.listen();
        return srv;
    }

    public static AxonServer start(Datastore ds, String secret, int port, String user, String pass,
                                   boolean requireAuth, String bind) throws Exception {
        ServerConfig cfg = ServerConfig.defaults();
        UserStore store = new UserStore(ds.authCatalog());
        store.register(user == null ? "root" : user, pass == null ? "" : pass);
        AuthService service = requireAuth ? new AuthService(secret, store) : null;
        AxonServer srv = new AxonServer(ds, secret, port,
            bind == null || bind.isBlank() ? "127.0.0.1" : bind, service, requireAuth, cfg);
        srv.listen();
        return srv;
    }

    public static AxonServer start(Datastore ds, String secret, ServerConfig cfg) throws Exception {
        Messages.setLanguage(cfg.lang());
        UserStore store = new UserStore(ds.authCatalog());
        store.register(cfg.user() == null ? "root" : cfg.user(), cfg.password() == null ? "" : cfg.password());
        AuthService service = cfg.requireAuth() ? new AuthService(secret, store) : null;
        AxonServer srv = new AxonServer(ds, secret, cfg.port(),
            cfg.bind() == null || cfg.bind().isBlank() ? "127.0.0.1" : cfg.bind(),
            service, cfg.requireAuth(), cfg);
        ds.jksRegistrar((name, path, password, collector, oids) ->
            srv.trustStores.register(name, path, password, collector, oids));
        if (!cfg.tlsCa().isBlank() && !cfg.tlsKey().isBlank()) {
            try {
                srv.trustStores.register("icp_brasil", cfg.tlsCa(), cfg.tlsKey().toCharArray(),
                    "ICPBRASIL", java.util.List.of());
                System.err.println("{\"event\":\"truststore_loaded\",\"name\":\"icp_brasil\",\"path\":\""
                    + cfg.tlsCa() + "\"}");
            } catch (Exception e) {
                System.err.println("{\"event\":\"truststore_load_failed\",\"name\":\"icp_brasil\",\"detail\":\""
                    + e.getMessage() + "\"}");
            }
        }
        srv.listen();
        return srv;
    }

    private void listen() throws Exception {
        QueuedThreadPool pool = new QueuedThreadPool(config.httpThreads(), 1, config.httpQueue());
        Server jetty = new Server(pool);

        if (!config.tlsCert().isBlank() && !config.tlsKey().isBlank()) {
            SslContextFactory.Server ssl = new SslContextFactory.Server();
            ssl.setKeyStorePath(config.tlsCert());
            ssl.setKeyStorePassword(config.tlsKey());
            ssl.setKeyStoreType("PKCS12");
            ssl.setIncludeProtocols("TLSv1.3", "TLSv1.2");
            if (!config.tlsCa().isBlank()) {
                ssl.setTrustStorePath(config.tlsCa());
                ssl.setTrustStorePassword(config.tlsKey());
                ssl.setWantClientAuth(true);
            }
            ServerConnector connector = new ServerConnector(jetty, ssl);
            connector.setHost(bind);
            connector.setPort(port);
            jetty.addConnector(connector);
        } else {
            ServerConnector connector = new ServerConnector(jetty);
            connector.setHost(bind);
            connector.setPort(port);
            jetty.addConnector(connector);
        }
        if (config.plainPort() > 0) {
            ServerConnector plain = new ServerConnector(jetty);
            plain.setHost(bind);
            plain.setPort(config.plainPort());
            jetty.addConnector(plain);
        }

        jetty.setHandler(handlers());
        jetty.start();
        this.startedAtNanos = System.nanoTime();
        this.jetty = jetty;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                stop();
            } catch (Exception e) {
                System.err.println("{\"time\":\"" + java.time.Instant.now()
                    + "\",\"event\":\"shutdown_error\",\"message\":\"" + e.getMessage() + "\"}");
            }
        }, "ax-shutdown"));
    }

    private org.eclipse.jetty.server.Handler handlers() {
        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");

        ctx.addServlet(new ServletHolder(new HealthServlet()), "/health");
        ctx.addServlet(new ServletHolder(new ReadyServlet()), "/ready");
        ctx.addServlet(new ServletHolder(new StatusServlet()), "/status");
        ctx.addServlet(new ServletHolder(new VersionServlet()), "/version");
        ctx.addServlet(new ServletHolder(new MetricsServlet()), "/metrics");
        ctx.addServlet(new ServletHolder(new AdminClusterServlet()), "/admin/cluster/join");
        ctx.addServlet(new ServletHolder(new AdminClusterServlet()), "/admin/cluster/leave");
        ctx.addServlet(new ServletHolder(new AdminTrustStoreServlet()), "/admin/truststores");
        ctx.addServlet(new ServletHolder(new CertificateLoginServlet()), "/certificate/login/*");
        ctx.addServlet(new ServletHolder(new ShardMoveServlet()), "/admin/shard/move");
        ctx.addServlet(new ServletHolder(new SqlServlet()), "/sql/*");
        ctx.addServlet(new ServletHolder(new TableServlet()), "/table/*");
        ctx.addServlet(new ServletHolder(new SigninServlet()), "/signin");
        RpcServlet rpc = new RpcServlet(ds, auth, requireAuth, trustStores);
        WsRpcServlet ws = new WsRpcServlet(ds, auth, requireAuth, trustStores, wsOpen,
            wsFramesReceived, wsFramesSent, wsResponseTimeNanos, wsResponseCount,
            wsQueryErrors, wsNotLeaderErrors,
            config.queryTimeout(), config.txnTimeout());
        dispatchers.add(rpc.dispatcher);
        dispatchers.add(ws.dispatcher());
        ctx.addServlet(new ServletHolder(rpc), "/rpc");
        ctx.addServlet(new ServletHolder(new ExportServlet()), "/export");
        ctx.addServlet(new ServletHolder(new ImportServlet()), "/import");
        ctx.addServlet(new ServletHolder(new GraphQLServlet(ds, this::withNames, requireAuth)), "/graphql");
        ctx.addServlet(new ServletHolder(new McpServlet(ds, this::withNames, requireAuth)), "/mcp/*");
        JettyWebSocketServletContainerInitializer.configure(ctx, (sc, wsContainer) -> {});
        ctx.addServlet(new ServletHolder(ws), "/rpc/ws/*");

        if (!config.corsOrigins().isBlank()) {
            jakarta.servlet.Filter corsFilter = new jakarta.servlet.Filter() {
                @Override
                public void doFilter(jakarta.servlet.ServletRequest request,
                                     jakarta.servlet.ServletResponse response,
                                     jakarta.servlet.FilterChain chain)
                        throws IOException, jakarta.servlet.ServletException {
                    jakarta.servlet.http.HttpServletResponse resp =
                        (jakarta.servlet.http.HttpServletResponse) response;
                    resp.setHeader("Access-Control-Allow-Origin", config.corsOrigins());
                    resp.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                    resp.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, Axon-Ns, Axon-Db, X-Request-Id, X-Correlation-Id");
                    resp.setHeader("Access-Control-Allow-Credentials", "true");
                    if ("OPTIONS".equalsIgnoreCase(((jakarta.servlet.http.HttpServletRequest) request).getMethod())) {
                        resp.setStatus(204);
                        return;
                    }
                    chain.doFilter(request, response);
                }
                @Override public void init(jakarta.servlet.FilterConfig filterConfig) {}
                @Override public void destroy() {}
            };
            ctx.addFilter(new org.eclipse.jetty.servlet.FilterHolder(corsFilter), "/*", EnumSet.of(DispatcherType.REQUEST));
        }

        ctx.addFilter(new org.eclipse.jetty.servlet.FilterHolder(new LoggingFilter()), "/*", EnumSet.of(DispatcherType.REQUEST));
        ctx.addFilter(new org.eclipse.jetty.servlet.FilterHolder(new RateLimitFilter(config.rateLimit())), "/*", EnumSet.of(DispatcherType.REQUEST));

        return ctx;
    }

    public int port() {
        return ((ServerConnector) jetty.getConnectors()[0]).getLocalPort();
    }

    public void clusterStatus(com.axonbase.core.cluster.ClusterStatusProvider provider) {
        this.clusterStatus = provider == null
            ? com.axonbase.core.cluster.ClusterStatusProvider.Status::local : provider;
        this.clusterRuntime = provider instanceof com.axonbase.core.cluster.ClusterRuntime rt ? rt : null;
        dispatchers.forEach(dispatcher -> dispatcher.clusterStatus(() -> clusterStatus.status()));
    }

    /** Registers a JKS in memory without persisting its supplied password. */
    public void registerTrustStore(String name, String path, char[] password) {
        trustStores.register(name, path, password);
    }

    private boolean redirectWrites(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        var status = clusterStatus.status();
        if (status.isLeader()) {
            return false;
        }
        if (!status.leaderAddress().isEmpty()) {
            String query = req.getQueryString();
            resp.setHeader("Location", "http://" + status.leaderAddress() + req.getRequestURI()
                + (query == null ? "" : "?" + query));
            resp.setHeader("Axon-Leader", status.leader());
            resp.setStatus(307);
            return true;
        }
        resp.setHeader("Axon-Leader", status.leader());
        json(resp, 503, "{\"status\":\"ERR\",\"kind\":\"NOT_LEADER\",\"leader\":\""
            + esc(status.leader()) + "\",\"leader_address\":\"\",\"detail\":\""
            + esc(Messages.get("server_no_leader")) + "\"}");
        return true;
    }

    private boolean requireAuthenticated(Session session, HttpServletResponse resp) throws IOException {
        if (!requireAuth || session.auth() != null) {
            return false;
        }
        json(resp, 401, "{\"code\":-32002,\"message\":\"" + esc(Messages.get("auth_required")) + "\"}");
        return true;
    }

    private boolean requireRoot(Session session, HttpServletResponse resp) throws IOException {
        if (!requireAuth || session.auth() == null || !session.auth().isObject()
            || !"ROOT".equals(valueOf(session.auth().asObject().get("scope")))) {
        json(resp, 403, "{\"code\":-32002,\"message\":\"" + esc(Messages.get("auth_forbidden")) + "\"}");
            return true;
        }
        return false;
    }

    public void stop() throws Exception {
        if (jetty == null) {
            return;
        }
        stopping = true;
        long deadline = System.currentTimeMillis() + config.shutdownTimeout();
        while (System.currentTimeMillis() < deadline && requests.get() > 0) {
            Thread.sleep(100);
        }
        jetty.stop();
        jetty = null;
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
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            var s = clusterStatus.status();
            json(resp, s.ready() ? 200 : 503, "{\"ready\":" + s.ready()
                + ",\"role\":\"" + s.role() + "\""
                + ",\"leader\":\"" + esc(s.leader()) + "\""
                + ",\"active\":" + s.active() + ",\"quorum\":" + s.quorum() + "}");
        }
    }

    private final class StatusServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            var s = clusterStatus.status();
            json(resp, 200, "{\"node_id\":\"" + esc(s.nodeId()) + "\""
                + ",\"cluster_id\":\"" + esc(s.clusterId()) + "\""
                + ",\"role\":\"" + s.role() + "\""
                + ",\"leader\":\"" + esc(s.leader()) + "\""
                + ",\"leader_address\":\"" + esc(s.leaderAddress()) + "\""
                + ",\"term\":" + s.term()
                + ",\"commit_index\":" + s.commitIndex()
                + ",\"active\":" + s.active()
                + ",\"members\":" + s.members()
                + ",\"quorum\":" + s.quorum()
                + ",\"ready\":" + s.ready() + "}");
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
            var status = clusterStatus.status();
            double uptime = Math.max(0, System.nanoTime() - startedAtNanos) / 1_000_000_000.0;
            resp.setStatus(200);
            resp.setContentType("text/plain; version=0.0.4");
            resp.setCharacterEncoding("UTF-8");

            // JVM memory
            MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();

            var sb = new StringBuilder();
            sb.append("# TYPE axe_requests_total counter\n")
              .append("axe_requests_total ").append(requests.get()).append("\n")
              .append("# TYPE axe_ws_open gauge\n")
              .append("axe_ws_open ").append(wsOpen.get()).append("\n")
              .append("# TYPE axe_ws_frames_received_total counter\n")
              .append("axe_ws_frames_received_total ").append(wsFramesReceived.get()).append("\n")
              .append("# TYPE axe_ws_frames_sent_total counter\n")
              .append("axe_ws_frames_sent_total ").append(wsFramesSent.get()).append("\n")
              .append("# TYPE axe_ws_response_time_ns_total counter\n")
              .append("axe_ws_response_time_ns_total ").append(wsResponseTimeNanos.get()).append("\n")
              .append("# TYPE axe_ws_response_count_total counter\n")
              .append("axe_ws_response_count_total ").append(wsResponseCount.get()).append("\n")
              .append("# TYPE axe_ws_query_errors_total counter\n")
              .append("axe_ws_query_errors_total ").append(wsQueryErrors.get()).append("\n")
              .append("# TYPE axe_ws_not_leader_errors_total counter\n")
              .append("axe_ws_not_leader_errors_total ").append(wsNotLeaderErrors.get()).append("\n")
              // Query metrics
              .append("# TYPE axe_query_total counter\n")
              .append("axe_query_total ").append(queryCount.get()).append("\n")
              .append("# TYPE axe_query_slow_total counter\n")
              .append("axe_query_slow_total ").append(querySlowCount.get()).append("\n")
              .append("# TYPE axe_query_error_total counter\n")
              .append("axe_query_error_total ").append(queryErrorCount.get()).append("\n")
              // Auth metrics
              .append("# TYPE axe_auth_signin_total counter\n")
              .append("axe_auth_signin_total ").append(signinCount.get()).append("\n")
              .append("# TYPE axe_auth_signin_fail_total counter\n")
              .append("axe_auth_signin_fail_total ").append(signinFailCount.get()).append("\n")
              // Storage metrics
              .append("# TYPE axe_storage_keys_total gauge\n")
              .append("axe_storage_keys_total ").append(storageKeys.get()).append("\n")
              // JVM metrics
              .append("# TYPE axe_jvm_heap_bytes gauge\n")
              .append("axe_jvm_heap_bytes ").append(memory.getHeapMemoryUsage().getUsed()).append("\n")
              .append("# TYPE axe_jvm_heap_max_bytes gauge\n")
              .append("axe_jvm_heap_max_bytes ").append(memory.getHeapMemoryUsage().getMax()).append("\n")
              .append("# TYPE axe_jvm_nonheap_bytes gauge\n")
              .append("axe_jvm_nonheap_bytes ").append(memory.getNonHeapMemoryUsage().getUsed()).append("\n")
              .append("# TYPE axe_uptime_seconds gauge\n")
              .append("axe_uptime_seconds ").append(uptime).append("\n")
              .append("# TYPE axe_cluster_role gauge\n")
              .append("axe_cluster_role{role=\"").append(status.role()).append("\"} 1\n")
              .append("# TYPE axe_cluster_term gauge\n")
              .append("axe_cluster_term ").append(status.term()).append("\n")
              .append("# TYPE axe_cluster_commit_index gauge\n")
              .append("axe_cluster_commit_index ").append(status.commitIndex()).append("\n")
              .append("# TYPE axe_cluster_active gauge\n")
              .append("axe_cluster_active ").append(status.active()).append("\n")
              .append("# TYPE axe_cluster_members gauge\n")
              .append("axe_cluster_members ").append(status.members()).append("\n")
              .append("# TYPE axe_cluster_quorum gauge\n")
              .append("axe_cluster_quorum ").append(status.quorum()).append("\n");
            var rt = clusterRuntime;
            if (rt != null) {
                long lag = rt.peerLag().values().stream().mapToLong(Long::longValue).sum();
                sb.append("# TYPE axe_raft_lag gauge\n")
                  .append("axe_raft_lag ").append(lag).append("\n")
                  .append("# TYPE axe_raft_election_started_total counter\n")
                  .append("axe_raft_election_started_total ").append(rt.electionsStarted()).append("\n")
                  .append("# TYPE axe_raft_quorum_unavailable_total counter\n")
                  .append("axe_raft_quorum_unavailable_total ").append(rt.quorumUnavailable()).append("\n")
                  .append("# TYPE axe_raft_snapshot_bytes_total counter\n")
                  .append("axe_raft_snapshot_bytes_total ").append(rt.snapshotBytes()).append("\n");
            }
            resp.getWriter().write(sb.toString());
        }
    }

    private final class AdminClusterServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            requests.incrementAndGet();
            Session s = withNames(req);
            if (requireAuthenticated(s, resp)) {
                return;
            }
            var rt = clusterRuntime;
            if (rt == null) {
                json(resp, 503, "{\"status\":\"ERR\",\"detail\":\"" + esc(Messages.get("http_cluster_unconfigured")) + "\"}");
                return;
            }
            var status = rt.status();
            if (!status.isLeader()) {
                if (!status.leaderAddress().isEmpty()) {
                    resp.setHeader("Location", "http://" + status.leaderAddress() + req.getRequestURI());
                    resp.setStatus(307);
                } else {
                    json(resp, 503, "{\"status\":\"ERR\",\"kind\":\"NOT_LEADER\",\"detail\":\"" + esc(Messages.get("http_cluster_leader_required")) + "\"}");
                }
                return;
            }
            AxonValue body = AxonJson.parseDocument(readBody(req));
            if (!body.isObject() || !body.asObject().containsKey("address")) {
                json(resp, 400, "{\"status\":\"ERR\",\"detail\":\"" + esc(Messages.get("http_body_address_required")) + "\"}");
                return;
            }
            String node = body.asObject().get("node") == null ? ""
                : body.asObject().get("node").asString();
            String address = body.asObject().get("address").asString();
            boolean join = req.getRequestURI().endsWith("/join");
            boolean changed;
            try {
                if (join) {
                    changed = rt.addPeer(node, parseAddress(address));
                } else {
                    changed = rt.removePeer(address);
                }
            } catch (IllegalArgumentException e) {
                json(resp, 400, "{\"status\":\"ERR\",\"detail\":\"" + esc(e.getMessage()) + "\"}");
                return;
            }
            var after = rt.status();
            json(resp, 200, "{\"status\":\"" + (changed ? "OK" : "NOOP")
                + "\",\"added\":" + join + ",\"members\":" + after.members()
                + ",\"quorum\":" + after.quorum() + ",\"peer\":\"" + esc(address) + "\"}");
        }
    }

    private final class AdminTrustStoreServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            requests.incrementAndGet();
            if (requireRoot(withNames(req), resp)) {
                return;
            }
            AxonValue body;
            try {
                body = AxonJson.parseDocument(readBody(req));
            } catch (RuntimeException e) {
                json(resp, 400, "{\"status\":\"ERR\",\"detail\":\"" + esc(Messages.get("http_json_invalid")) + "\"}");
                return;
            }
            if (!body.isObject()) {
                json(resp, 400, "{\"status\":\"ERR\",\"detail\":\"" + esc(Messages.get("http_body_object_required")) + "\"}");
                return;
            }
            Map<String, AxonValue> values = body.asObject();
            String name = valueOf(values.get("name"));
            String path = valueOf(values.get("path"));
            String password = valueOf(values.get("password"));
            if (name.isBlank() || path.isBlank() || password.isBlank()) {
                json(resp, 400, "{\"status\":\"ERR\",\"detail\":\"" + esc(Messages.get("http_truststore_fields_required")) + "\"}");
                return;
            }
            char[] secret = password.toCharArray();
            try {
                registerTrustStore(name, path, secret);
                json(resp, 200, "{\"status\":\"OK\",\"name\":\"" + esc(name) + "\"}");
            } catch (IllegalArgumentException e) {
                json(resp, 400, "{\"status\":\"ERR\",\"detail\":\"" + esc(e.getMessage()) + "\"}");
            }
        }
    }

    private static InetSocketAddress parseAddress(String raw) {
        int i = raw.lastIndexOf(':');
        if (i < 1) {
            throw new IllegalArgumentException(Messages.get("server_invalid_address", raw));
        }
        return new InetSocketAddress(raw.substring(0, i), Integer.parseInt(raw.substring(i + 1)));
    }

    private final class CertificateLoginServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            requests.incrementAndGet();
            String path = req.getPathInfo();
            if (path == null || path.startsWith("/")) {
                path = path == null ? "" : path.substring(1);
            }
            String store = path;
            if (store.isBlank()) {
                json(resp, 400, "{\"status\":\"ERR\",\"detail\":\"" + esc(Messages.get("cert_store_required")) + "\"}");
                return;
            }
            String ns = req.getParameter("ns");
            String db = req.getParameter("db");
            if (ns == null || db == null) {
                json(resp, 400, "{\"status\":\"ERR\",\"detail\":\"" + esc(Messages.get("cert_scope_required")) + "\"}");
                return;
            }

            java.security.cert.X509Certificate[] certChain = (java.security.cert.X509Certificate[])
                req.getAttribute("jakarta.servlet.request.X509Certificate");

            String lang = Messages.language();
            String htmlLang = lang.equals("en") ? "en" : "pt-BR";

            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html lang=\"").append(htmlLang).append("\"><head><meta charset=\"UTF-8\">");
            html.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
            html.append("<title>").append(Messages.get("cert_login_title")).append("</title>");
            html.append("<style>");
            html.append(":root{color-scheme:dark;--ink:#edf2ff;--muted:#9aa9c7;--line:#273558;--panel:#111a2e;--panel-2:#17233d;--accent:#72e1bd;--accent-ink:#07271e;--danger:#ff9a8d}");
            html.append("*{box-sizing:border-box}body{margin:0;min-height:100vh;background:#09101f;color:var(--ink);font-family:Inter,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;line-height:1.5}");
            html.append("body:before{content:'';position:fixed;inset:0;pointer-events:none;background:radial-gradient(circle at 10% 5%,#193866 0,transparent 31%),radial-gradient(circle at 95% 88%,#16453b 0,transparent 32%)}");
            html.append(".shell{position:relative;width:min(900px,calc(100% - 40px));margin:0 auto;padding:64px 0 80px}.masthead{display:flex;justify-content:space-between;align-items:flex-start;gap:24px;margin-bottom:34px}");
            html.append(".eyebrow{display:block;color:var(--accent);font:700 11px/1.2 ui-monospace,SFMono-Regular,Menlo,monospace;letter-spacing:.16em;text-transform:uppercase;margin-bottom:12px}");
            html.append("h1{font-size:clamp(34px,5vw,58px);letter-spacing:-.055em;line-height:.98;margin:0;color:#fff;max-width:600px}.subtitle{max-width:620px;margin:18px 0 0;color:var(--muted);font-size:17px}");
            html.append(".secure-mark{border:1px solid var(--line);border-radius:999px;padding:8px 12px;color:var(--muted);font:600 11px/1 ui-monospace,SFMono-Regular,Menlo,monospace;white-space:nowrap}");
            html.append(".credential{background:linear-gradient(145deg,#162342,#0f172a);border:1px solid #38527d;border-radius:18px;padding:25px;box-shadow:0 26px 55px rgba(0,0,0,.25)}");
            html.append(".credential-head{display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid var(--line);padding-bottom:18px;margin-bottom:4px}.credential-title{font-size:16px;font-weight:700}.status{color:var(--accent);font:700 11px/1 ui-monospace,SFMono-Regular,Menlo,monospace;letter-spacing:.12em;text-transform:uppercase}");
            html.append(".credential-row{display:grid;grid-template-columns:minmax(140px,180px) minmax(0,1fr) auto;align-items:center;gap:18px;padding:20px 0;border-bottom:1px solid rgba(72,94,137,.45)}.credential-row:last-of-type{border-bottom:0}.label{color:var(--muted);font-size:13px;font-weight:700}.secret{overflow-wrap:anywhere;color:#fff;font:600 19px/1.35 ui-monospace,SFMono-Regular,Menlo,monospace;letter-spacing:.02em}");
            html.append(".cpy-btn{appearance:none;background:var(--accent);color:var(--accent-ink);border:0;border-radius:8px;padding:10px 13px;cursor:pointer;font:700 12px/1.15 Inter,-apple-system,BlinkMacSystemFont,sans-serif;transition:transform .15s,background .15s;white-space:nowrap}.cpy-btn:hover{transform:translateY(-1px);background:#9af0d1}.cpy-btn.copied{background:#bcd3ff;color:#10234c}");
            html.append(".expires{display:block;margin-top:20px;color:var(--danger);font:700 12px/1.3 ui-monospace,SFMono-Regular,Menlo,monospace;letter-spacing:.04em}.info{margin-top:20px;background:rgba(17,26,46,.78);border:1px solid var(--line);border-radius:14px;padding:22px;color:var(--muted)}.info strong{display:block;color:#fff;font-size:14px;margin-bottom:14px}.connection-line{display:flex;gap:14px;align-items:flex-start;padding:8px 0;border-top:1px solid rgba(72,94,137,.35)}.connection-line:first-of-type{border-top:0}.connection-line span{min-width:100px;color:var(--muted);font-weight:700;font-size:13px}.connection-line code{color:#d9e5ff;overflow-wrap:anywhere;font:500 13px/1.45 ui-monospace,SFMono-Regular,Menlo,monospace}");
            html.append("input{width:100%;padding:12px;border-radius:8px;border:1px solid var(--line);background:#09101f;color:#fff;font-size:16px}button[type=submit]{margin-top:16px;border:0;border-radius:8px;background:var(--accent);padding:11px 16px;color:var(--accent-ink);font-weight:700;cursor:pointer}@media(max-width:620px){.shell{width:min(100% - 28px,900px);padding-top:38px}.masthead{display:block}.secure-mark{display:inline-block;margin-top:22px}.credential{padding:18px}.credential-row{grid-template-columns:1fr;gap:8px}.cpy-btn{justify-self:start}.connection-line{display:block}.connection-line span{display:block;margin-bottom:4px}}</style></head><body>");
            html.append("<main class=\"shell\"><header class=\"masthead\"><div><span class=\"eyebrow\">").append(Messages.get("cert_login_eyebrow")).append("</span><h1>").append(Messages.get("cert_login_heading")).append("</h1><p class=\"subtitle\">").append(Messages.get("cert_login_subtitle")).append("</p></div><span class=\"secure-mark\">TLS / CERTIFICATE</span></header>");

            if (certChain != null && certChain.length > 0) {
                try {
                    java.security.KeyStore trustStore = trustStores.get(store);
                    if (trustStore == null) {
                        html.append("<div class=\"info\">").append(Messages.get("cert_store_not_found", escHtml(store))).append("</div>");
                        html.append("</body></html>");
                        html(resp, 200, html.toString());
                        return;
                    }
                    var fingerprint = certificateChallenges.verifyChain(
                        java.util.List.of(certChain), trustStore);

                    String user = req.getParameter("user");
                    if (user == null || user.isBlank()) {
                        user = trustStores.usesIcpBrasilCollector(store)
                            ? extractIcpBrasilUserId(certChain[0])
                            : trustStores.collectUser(store, certChain[0]);
                    }
                    System.err.println("{\"event\":\"certificate_login\",\"subject\":\""
                        + esc(certChain[0].getSubjectX500Principal().getName())
                        + "\",\"issuer\":\"" + esc(certChain[0].getIssuerX500Principal().getName())
                        + "\",\"extracted_user\":\"" + esc(user == null ? "" : user) + "\"}");

                    if (user == null || user.isBlank()) {
                        html.append("<div class=\"info\">").append(Messages.get("cert_no_user")).append("</div>");
                        html.append("<form method=\"get\">");
                        html.append("<input type=\"hidden\" name=\"ns\" value=\"").append(escHtml(ns)).append("\">");
                        html.append("<input type=\"hidden\" name=\"db\" value=\"").append(escHtml(db)).append("\">");
                        html.append("<label>").append(Messages.get("cert_type_cpf")).append("<br>");
                        html.append("<input type=\"text\" name=\"user\" pattern=\"[0-9]{11}|[0-9]{14}\" required style=\"font-size:18px;padding:8px;width:100%\">");
                        html.append("</label><br><br><button type=\"submit\" style=\"padding:10px 20px;font-size:16px\">").append(Messages.get("cert_confirm")).append("</button>");
                        html.append("</form>");
                        html.append("</body></html>");
                        html(resp, 200, html.toString());
                        return;
                    }

                    if (!user.matches("[0-9]{11}|[0-9]{14}")) {
                        html.append("<div class=\"info\">").append(Messages.get("cert_invalid_cpf", escHtml(user))).append("</div>");
                        html.append("</body></html>");
                        html(resp, 200, html.toString());
                        return;
                    }

                    long remainingSec = auth.remainingCooldown(user, store);
                    if (remainingSec > 0) {
                        html.append("<div class=\"info\" style=\"border-color:var(--danger)\"><strong>").append(Messages.get("cert_credential_unavailable")).append("</strong>");
                        html.append("<p>").append(Messages.get("cert_rate_limited", remainingSec)).append("</p></div>");
                        html.append("</body></html>");
                        html(resp, 200, html.toString());
                        return;
                    }

                    String credential = auth.issueTemporaryCredential(user, store, fingerprint.fingerprint());

                    String copyUserLabel = Messages.get("cert_copy_user");
                    String copyPassLabel = Messages.get("cert_copy_password");
                    String copiedLabel = Messages.get("cert_copied");

                    html.append("<section class=\"credential\"><div class=\"credential-head\"><span class=\"credential-title\">").append(Messages.get("cert_credential_ready")).append("</span><span class=\"status\">").append(Messages.get("cert_verified")).append("</span></div>");
                    html.append("<div class=\"credential-row\"><span class=\"label\">").append(Messages.get("cert_user_label")).append("</span>");
                    html.append("<code id=\"cred-user\" class=\"secret\">").append(escHtml(user)).append("</code>");
                    html.append("<button class=\"cpy-btn\" data-copy=\"cred-user\" onclick=\"copyToClipboard('cred-user','")
                        .append(escHtml(copyUserLabel)).append("','").append(escHtml(copiedLabel)).append("')\">")
                        .append(copyUserLabel).append("</button>");
                    html.append("</div>");
                    html.append("<div class=\"credential-row\"><span class=\"label\">").append(Messages.get("cert_password_label")).append("</span>");
                    html.append("<code id=\"cred-pass\" class=\"secret\">").append(escHtml(credential)).append("</code>");
                    html.append("<button class=\"cpy-btn\" data-copy=\"cred-pass\" onclick=\"copyToClipboard('cred-pass','")
                        .append(escHtml(copyPassLabel)).append("','").append(escHtml(copiedLabel)).append("')\">")
                        .append(copyPassLabel).append("</button>");
                    html.append("</div><span id=\"expires\" class=\"expires\"></span></section>");
                    html.append("<script>function copyToClipboard(e,b,c){var t=document.getElementById(e);")
                        .append("navigator.clipboard.writeText(t.textContent).then(function(){")
                        .append("var btn=document.querySelector('[data-copy=\"'+e+'\"]');btn.textContent=c;btn.className='cpy-btn copied';")
                        .append("setTimeout(function(){btn.textContent=b;btn.className='cpy-btn'},2000)})}var n=60,e=document.getElementById('expires');")
                        .append("function tick(){e.textContent='").append(escHtml(Messages.get("cert_expires_template"))).append("'.replace('__SECONDS__',n);if(n>0){n--;setTimeout(tick,1000)}}tick()</script>");
                    html.append("<div class=\"info\">");
                    html.append("<strong>").append(Messages.get("cert_connection")).append("</strong>");
                    html.append("<div class=\"connection-line\"><span>").append(Messages.get("cert_url_label")).append("</span><code>jdbc:axonbase:ws://").append(escHtml(req.getServerName()));
                    html.append(":8080/rpc/ws?ns=").append(escHtml(ns)).append("&amp;db=").append(escHtml(db)).append("</code></div>");
                    html.append("<div class=\"connection-line\"><span>").append(Messages.get("cert_user_field")).append("</span><code>").append(escHtml(user)).append("</code></div>");
                    html.append("<div class=\"connection-line\"><span>").append(Messages.get("cert_password_field")).append("</span><code>").append(escHtml(credential)).append("</code></div>");
                    html.append("<p>").append(Messages.get("cert_ns_db")).append("</p>");
                    html.append("</div>");
                } catch (Exception e) {
                    html.append("<div class=\"info\">").append(Messages.get("cert_error", escHtml(e.getMessage()))).append("</div>");
                }
            } else {
                html.append("<div class=\"info\">").append(Messages.get("cert_no_chain")).append("</div>");
            }
            html.append("</main></body></html>");
            html(resp, 200, html.toString());
        }

        private String escHtml(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
        }
    }

    private final class ShardMoveServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            requests.incrementAndGet();
            Session s = withNames(req);
            if (requireAuthenticated(s, resp)) {
                return;
            }
            var status = clusterStatus.status();
            if (!status.isLeader()) {
                if (!status.leaderAddress().isEmpty()) {
                    resp.setHeader("Location", "http://" + status.leaderAddress() + req.getRequestURI());
                    resp.setStatus(307);
                } else {
                    json(resp, 503, "{\"status\":\"ERR\",\"kind\":\"NOT_LEADER\",\"detail\":\"" + esc(Messages.get("http_shard_leader_required")) + "\"}");
                }
                return;
            }
            AxonValue body = AxonJson.parseDocument(readBody(req));
            if (!body.isObject()) {
                json(resp, 400, "{\"status\":\"ERR\",\"detail\":\"" + esc(Messages.get("http_body_object_required")) + "\"}");
                return;
            }
            var obj = body.asObject();
            String ns = obj.containsKey("ns") ? obj.get("ns").asString() : "";
            String db = obj.containsKey("db") ? obj.get("db").asString() : "";
            String target = obj.containsKey("to") ? obj.get("to").asString() : "";
            if (ns.isEmpty() || db.isEmpty() || target.isEmpty()) {
                json(resp, 400, "{\"status\":\"ERR\",\"detail\":\"" + esc(Messages.get("http_shard_fields_required")) + "\"}");
                return;
            }
            json(resp, 200, "{\"status\":\"OK\",\"detail\":\"shard move iniciado\",\"ns\":\"" + esc(ns) + "\",\"db\":\"" + esc(db) + "\",\"to\":\"" + esc(target) + "\"}");
        }
    }

    private final class SqlServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            requests.incrementAndGet();
            String body = readBody(req);
            if (com.axonbase.core.engine.Datastore.isMutation(body) && redirectWrites(req, resp)) {
                return;
            }
            Session s = withNames(req);
            if (requireAuthenticated(s, resp)) {
                return;
            }
            AxonValue result = null;
            String error = null;
            if (config.queryTimeout() > 0) {
                Future<AxonValue> future = queryExecutor.submit(() -> ds.execute(body, s, null));
                try {
                    result = future.get(config.queryTimeout(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    error = Messages.get("query_timeout");
                } catch (Exception e) {
                    error = e.getCause() instanceof RuntimeException re ? re.getMessage() : e.getMessage();
                }
            } else {
                try {
                    result = ds.execute(body, s, null);
                } catch (RuntimeException e) {
                    error = e.getMessage();
                }
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
            if (requireAuthenticated(s, resp)) {
                return;
            }
            AxonValue rows = ds.execute("SELECT * FROM " + table, s, null);
            json(resp, 200, AxonJson.write(rows));
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            if (redirectWrites(req, resp)) {
                return;
            }
            String table = tablePath(req);
            Session s = withNames(req);
            if (requireAuthenticated(s, resp)) {
                return;
            }
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
                json(resp, 400, "{\"code\":-32002,\"message\":\"" + esc(Messages.get("auth_invalid")) + "\"}");
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
                json(resp, 401, "{\"code\":-32002,\"message\":\"" + esc(Messages.get("auth_invalid")) + "\"}");
                return;
            }
            json(resp, 200, "{\"code\":200,\"details\":\"" + esc(Messages.get("http_auth_success")) + "\",\"token\":\"" + token + "\"}");
        }
    }

    private final class RpcServlet extends HttpServlet {
        final RpcDispatcher dispatcher;

        RpcServlet(Datastore ds) {
            this.dispatcher = new RpcDispatcher(ds);
        }

        RpcServlet(Datastore ds, com.axonbase.server.auth.AuthService auth, boolean requireAuth,
                   TrustStoreRegistry trustStores) {
            this.dispatcher = new RpcDispatcher(ds, auth, requireAuth, 0, trustStores);
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            requests.incrementAndGet();
            Session s = withNames(req);
            String body = readBody(req);
            String respBody;
            if (config.queryTimeout() > 0) {
                Future<String> future = queryExecutor.submit(() -> dispatcher.dispatch(body, s));
                try {
                    respBody = future.get(config.queryTimeout(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    respBody = "{\"id\":null,\"error\":{\"code\":-32028,\"message\":\"" + esc(Messages.get("query_timeout")) + "\"}}";
                } catch (Exception e) {
                    respBody = "{\"id\":null,\"error\":{\"code\":-32003,\"message\":\"" + esc(Messages.get("rpc_internal_error", e.getMessage())) + "\"}}";
                }
            } else {
                respBody = dispatcher.dispatch(body, s);
            }
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
            if (requireAuthenticated(s, resp)) {
                return;
            }
            if (redirectWrites(req, resp)) {
                return;
            }
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
            if (requireAuthenticated(s, resp)) {
                return;
            }
            if (redirectWrites(req, resp)) {
                return;
            }
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
        String correlationId = req.getHeader("X-Correlation-Id");
        if (correlationId != null && !correlationId.isBlank()) {
            s.vars().set("saga_corr", AxonValue.str(correlationId));
        }
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

    private static String valueOf(AxonValue value) {
        return value != null && value.isString() ? value.asString() : "";
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

    private static void html(HttpServletResponse resp, int status, String body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("text/html; charset=UTF-8");
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

    /**
     * Extrai CPF ou CNPJ de um certificado ICP-Brasil.
     *
     * <p>Ordem de busca:
     * 1) OIDs do Subject DN (2.16.76.1.3.1 / 2.16.76.1.3.7);
     * 2) CN do formato ICP-Brasil "NOME:CPF" ou "NOME:CNPJ";
     * 3) Extensões do certificado 2.16.76.1.3.1 (pessoa física) e
     *    2.16.76.1.3.7 (pessoa jurídica), onde o CPF/CNPJ fica numa
     *    UTF8String/PrintableString dentro do DER da extensão.</p>
     */
    private static String extractIcpBrasilUserId(java.security.cert.X509Certificate certificate) {
        try {
            LdapName ldapName = new LdapName(certificate.getSubjectX500Principal().getName());
            for (Rdn rdn : ldapName.getRdns()) {
                String type = rdn.getType();
                if ("2.16.76.1.3.1".equals(type) || "2.16.76.1.3.7".equals(type)
                    || "CN".equalsIgnoreCase(type)) {
                    String value = String.valueOf(rdn.getValue());
                    String digits = findIcpDigits(value);
                    if (digits != null) {
                        return digits;
                    }
                }
            }
        } catch (Exception ignored) {
            // segue para a extensão
        }
        String fromExtension = extractIcpOidExtension(certificate, "2.16.76.1.3.1");
        if (fromExtension != null) {
            return fromExtension;
        }
        return extractIcpOidExtension(certificate, "2.16.76.1.3.7");
    }

    /** Extrai CPF/CNPJ do subject DN (CN=ALVARO:CPF ou OID 2.16.76.1.3.x). */
    private static String findIcpDigits(String value) {
        if (value == null) {
            return null;
        }
        // Formato "NOME:CPF"
        int colon = value.lastIndexOf(':');
        if (colon > 0 && colon < value.length() - 1) {
            String digits = value.substring(colon + 1).replaceAll("[^0-9]", "");
            if (digits.length() == 11 || digits.length() == 14) {
                return digits;
            }
        }
        // Número direto
        for (String chunk : value.replaceAll("[^0-9]", " ").trim().split("\\s+")) {
            String digits = chunk;
            if (digits.length() == 11 || digits.length() == 14) {
                return digits;
            }
        }
        return null;
    }

    /**
     * Lê a extensão 2.16.76.1.3.1 (pessoa física) ou 2.16.76.1.3.7 (pessoa
     * jurídica) da ICP-Brasil. O valor é um OCTET STRING que envolve uma
     * estrutura DER; o CPF/CNPJ está numa UTF8String/PrintableString nos bytes.
     * Percorre o DER em busca de strings com 11 ou 14 dígitos.
     */
    private static String extractIcpOidExtension(java.security.cert.X509Certificate certificate, String oid) {
        try {
            byte[] octetString = certificate.getExtensionValue(oid);
            if (octetString == null || octetString.length < 4) {
                return null;
            }
            // Descartar o OCTET STRING externo (tag 0x04 + comprimento)
            byte[] content = unwrapOctetString(octetString);
            if (content == null) {
                return null;
            }
            String firstName = null;
            int i = 0;
            while (i < content.length) {
                int tag = content[i] & 0xFF;
                int clazz = tag >> 6;
                int constructed = tag & 0x20;
                int tagNumber = tag & 0x1F;
                i++;
                int[] len = {0};
                i = skipLength(content, i, len);
                if (i + len[0] > content.length) {
                    break;
                }
                boolean isString = tagNumber == 12 || tagNumber == 19 || tagNumber == 20
                    || tagNumber == 22 || tagNumber == 30; // UTF8, Printable, IA5, etc.
                if (isString && clazz == 0 && constructed == 0) {
                    String text = new String(content, i, len[0], java.nio.charset.StandardCharsets.UTF_8);
                    String digits = findIcpDigits(text);
                    if (digits != null) {
                        return digits;
                    }
                }
                if (firstName == null && (tagNumber == 12 || tagNumber == 19)) {
                    firstName = new String(content, i, len[0], java.nio.charset.StandardCharsets.UTF_8);
                }
                i += len[0];
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] unwrapOctetString(byte[] octetString) {
        // tag 0x04, depois comprimento
        if (octetString.length < 2 || (octetString[0] & 0xFF) != 0x04) {
            return null;
        }
        int i = 1;
        if ((octetString[i] & 0x80) != 0) {
            int n = octetString[i] & 0x7F;
            int len = 0;
            for (int j = 0; j < n; j++) {
                len = (len << 8) | (octetString[++i] & 0xFF);
            }
            i++;
            byte[] out = new byte[len];
            System.arraycopy(octetString, i, out, 0, len);
            return out;
        }
        int len = octetString[i] & 0xFF;
        byte[] out = new byte[len];
        System.arraycopy(octetString, i + 1, out, 0, len);
        return out;
    }

    private static int skipLength(byte[] data, int i, int[] len) {
        if (i >= data.length) {
            return data.length;
        }
        int first = data[i] & 0xFF;
        i++;
        if ((first & 0x80) == 0) {
            len[0] = first;
            return i;
        }
        int n = first & 0x7F;
        int length = 0;
        for (int j = 0; j < n && i < data.length; j++) {
            length = (length << 8) | (data[i++] & 0xFF);
        }
        len[0] = length;
        return i;
    }
}
