package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** O que vai por um DATABASE LINK roda com a credencial dele: nada aninhado nem com efeito pode seguir junto. */
@DisplayName("DATABASE LINK: sentença aninhada e função com efeito não são encaminhadas")
class DatabaseLinkNestedStatementTest {

    private static final String DEFINE_USER = "DEFINE USER evil ON ROOT PASSWORD 'Test-pw-1' ROLES OWNER";

    private final List<String> received = new CopyOnWriteArrayList<>();
    private final Datastore ds = Datastore.memory();
    private HttpServer server;

    @BeforeEach
    void participante() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sql", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        ds.execute("DEFINE DATABASE LINK \"remoto\" CONNECT BY \"http://127.0.0.1:" + server.getAddress().getPort()
            + "\" WITH ns = \"n\" db = \"d\"", local(), null);
    }

    @AfterEach
    void encerra() {
        server.stop(0);
    }

    private static Session local() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    private static Session user(String role) {
        Session s = local();
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str(role));
        claims.put("scope", AxonValue.str("DATABASE"));
        claims.put("ns", AxonValue.str("n"));
        claims.put("db", AxonValue.str("d"));
        claims.put("roles", AxonValue.str(role));
        s.auth(AxonValue.object(claims));
        return s;
    }

    /** Os comandos que não foram recusados ou que fizeram alguma coisa chegar ao participante. */
    private List<String> forwardedOrAccepted(Session who, List<String> commands) {
        List<String> leaked = new ArrayList<>();
        for (String sql : commands) {
            received.clear();
            boolean refused = false;
            try {
                ds.execute(sql, who, null);
            } catch (RuntimeException e) {
                refused = true;
            }
            if (!refused || !received.isEmpty()) {
                leaked.add(sql + " => " + received);
            }
        }
        return leaked;
    }

    @Test
    void leituraDeViewerNaoLevaSentencaNemFuncaoComEfeito() {
        List<String> commands = List.of(
            "SELECT * FROM remoto.t:1 WHERE { " + DEFINE_USER + "; RETURN true }",
            "SELECT { DELETE users; RETURN true } AS x FROM remoto.t:1",
            "SELECT (SELECT * FROM users; DELETE users) AS x FROM remoto.t:1",
            "SELECT * FROM remoto.t:1 WHERE a = (SELECT * FROM users)",
            "SELECT * FROM remoto.t:1 WHERE tags[WHERE { DELETE users; RETURN true }] != NONE",
            "SELECT [{a: { DELETE users }}] AS x FROM remoto.t:1",
            "SELECT string::len({ DELETE users; RETURN 'a' }) AS x FROM remoto.t:1",
            "SELECT kv::set('k', 'v') AS x FROM remoto.t:1",
            "SELECT fn::apaga() AS x FROM remoto.t:1",
            "SELECT pacote::apaga() AS x FROM remoto.t:1");

        assertEquals(List.of(), forwardedOrAccepted(user("viewer"), commands));
        assertEquals(List.of(), forwardedOrAccepted(user("editor"), commands));
    }

    @Test
    void escritaDeEditorNaoLevaSentencaNemFuncaoComEfeito() {
        List<String> commands = List.of(
            "UPDATE remoto.t:1 SET a = { DELETE users; RETURN true }",
            "CREATE remoto.t:1 SET a = { " + DEFINE_USER + " }",
            "UPSERT remoto.t:1 CONTENT {a: { DELETE users }}",
            "DELETE remoto.t:1 WHERE { DELETE users; RETURN true }",
            "RELATE remoto.p:1->conhece->remoto.p:2 SET a = { DELETE users }",
            "UPDATE remoto.t:1 SET a = (SELECT * FROM users)",
            "UPDATE remoto.t:1 SET a = kv::set('k', 'v')");

        assertEquals(List.of(), forwardedOrAccepted(user("editor"), commands));
        assertEquals(List.of(), forwardedOrAccepted(user("viewer"), commands));
    }

    @Test
    @DisplayName("na SAGA a escrita recusada não envia nem a leitura prévia, e não vira passo")
    void escritaRecusadaNaSagaNaoDeixaPasso() {
        ds.execute("CREATE SAGA s WITH DATABASES 'remoto'", local(), null);
        ds.execute("BEGIN SAGA s WITH CORRELATION 'c1'", local(), null);
        Session who = local();
        ds.execute("LET $saga_corr = 'c1'", who, null);

        assertEquals(List.of(), forwardedOrAccepted(who, List.of(
            "UPDATE remoto.t:1 SET a = { DELETE users; RETURN true }",
            "DELETE remoto.t:1 WHERE { DELETE users; RETURN true }")));

        AxonValue shown = ds.execute("SHOW SAGA TRANSACTION s 'c1'", local(), null);
        assertEquals(0, shown.asObject().get("steps").asArray().size());
    }

    @Test
    void comandoSimplesContinuaSendoEncaminhado() {
        for (String sql : List.of(
            "SELECT * FROM remoto.t:1 WHERE string::lowercase(nome) = 'ana' AND idade > 18",
            "SELECT string::uppercase(nome) AS n FROM remoto.t:1",
            "SELECT ->conhece->p AS amigos FROM remoto.t:1",
            "SELECT * FROM remoto.person",
            "CREATE remoto.t:2 CONTENT {nome: 'x', tags: ['a'], vazio: {}}",
            "UPDATE remoto.t:1 SET quando = time::now(), total = math::abs(-1)",
            "DELETE remoto.t:1 WHERE ativo = false",
            "RELATE remoto.p:1->conhece->remoto.p:2 SET desde = 2020")) {
            received.clear();
            ds.execute(sql, user("editor"), null);
            assertEquals(1, received.size(), sql);
        }
    }
}
