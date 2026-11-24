package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Sintaxe de consulta: BETWEEN, literais com campos da linha, FROM $variavel. */
class AqlSyntaxTest {

    private Datastore ds;
    private Session session;

    @BeforeEach
    void setUp() {
        ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        session = Session.create();
        session.namespace("test");
        session.database("dev");
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, session, null);
    }

    private List<Long> ids(AxonValue rows) {
        return rows.asArray().stream().map(r -> r.asObject().get("n").asLong()).sorted().collect(Collectors.toList());
    }

    private void seedNumbers() {
        for (int n = 1; n <= 6; n++) {
            run("CREATE num:" + n + " CONTENT {n: " + n + ", tag: 'x'}");
        }
    }

    // ---------------------------------------------------------------- BETWEEN

    @Test
    void betweenIncluiOsDoisLimites() {
        seedNumbers();
        assertEquals(List.of(2L, 3L, 4L), ids(run("SELECT n FROM num WHERE n BETWEEN 2 AND 4")));
    }

    @Test
    void notBetweenInverte() {
        seedNumbers();
        assertEquals(List.of(1L, 5L, 6L), ids(run("SELECT n FROM num WHERE n NOT BETWEEN 2 AND 4")));
    }

    @Test
    void betweenConvivecomAndOrEExpressoesNosLimites() {
        seedNumbers();
        assertEquals(List.of(3L, 4L), ids(run("SELECT n FROM num WHERE n BETWEEN 1 + 2 AND 4 AND tag = 'x'")));
        assertEquals(List.of(1L, 6L), ids(run("SELECT n FROM num WHERE n BETWEEN 7 AND 9 OR n = 1 OR n = 6")));
    }

    @Test
    void betweenComparaTextoNaOrdemAlfabetica() {
        run("CREATE t:a CONTENT {n: 1, hora: '17:59'}");
        run("CREATE t:b CONTENT {n: 2, hora: '18:00'}");
        run("CREATE t:c CONTENT {n: 3, hora: '19:30'}");
        run("CREATE t:d CONTENT {n: 4, hora: '19:31'}");
        assertEquals(List.of(2L, 3L), ids(run("SELECT n FROM t WHERE hora BETWEEN '18:00' AND '19:30'")));
    }

    @Test
    void betweenNaoReservaOsNomesBetweenEDeCampo() {
        run("CREATE t:1 CONTENT {between: 5, n: 1}");
        assertEquals(5, run("SELECT VALUE between FROM t").asArray().get(0).asLong());
    }

    // ------------------------------------------------------- literais por linha

    @Test
    void objetoELiteralDeArrayUsamOsCamposDaLinha() {
        run("CREATE p:1 CONTENT {lon: -46.6, lat: -23.5, nome: 'Sé'}");
        AxonValue row = run("SELECT {type: 'Point', coordinates: [lon, lat]} AS pos, nome FROM p").asArray().get(0);
        AxonValue pos = row.asObject().get("pos");
        assertEquals("Point", pos.asObject().get("type").asString());
        assertEquals(-46.6, pos.asObject().get("coordinates").asArray().get(0).asDouble(), 1e-9);
        assertEquals(-23.5, pos.asObject().get("coordinates").asArray().get(1).asDouble(), 1e-9);
    }

    @Test
    void literaisSemLinhaContinuamComoAntes() {
        assertEquals(2, run("RETURN [1, 2]").asArray().size());
        assertEquals("a", run("RETURN {k: 'a'}").asObject().get("k").asString());
    }

    // --------------------------------------------------------- FROM $variavel

    @Test
    void fromVariavelLeOArrayGuardadoPorLet() {
        seedNumbers();
        AxonValue rows = run("LET $r = (SELECT n FROM num WHERE n > 2); SELECT n FROM $r WHERE n < 6 ORDER BY n DESC");
        assertEquals(List.of(3L, 4L, 5L), ids(rows));
        assertEquals(5, rows.asArray().get(0).asObject().get("n").asLong());
    }

    @Test
    void fromVariavelVaziaOuInexistenteDevolveVazio() {
        assertEquals(0, run("LET $r = []; SELECT * FROM $r").asArray().size());
    }
}
