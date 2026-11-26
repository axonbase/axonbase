package com.axonbase.server;

import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterStatusProvider;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.value.AxonJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Backup físico em cluster: criado em seguidor ou learner, nunca restaurado")
class PhysicalBackupClusterApiTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path tmp;

    @Test
    @DisplayName("seguidor e learner criam, listam e entregam o backup; líder e candidato não criam; restore recusa")
    void onlyReplicasCreateAndNobodyRestores() throws Exception {
        AtomicReference<ClusterRole> role = new AtomicReference<>(ClusterRole.FOLLOWER);
        try (RocksDbBackend backend = new RocksDbBackend(tmp.resolve("db").toString(), RocksDbBackend.SyncMode.OFF)) {
            Datastore ds = new Datastore(backend);
            ds.createDatabase("app", "main");
            int free;
            try (ServerSocket socket = new ServerSocket(0)) {
                free = socket.getLocalPort();
            }
            AxonServer server = AxonServer.start(ds, "secret-testing", ServerConfig.load(Path.of("nao-existe.conf"),
                Map.of("AXON_PORT", String.valueOf(free), "AXON_ROOT_USER", "root", "AXON_ROOT_PASSWORD", "pw")));
            try {
                server.clusterStatus(() -> new ClusterStatusProvider.Status("n2", "g", "n1", "10.0.0.1:9000",
                    role.get(), 3, 10, 1, 3, 2));
                String base = "http://127.0.0.1:" + server.port();
                String token = AxonJson.parseDocument(HTTP.send(HttpRequest.newBuilder(URI.create(base + "/signin"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"root\",\"pass\":\"pw\"}")).build(),
                    HttpResponse.BodyHandlers.ofString()).body()).asObject().get("token").asString();
                String api = base + "/admin/v1/backups/physical";

                assertEquals(200, send("POST", api, token).statusCode());
                role.set(ClusterRole.LEARNER);
                assertEquals(200, send("POST", api, token).statusCode());
                for (ClusterRole refused : new ClusterRole[] {ClusterRole.LEADER, ClusterRole.CANDIDATE}) {
                    role.set(refused);
                    HttpResponse<String> response = send("POST", api, token);
                    assertEquals(409, response.statusCode(), refused + " " + response.body());
                }
                for (ClusterRole any : ClusterRole.values()) {
                    role.set(any);
                    assertEquals(200, send("GET", api, token).statusCode(), any.name());
                    assertEquals(200, send("GET", api + "/1/files", token).statusCode(), any.name());
                    assertEquals(200, send("POST", api + "/1/verify", token).statusCode(), any.name());
                    HttpResponse<String> restore = send("POST", api + "/1/restore?confirm=true", token);
                    assertEquals(409, restore.statusCode(), any + " " + restore.body());
                    assertEquals(409, send("PUT", api + "/files?source=outro&path=meta/1", token).statusCode());
                    assertEquals(409, send("DELETE", api + "/imports/outro", token).statusCode());
                }
                role.set(ClusterRole.FOLLOWER);
                assertEquals(200, send("DELETE", api + "?keep=1", token).statusCode());
            } finally {
                server.stop();
            }
        }
    }

    private static HttpResponse<String> send(String method, String url, String token) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
            .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
}
