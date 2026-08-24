package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.LiveBus;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Motor: live queries em tempo real")
class LiveQueryTest {

    private Datastore ns() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        return ds;
    }

    private Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    /** Liga um coletor de notificações à sessão e devolve a lista viva. */
    private List<LiveBus.Notification> collect(Session s) {
        List<LiveBus.Notification> got = new ArrayList<>();
        s.liveListener(got::add);
        return got;
    }

    @Test
    @DisplayName("LIVE SELECT devolve um id e notifica CREATE")
    void liveNotificaCreate() {
        Datastore ds = ns();
        Session s = session();
        List<LiveBus.Notification> got = collect(s);

        AxonValue id = ds.execute("LIVE SELECT * FROM person", s, null);
        assertTrue(id.isString());
        assertFalse(id.asString().isBlank());
        assertEquals(1, ds.liveBus().size());

        ds.execute("CREATE person CONTENT { name: \"Ana\", age: 30 }", s, null);

        assertEquals(1, got.size());
        LiveBus.Notification n = got.get(0);
        assertEquals(id.asString(), n.id());
        assertEquals("CREATE", n.action());
        assertEquals("Ana", n.result().asObject().get("name").asString());
    }

    @Test
    @DisplayName("Notifica UPDATE e DELETE com o registro afetado")
    void liveNotificaUpdateEDelete() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("CREATE person:ana CONTENT { name: \"Ana\", age: 30 }", s, null);
        List<LiveBus.Notification> got = collect(s);
        ds.execute("LIVE SELECT * FROM person", s, null);

        ds.execute("UPDATE person:ana SET age = 31", s, null);
        ds.execute("DELETE person:ana", s, null);

        assertEquals(2, got.size());
        assertEquals("UPDATE", got.get(0).action());
        assertEquals(31, got.get(0).result().asObject().get("age").asLong());
        assertEquals("DELETE", got.get(1).action());
        assertEquals("Ana", got.get(1).result().asObject().get("name").asString());
    }

    @Test
    @DisplayName("WHERE filtra as notificações")
    void liveComWhereFiltra() {
        Datastore ds = ns();
        Session s = session();
        List<LiveBus.Notification> got = collect(s);
        ds.execute("LIVE SELECT * FROM person WHERE age >= 18", s, null);

        ds.execute("CREATE person CONTENT { name: \"Bob\", age: 17 }", s, null);
        assertTrue(got.isEmpty());

        ds.execute("CREATE person CONTENT { name: \"Ana\", age: 30 }", s, null);
        assertEquals(1, got.size());
        assertEquals("Ana", got.get(0).result().asObject().get("name").asString());
    }

    @Test
    @DisplayName("Projeção de campos respeita o SELECT da live query")
    void liveProjetaCampos() {
        Datastore ds = ns();
        Session s = session();
        List<LiveBus.Notification> got = collect(s);
        ds.execute("LIVE SELECT name FROM person", s, null);

        ds.execute("CREATE person CONTENT { name: \"Ana\", age: 30 }", s, null);

        assertEquals(1, got.size());
        AxonValue r = got.get(0).result();
        assertEquals(1, r.asObject().size());
        assertEquals("Ana", r.asObject().get("name").asString());
    }

    @Test
    @DisplayName("LIVE SELECT ... DIFF entrega operações de patch")
    void liveDiff() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("CREATE person:ana CONTENT { name: \"Ana\", age: 30 }", s, null);
        List<LiveBus.Notification> got = collect(s);
        ds.execute("LIVE SELECT * FROM person DIFF", s, null);

        ds.execute("UPDATE person:ana SET age = 31", s, null);

        assertEquals(1, got.size());
        AxonValue ops = got.get(0).result();
        assertTrue(ops.isArray());
        assertEquals(1, ops.asArray().size());
        AxonValue op = ops.asArray().get(0);
        assertEquals("replace", op.asObject().get("op").asString());
        assertEquals("/age", op.asObject().get("path").asString());
        assertEquals(31, op.asObject().get("value").asLong());
    }

    @Test
    @DisplayName("KILL cancela a subscription")
    void killCancela() {
        Datastore ds = ns();
        Session s = session();
        List<LiveBus.Notification> got = collect(s);
        AxonValue id = ds.execute("LIVE SELECT * FROM person", s, null);

        AxonValue killed = ds.execute("KILL \"" + id.asString() + "\"", s, null);
        assertTrue(killed.asBool());
        assertEquals(0, ds.liveBus().size());

        ds.execute("CREATE person CONTENT { name: \"Ana\" }", s, null);
        assertTrue(got.isEmpty());
    }

    @Test
    @DisplayName("Notificações só saem no COMMIT da transação")
    void transacaoRetemNotificacoes() {
        Datastore ds = ns();
        Session s = session();
        List<LiveBus.Notification> got = collect(s);
        ds.execute("LIVE SELECT * FROM person", s, null);

        ds.execute("BEGIN", s, null);
        ds.execute("CREATE person CONTENT { name: \"Ana\" }", s, null);
        assertTrue(got.isEmpty(), "nada deve sair antes do COMMIT");

        ds.execute("COMMIT", s, null);
        assertEquals(1, got.size());
        assertEquals("CREATE", got.get(0).action());
    }

    @Test
    @DisplayName("CANCEL descarta as notificações pendentes")
    void cancelDescartaNotificacoes() {
        Datastore ds = ns();
        Session s = session();
        List<LiveBus.Notification> got = collect(s);
        ds.execute("LIVE SELECT * FROM person", s, null);

        ds.execute("BEGIN", s, null);
        ds.execute("CREATE person CONTENT { name: \"Ana\" }", s, null);
        ds.execute("CANCEL", s, null);

        assertTrue(got.isEmpty());
    }

    @Test
    @DisplayName("Uma sessão observa as mudanças feitas por outra")
    void notificaEntreSessoes() {
        Datastore ds = ns();
        Session observer = session();
        Session writer = session();
        List<LiveBus.Notification> got = collect(observer);
        ds.execute("LIVE SELECT * FROM person", observer, null);

        ds.execute("CREATE person CONTENT { name: \"Ana\" }", writer, null);

        assertEquals(1, got.size());
        assertEquals("CREATE", got.get(0).action());
    }

    @Test
    @DisplayName("killAll limpa as subscriptions de uma sessão desligada")
    void killAllLimpaSessao() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("LIVE SELECT * FROM person", s, null);
        ds.execute("LIVE SELECT * FROM company", s, null);
        assertEquals(2, ds.liveBus().size());

        ds.liveBus().killAll(s);
        assertEquals(0, ds.liveBus().size());
        assertTrue(s.liveIds().isEmpty());
    }

    @Test
    @DisplayName("DEFINE EVENT dispara as sentenças THEN na mudança")
    void defineEventDispara() {
        Datastore ds = ns();
        Session s = session();
        ds.execute("DEFINE EVENT log ON TABLE person WHEN $event = \"CREATE\" "
            + "THEN (CREATE audit CONTENT { acao: \"novo\" })", s, null);

        ds.execute("CREATE person CONTENT { name: \"Ana\" }", s, null);

        AxonValue audits = ds.execute("SELECT * FROM audit", s, null);
        assertEquals(1, audits.asArray().size());
        assertEquals("novo", audits.asArray().get(0).asObject().get("acao").asString());
    }
}