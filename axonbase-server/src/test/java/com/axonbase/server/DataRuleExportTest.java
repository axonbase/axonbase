package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.server.auth.Jwt;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** A exportação não aplica filtro nem máscara, então quem tem Data Rule não exporta. */
class DataRuleExportTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void usuarioComDataRuleNaoExporta() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        ds.execute("CREATE o:1 CONTENT {rep: 2, cpf: '98765'}", s, null);
        ds.execute("CREATE DATA RULE meus APPLY rep = 1", s, null);
        ds.authCatalog().defineUser("v", AuthCatalog.Scope.DATABASE, "app", "main", "Test-pw-1",
            List.of("editor"), List.of("meus"));
        AxonServer server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        try {
            String token = Jwt.sign("secret-testing", Map.of("id", "v", "jti", "j-v", "typ", "access",
                "scope", "DATABASE", "ns", "app", "db", "main", "roles", "editor", "data_rules", "meus"),
                3600_000L).value();
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.port() + "/export"))
                .header("Authorization", "Bearer " + token)
                .header("Axon-Ns", "app").header("Axon-Db", "main")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, r.statusCode(), r.body());
            assertFalse(r.body().contains("98765"), r.body());
        } finally {
            server.stop();
        }
    }
}
