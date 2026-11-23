package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.cluster.ClusterConfig;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.engine.Datastore;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Métricas e /version da E0. */
class MetricsE0Test {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static HttpResponse<String> get(int port, String path, String... headers) throws Exception {
        var b = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path)).GET();
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(int port, String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Axon-Ns", "n").header("Axon-Db", "d")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static double metric(String body, String name) {
        Matcher m = Pattern.compile("^" + Pattern.quote(name) + " ([0-9.eE+-]+)$", Pattern.MULTILINE).matcher(body);
        assertTrue(m.find(), "métrica ausente: " + name + "\n" + body);
        return Double.parseDouble(m.group(1));
    }

    @Test
    void contadoresDeConsultaEHistogramaSaemDoZero() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("n", "d");
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing");
        try {
            assertEquals(200, post(server.port(), "/sql", "CREATE t:1 CONTENT {a: 1}").statusCode());
            assertEquals(200, post(server.port(), "/sql", "SELECT * FROM t").statusCode());
            assertEquals(400, post(server.port(), "/sql", "SELECT FROM FROM").statusCode());
            String m = get(server.port(), "/metrics").body();

            assertTrue(metric(m, "axe_query_total") >= 3, m);
            assertTrue(metric(m, "axe_query_error_total") >= 1, m);
            assertTrue(metric(m, "axe_requests_total") >= 3, m);
            assertTrue(metric(m, "axe_storage_keys_total") >= 1, m);
            assertTrue(m.contains("axe_http_requests_total{endpoint=\"/sql\",status=\"200\"} 2"), m);
            assertTrue(m.contains("axe_http_requests_total{endpoint=\"/sql\",status=\"400\"} 1"), m);
            assertTrue(metric(m, "axe_query_duration_seconds_count") >= 3, m);
            assertTrue(m.contains("axe_query_duration_seconds_bucket{le=\"0.001\"}"), m);
            assertTrue(m.contains("axe_query_duration_seconds_bucket{le=\"30\"}"), m);
            assertTrue(m.contains("axe_query_duration_seconds_bucket{le=\"+Inf\"} "), m);
            assertTrue(m.contains("axe_query_duration_seconds_sum "), m);
            assertTrue(m.contains("axe_audit_pending_cases 0"), m);
            assertTrue(m.contains("axe_audit_blocked_users 0"), m);
        } finally {
            server.stop();
        }
    }

    @Test
    void contadoresDeLoginContamSucessoEFalha() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        try {
            assertEquals(200, post(server.port(), "/signin", "{\"user\":\"root\",\"pass\":\"pw\"}").statusCode());
            assertEquals(401, post(server.port(), "/signin", "{\"user\":\"root\",\"pass\":\"x\"}").statusCode());
            String rpc = post(server.port(), "/rpc",
                "{\"id\":1,\"method\":\"signin\",\"params\":[{\"user\":\"root\",\"pass\":\"errada\"}]}").body();
            assertTrue(rpc.contains("error"), rpc);
            String m = get(server.port(), "/metrics").body();
            assertEquals(1, metric(m, "axe_auth_signin_total"), m);
            assertEquals(2, metric(m, "axe_auth_signin_fail_total"), m);
        } finally {
            server.stop();
        }
    }

    @Test
    void metricasPorBancoSoComOpcaoLigada() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("n", "d");
        ds.execute("CREATE t:1 CONTENT {a: 1}", sessao(), null);
        AxonServer off = AxonServer.startRandomPort(ds, "secret-testing");
        AxonServer on = AxonServer.startRandomPort(ds, "secret-testing");
        try {
            on.metricsPerDatabase(true);
            assertFalse(get(off.port(), "/metrics").body().contains("axe_db_keys{"));
            String m = get(on.port(), "/metrics").body();
            assertTrue(m.contains("axe_db_keys{ns=\"n\",db=\"d\"} "), m);
            assertTrue(metricLabeled(m, "axe_db_keys{ns=\"n\",db=\"d\"}") >= 1, m);
        } finally {
            off.stop();
            on.stop();
        }
    }

    private static double metricLabeled(String body, String name) {
        return metric(body, name);
    }

    private static Session sessao() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    @Test
    void metricsPodeExigirToken() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        try {
            assertEquals(200, get(server.port(), "/metrics").statusCode());
            server.metricsRequireAuth(true);
            assertEquals(401, get(server.port(), "/metrics").statusCode());
            String token = com.axonbase.value.AxonJson.parseDocument(
                post(server.port(), "/signin", "{\"user\":\"root\",\"pass\":\"pw\"}").body())
                .asObject().get("token").asString();
            assertEquals(200, get(server.port(), "/metrics", "Authorization", "Bearer " + token).statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void versionDevolveAVersaoRealDoBuild() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing");
        try {
            String body = get(server.port(), "/version").body();
            String version = com.axonbase.value.AxonJson.parseDocument(body).asString();
            assertTrue(version.matches("\\d+\\.\\d+\\.\\d+.*"), version);
            assertFalse(version.contains("SNAPSHOT") && version.startsWith("0.1.0"), version);
            assertEquals(BuildInfo.version(), version);
            assertEquals(RpcDispatcher.SERVER_VERSION, version);
            String raiz = Files.readString(java.nio.file.Path.of("..", "VERSION")).trim();
            assertEquals(raiz, version);
        } finally {
            server.stop();
        }
    }

    @Test
    void lagEUltimoContatoPorPeerNoCluster() throws Exception {
        Datastore ds = Datastore.memory();
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing");
        var dir = Files.createTempDirectory("m-e0");
        ClusterRuntime rt = new ClusterRuntime(new ClusterConfig("n1", "g",
            new InetSocketAddress("127.0.0.1", 0), java.util.List.of()), dir);
        try {
            server.clusterStatus(rt);
            for (int i = 0; i < 80 && rt.role() != ClusterRole.LEADER; i++) {
                Thread.sleep(60);
            }
            assertTrue(rt.addPeer("n2", new InetSocketAddress("127.0.0.1", 9090)));
            String m = get(server.port(), "/metrics").body();
            assertTrue(m.contains("axe_raft_peer_lag{peer=\"127.0.0.1:9090\"} "), m);
            assertTrue(m.contains("axe_raft_peer_last_contact_seconds{peer=\"127.0.0.1:9090\"} "), m);
        } finally {
            server.stop();
            rt.close();
        }
    }
}
