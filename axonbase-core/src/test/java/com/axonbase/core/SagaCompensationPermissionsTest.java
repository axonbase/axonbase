package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("SAGA: o CANCEL repõe o estado anterior mesmo quando PERMISSIONS barrariam uma escrita nova")
class SagaCompensationPermissionsTest {

    private final Datastore ds = Datastore.memory();
    private final Session session = local();

    private static Session local() {
        Session s = Session.create();
        s.namespace("t");
        s.database("d");
        return s;
    }

    /** Como a credencial de uma ligação num participante: escopo de banco e papel de operador. */
    private static Session linkCredential(String... dataRules) {
        Session s = local();
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str("ligacao"));
        claims.put("scope", AxonValue.str("DATABASE"));
        claims.put("ns", AxonValue.str("t"));
        claims.put("db", AxonValue.str("d"));
        claims.put("roles", AxonValue.str("operator"));
        if (dataRules.length > 0) {
            claims.put("data_rules", AxonValue.str(String.join(",", dataRules)));
        }
        s.auth(AxonValue.object(claims));
        return s;
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, session, null);
    }

    private AxonValue as(Session who, String sql) {
        return ds.execute(sql, who, null);
    }

    private void begin(Session who, String correlation) {
        as(who, "BEGIN SAGA z WITH CORRELATION '" + correlation + "'");
        as(who, "LET $saga_corr = '" + correlation + "'");
    }

    private String cancel(Session who, String correlation) {
        return as(who, "CANCEL SAGA z WITH CORRELATION '" + correlation + "'").asObject().get("status").asString();
    }

    private int count(String table) {
        return run("SELECT * FROM " + table).asArray().size();
    }

    private String estado(String table) {
        return run("SELECT * FROM " + table).asArray().get(0).asObject().get("estado").asString();
    }

    @Test
    void atualizacaoQueOEstadoNovoBarrariaEhDesfeita() {
        run("CREATE doc:1 SET estado = \"rascunho\"");
        run("DEFINE TABLE doc PERMISSIONS FOR update WHERE estado = \"rascunho\"");
        begin(session, "p1");
        run("UPDATE doc:1 SET estado = \"publicado\"");

        assertEquals("FAILED", cancel(session, "p1"));

        assertEquals("rascunho", estado("doc"));
    }

    @Test
    void criacaoSemPermissaoDeApagarEhDesfeita() {
        run("DEFINE TABLE nota PERMISSIONS FOR delete WHERE false");
        begin(session, "p2");
        run("CREATE nota:1 SET estado = \"nova\"");

        assertEquals("FAILED", cancel(session, "p2"));

        assertEquals(0, count("nota"));
    }

    @Test
    void remocaoSemPermissaoDeCriarEhDesfeita() {
        run("CREATE item:1 SET estado = \"antigo\"");
        run("DEFINE TABLE item PERMISSIONS FOR create WHERE false");
        begin(session, "p3");
        run("DELETE item:1");

        assertEquals("FAILED", cancel(session, "p3"));

        assertEquals("antigo", estado("item"));
    }

    @Test
    void credencialDaLigacaoDesfazApesarDePermissionsEDataRules() {
        run("CREATE conta:1 SET estado = \"aberta\"");
        run("DEFINE TABLE conta PERMISSIONS FOR update WHERE estado = \"aberta\"");
        run("CREATE DATA RULE abertas APPLY estado != \"fechada\"");
        Session link = linkCredential("abertas");
        begin(link, "p4");
        as(link, "UPDATE conta:1 SET estado = \"fechada\"");

        assertEquals("FAILED", cancel(link, "p4"));

        assertEquals("aberta", estado("conta"));
    }

    @Test
    void eventoDisparadoPelaCompensacaoNaoHerdaAMarca() {
        run("DEFINE TABLE trilha PERMISSIONS FOR create WHERE false");
        run("CREATE pedido:1 SET estado = \"novo\"");
        begin(session, "p5");
        run("UPDATE pedido:1 SET estado = \"pago\"");
        run("DEFINE EVENT registra ON TABLE pedido WHEN true THEN (CREATE trilha CONTENT {ok: true})");

        assertThrows(RuntimeException.class, () -> cancel(session, "p5"));

        assertEquals(0, count("trilha"));
    }

    @Test
    void remocaoQueOParticipanteRemotoNaoFezNaoContaComoCompensada() throws Exception {
        HttpServer remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        remote.createContext("/sql", exchange -> {
            exchange.getRequestBody().readAllBytes();
            // o registro continua lá, como num DELETE que as PERMISSIONS do participante pulam
            byte[] body = "[{\"id\":\"x:1\"}]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        remote.start();
        try {
            run("DEFINE DATABASE LINK \"p\" CONNECT BY \"http://127.0.0.1:" + remote.getAddress().getPort()
                + "\" WITH ns = \"t\" db = \"p\" user = \"\" password = \"\"");
            begin(session, "p6");
            run("CREATE p.x:1 CONTENT {}");

            assertThrows(RuntimeException.class, () -> cancel(session, "p6"));
        } finally {
            remote.stop(0);
        }
    }
}
