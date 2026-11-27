package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.core.security.Authz;
import com.axonbase.core.shard.ShardCodec;
import com.axonbase.core.shard.ShardException;
import com.axonbase.core.shard.ShardMessages;
import com.axonbase.core.shard.ShardHttp;
import com.axonbase.core.shard.ShardNode;
import com.axonbase.core.shard.ShardReply;
import com.axonbase.core.shard.ShardRequest;
import com.axonbase.core.shard.backup.BackupHttp;
import com.axonbase.core.shard.backup.BackupMember;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A rota interna por onde o gateway de shards fala com este nó ({@value ShardHttp#EXECUTE}). Só existe num nó
 * configurado como shard ({@value ShardHttp#SECRET_VARIABLE}); nos demais responde 404. Exige o segredo do gateway e,
 * além dele, a mesma autenticação do {@code /sql}, com a credencial que o gateway repassa.
 */
final class ShardNodeServlet extends HttpServlet {

    private static final int MAX_BODY = 64 << 20;
    private static final int MAX_BACKUP_BODY = 4 << 10;

    private final transient Datastore datastore;
    private final transient Function<HttpServletRequest, Session> sessions;
    private final boolean requireAuth;
    private final transient Supplier<ClusterRuntime> cluster;
    private final transient HybridClock clock;
    private final transient Supplier<BackupMember> backup;
    private final transient Supplier<String> secret;

    ShardNodeServlet(Datastore datastore, Function<HttpServletRequest, Session> sessions, boolean requireAuth,
                     Supplier<ClusterRuntime> cluster, Supplier<BackupMember> backup, Supplier<String> secret) {
        this.secret = secret;
        this.backup = backup;
        this.datastore = datastore;
        this.sessions = sessions;
        this.requireAuth = requireAuth;
        this.cluster = cluster;
        // o relógio que carimba os commits do armazenamento; sem RocksDB basta o do sistema
        this.clock = datastore.rocksBackend().map(RocksDbBackend::clock).orElseGet(HybridClock::system);
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
        String expected = secret.get();
        if (expected == null || expected.isBlank()) {
            // nó que não é shard: como se a rota não existisse, para qualquer método
            resp.sendError(404);
            return;
        }
        String given = req.getHeader(ShardHttp.SECRET_HEADER);
        if (given == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
            given.getBytes(StandardCharsets.UTF_8))) {
            refuse(resp, 401, "segredo do gateway ausente ou inválido");
            return;
        }
        super.service(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (req.getRequestURI().startsWith(BackupHttp.PREFIX)) {
            backup(req, resp);
            return;
        }
        if (!ShardHttp.EXECUTE.equals(req.getRequestURI())) {
            resp.setStatus(404);
            return;
        }
        Session session = sessions.apply(req);
        if (requireAuth && session.auth() == null) {
            refuse(resp, 401, "autenticação obrigatória");
            return;
        }
        byte[] body = req.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            refuse(resp, 413, "requisição grande demais");
            return;
        }
        ShardRequest request;
        try {
            request = ShardCodec.decodeRequest(body, req.getHeader("Authorization"));
        } catch (IllegalArgumentException invalid) {
            refuse(resp, 400, invalid.getMessage());
            return;
        }
        // a credencial foi conferida contra os cabeçalhos: o corpo não pode pedir outro banco
        if (!Objects.equals(request.namespace(), req.getHeader("Axon-Ns"))
            || !Objects.equals(request.database(), req.getHeader("Axon-Db"))) {
            refuse(resp, 403, "namespace ou banco diferente do autorizado");
            return;
        }
        // as definições valem para todo o nó, e o modo de sistema ignora permissões: só root
        boolean maintenance = request.system() || ShardRequest.DEFINITIONS.equals(request.sql());
        if (requireAuth && maintenance && !rootAdmin(session)) {
            refuse(resp, 403, "a manutenção dos shards exige um administrador root");
            return;
        }
        try {
            ShardReply reply = new ShardNode(datastore, cluster.get(), clock).execute(request, session);
            resp.setStatus(200);
            resp.setContentType(ShardHttp.CONTENT_TYPE);
            resp.getOutputStream().write(ShardCodec.encode(reply));
        } catch (RuntimeException e) {
            send(resp, ShardHttp.failure(e));
        }
    }

    /** As rotas do coordenador de backup (veja {@link BackupHttp}); só com o journal ligado neste nó. */
    private void backup(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Session session = sessions.apply(req);
        if (requireAuth && session.auth() == null) {
            refuse(resp, 401, "autenticação obrigatória");
            return;
        }
        if (requireAuth && !rootAdmin(session)) {
            refuse(resp, 403, "o backup exige um administrador root");
            return;
        }
        BackupMember node = backup.get();
        if (node == null) {
            send(resp, ShardHttp.failure(new ShardException(ShardException.Code.REFUSED,
                ShardMessages.get("backup_no_journal", req.getServerName() + ":" + req.getServerPort()))));
            return;
        }
        byte[] body = req.getInputStream().readNBytes(MAX_BACKUP_BODY);
        try {
            AxonValue in = AxonJson.parseDocument(new String(body, StandardCharsets.UTF_8));
            AxonValue out = BackupHttp.serve(node, req.getRequestURI().substring(BackupHttp.PREFIX.length()), in);
            if (out == null) {
                resp.setStatus(404);
                return;
            }
            resp.setStatus(200);
            resp.setContentType("application/json");
            resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
            resp.getWriter().write(AxonJson.write(out));
        } catch (RuntimeException e) {
            send(resp, ShardHttp.failure(e));
        }
    }

    private static boolean rootAdmin(Session session) {
        return Authz.atLeast(session.auth(), Authz.Role.ADMIN)
            && Authz.scope(session.auth()) == AuthCatalog.Scope.ROOT;
    }

    private static void refuse(HttpServletResponse resp, int status, String message) throws IOException {
        send(resp, new ShardHttp.Failure(status, "{\"code\":\"ERR\",\"message\":\""
            + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"detail\":\""
            + (status == 400 ? -32000 : -32002) + "\"}"));
    }

    private static void send(HttpServletResponse resp, ShardHttp.Failure failure) throws IOException {
        resp.setStatus(failure.status());
        resp.setContentType("application/json");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(failure.body());
    }
}
