package com.axonbase.server;

import com.axonbase.common.AxonError;
import com.axonbase.core.cluster.CommitOutcomeUnknownException;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.shard.GatewaySession;
import com.axonbase.core.shard.ShardException;
import com.axonbase.core.shard.ShardGateway;
import com.axonbase.core.shard.ShardInfo;
import com.axonbase.core.shard.ShardMap;
import com.axonbase.core.shard.ShardMapStore;
import com.axonbase.core.shard.ShardMessages;
import com.axonbase.core.shard.ShardMover;
import com.axonbase.core.shard.SlotRange;
import com.axonbase.core.shard.backup.ClusterBackup;
import com.axonbase.core.shard.backup.ClusterManifest;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * O servidor HTTP (HyperText Transfer Protocol) do gateway de shards. Não guarda dados: {@code /sql} executa pelo
 * {@link ShardGateway}, e as rotas de administração movimentam slots pelo {@link ShardMover}.
 */
public final class GatewayServer implements AutoCloseable {

    /** Carimbo do relógio híbrido que o cliente devolve para preservar a ordem entre gateways. */
    private static final String CLOCK_HEADER = "Axon-HLC";
    private static final String ROLE_CAP_HEADER = "Axon-Role-Cap";
    private static final int MAX_BODY = 16 << 20;

    /**
     * @param adminToken credencial das rotas de administração; vazia desliga todas elas
     * @param signin     endereços do shard base, para onde {@code /signin} é repassado
     */
    public record Settings(String bind, int port, String adminToken, List<String> signin) {
        public Settings {
            signin = List.copyOf(signin);
        }
    }

    private final ShardGateway gateway;
    private final ShardMover mover;
    private final ShardMapStore store;
    private final ClusterBackup backups;
    private final Settings settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private Server jetty;
    private int port;

    private GatewayServer(ShardGateway gateway, ShardMover mover, ShardMapStore store, ClusterBackup backups,
                          Settings settings) {
        this.backups = backups;
        this.gateway = gateway;
        this.mover = mover;
        this.store = store;
        this.settings = settings;
    }

    public static GatewayServer start(ShardGateway gateway, ShardMover mover, ShardMapStore store,
                                      ClusterBackup backups, Settings settings) throws Exception {
        GatewayServer server = new GatewayServer(gateway, mover, store, backups, settings);
        server.listen();
        return server;
    }

    public int port() {
        return port;
    }

    private void listen() throws Exception {
        jetty = new Server();
        ServerConnector connector = new ServerConnector(jetty);
        connector.setHost(settings.bind());
        connector.setPort(settings.port());
        jetty.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(handler(this::health)), "/health");
        context.addServlet(new ServletHolder(handler(this::sql)), "/sql");
        context.addServlet(new ServletHolder(handler(this::signin)), "/signin");
        context.addServlet(new ServletHolder(handler(this::map)), "/shard/v1/map");
        context.addServlet(new ServletHolder(handler(this::admin)), "/admin/shard/*");
        context.addServlet(new ServletHolder(handler(this::backup)), "/admin/backup/*");
        jetty.setHandler(context);
        jetty.start();
        port = connector.getLocalPort();
    }

    private interface Route {
        void handle(HttpServletRequest req, HttpServletResponse resp) throws IOException;
    }

    private static HttpServlet handler(Route route) {
        return new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                try {
                    route.handle(req, resp);
                } catch (RuntimeException e) {
                    failure(resp, e);
                }
            }
        };
    }

    private void health(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ShardMap map = gateway.map();
        json(resp, 200, "{\"status\":\"OK\",\"epoch\":" + map.epoch() + ",\"shards\":" + map.shards().size() + "}");
    }

    private void sql(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!"POST".equals(req.getMethod())) {
            resp.setStatus(405);
            return;
        }
        if (req.getHeader(ROLE_CAP_HEADER) != null) {
            // ignorar o teto daria ao cliente mais do que ele pediu
            throw new ShardException(ShardException.Code.REFUSED, ShardMessages.get("role_cap"));
        }
        String script = body(req);
        observe(req.getHeader(CLOCK_HEADER));
        GatewaySession session = new GatewaySession(req.getHeader("Axon-Ns"), req.getHeader("Axon-Db"),
            req.getHeader("Authorization"));
        String correlation = req.getHeader("X-Correlation-Id");
        if (correlation != null && !correlation.isBlank()) {
            session.vars().put("saga_corr", AxonValue.str(correlation));
        }
        AxonValue result = gateway.execute(session, script, null);
        HlcTimestamp clock = gateway.clock();
        resp.setHeader(CLOCK_HEADER, clock.physical() + ":" + clock.logical());
        json(resp, 200, AxonJson.write(result));
    }

    private void observe(String header) {
        if (header == null || header.isBlank()) {
            return;
        }
        int colon = header.indexOf(':');
        try {
            gateway.observe(new HlcTimestamp(Long.parseLong(header.substring(0, colon).trim()),
                Integer.parseInt(header.substring(colon + 1).trim())));
        } catch (NumberFormatException | IndexOutOfBoundsException invalid) {
            throw new AxonError(-32000, "cabeçalho " + CLOCK_HEADER + " inválido: " + header);
        }
    }

    /** O login é do shard base: os usuários são os mesmos em todos, e o token vale em qualquer um. */
    private void signin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String body = body(req);
        for (String endpoint : settings.signin()) {
            try {
                HttpResponse<String> answer = http.send(HttpRequest.newBuilder(
                        URI.create(endpoint.replaceAll("/+$", "") + "/signin")).timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                json(resp, answer.statusCode(), answer.body());
                return;
            } catch (IOException unreachable) {
                // tenta o próximo nó
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        json(resp, 503, "{\"status\":\"ERR\",\"detail\":\"shard base inalcançável\"}");
    }

    private void map(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (refusedAdmin(req, resp)) {
            return;
        }
        json(resp, 200, AxonJson.write(store.require().toValue()));
    }

    private void admin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (refusedAdmin(req, resp)) {
            return;
        }
        if (!"POST".equals(req.getMethod())) {
            resp.setStatus(405);
            return;
        }
        String action = req.getPathInfo() == null ? "" : req.getPathInfo();
        String body = body(req);
        Map<String, AxonValue> in = body.isBlank() ? Map.of() : AxonJson.parseDocument(body).asObject();
        ShardMap result = switch (action) {
            case "/move" -> mover.move(SlotRange.parse(required(in, "slots")), required(in, "target"));
            case "/resume" -> mover.resume();
            case "/abort" -> mover.abort();
            case "/add" -> mover.addShard(new ShardInfo(required(in, "id"), strings(in.get("endpoints"))));
            default -> null;
        };
        if (result == null) {
            resp.setStatus(404);
            return;
        }
        gateway.refresh();
        json(resp, 200, AxonJson.write(result.toValue()));
    }

    /**
     * {@code POST /admin/backup/create} devolve o manifesto de um backup novo; {@code /barrier} avança o ponto
     * alcançável; {@code /seal} (com o manifesto no corpo) fecha os journals que ele usa.
     */
    private void backup(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (refusedAdmin(req, resp)) {
            return;
        }
        if (!"POST".equals(req.getMethod())) {
            resp.setStatus(405);
            return;
        }
        String action = req.getPathInfo() == null ? "" : req.getPathInfo();
        switch (action) {
            case "/create" -> json(resp, 200, AxonJson.write(backups.create().toValue()));
            case "/barrier" -> json(resp, 200, instant(backups.barrier()));
            case "/seal" -> json(resp, 200, instant(backups.sealJournals(
                ClusterManifest.fromValue(AxonJson.parseDocument(body(req))))));
            default -> resp.setStatus(404);
        }
    }

    private static String instant(HlcTimestamp stamp) {
        return "{\"instant\":\"" + java.time.Instant.ofEpochMilli(stamp.physical()) + "\",\"physical\":"
            + stamp.physical() + ",\"logical\":" + stamp.logical() + "}";
    }

    private boolean refusedAdmin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String expected = settings.adminToken() == null || settings.adminToken().isBlank()
            ? null : "Bearer " + settings.adminToken();
        String given = req.getHeader("Authorization");
        if (expected != null && given != null && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        json(resp, expected == null ? 403 : 401, "{\"status\":\"ERR\",\"detail\":\"" + (expected == null
            ? "administração desligada: defina AXON_GATEWAY_ADMIN_TOKEN" : "credencial de administração inválida")
            + "\"}");
        return true;
    }

    private static String required(Map<String, AxonValue> in, String name) {
        AxonValue value = in.get(name);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new AxonError(-32000, "campo obrigatório ausente: " + name);
        }
        return value.asString();
    }

    private static List<String> strings(AxonValue value) {
        if (value == null || !value.isArray()) {
            throw new AxonError(-32000, "campo obrigatório ausente: endpoints");
        }
        return value.asArray().stream().map(AxonValue::asString).toList();
    }

    private static String body(HttpServletRequest req) throws IOException {
        byte[] data = req.getInputStream().readNBytes(MAX_BODY + 1);
        if (data.length > MAX_BODY) {
            throw new AxonError(-32000, "requisição grande demais");
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static void failure(HttpServletResponse resp, RuntimeException error) throws IOException {
        int status = 400;
        String state = "ERR";
        String kind = null;
        if (error instanceof CommitOutcomeUnknownException) {
            status = 504;
            state = "UNKNOWN";
            kind = "OUTCOME_UNKNOWN";
        } else if (error instanceof ShardException shard) {
            kind = shard.code().name();
            switch (shard.code()) {
                case REFUSED -> status = 400;
                case MOVING, STALE_MAP, UNAVAILABLE -> {
                    status = 503;
                    resp.setHeader("Retry-After", "1");
                }
                case PARTIAL_WRITE -> {
                    status = 500;
                    state = "PARTIAL";
                }
            }
        } else if (error instanceof AxonError axon && axon.code() == -32002) {
            status = 403;
        }
        Map<String, AxonValue> out = new LinkedHashMap<>();
        out.put("status", AxonValue.str(state));
        out.put("detail", AxonValue.str(String.valueOf(error.getMessage())));
        if (kind != null) {
            out.put("kind", AxonValue.str(kind));
        }
        json(resp, status, AxonJson.write(AxonValue.object(out)));
    }

    private static void json(HttpServletResponse resp, int status, String body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(body);
    }

    @Override
    public void close() {
        try {
            if (jetty != null) {
                jetty.stop();
            }
        } catch (Exception e) {
            AxonLog.log("error", "gateway_stop_failed", String.valueOf(e.getMessage()));
        }
        gateway.close();
    }
}
