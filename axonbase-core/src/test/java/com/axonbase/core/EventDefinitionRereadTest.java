package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Todo corpo de evento aceito pelo analisador precisa ser gravado e relido pelo plano de controle. */
@DisplayName("Eventos: toda definição legítima grava e volta no reinício")
class EventDefinitionRereadTest {

    private static Session session() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "USE DB outro",
        "USE NS outro",
        "USE n d",
        "CREATE log SET v = $value; UPDATE log SET n = 1 WHERE v = 1; DELETE log WHERE n = 2",
        "IF $value.v = 1 THEN { CREATE log CONTENT {a: 1} } ELSE { CREATE log CONTENT {a: 2} } END",
        "CREATE log SET d = (SELECT * FROM outro.segredo WHERE (v = 1 OR v = 2) AND ok = true LIMIT 1)",
        "INSERT INTO log SELECT * FROM pedido",
        "ALTER TABLE log ADD COLUMN a int, DROP COLUMN b",
        "ALTER ANALYZER simples",
        "CREATE `data` SET a = 1",
        "SELECT VALUE 1",
        "SELECT `value` FROM log FETCH `a.b`",
        "LET $x = {(log:1), 2}",
        "CREATE log CONTENT {`Type`: 1}",
        "DEFINE TABLE copia SCHEMALESS PERMISSIONS FOR select WHERE (a = 1 OR b = 2) AND c = 3",
        "CREATE DATA RULE r APPLY (a = 1 OR b = 2) AND c = 3",
        "LIVE SELECT * FROM log DIFF",
        "CALL pacote::baixa(1, $value)"
    })
    void corpoGravaERele(String body) {
        MemoryBackend backend = new MemoryBackend();
        Datastore ds = new Datastore(backend);
        ds.execute("DEFINE EVENT e ON TABLE pedido WHEN true THEN (" + body + ")", session(), null);

        Datastore reopened = new Datastore(backend);

        assertTrue(reopened.findDatabase("n", "d").catalog().table("pedido").events().containsKey("e"));
    }
}
