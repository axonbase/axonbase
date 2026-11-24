package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Divisão e média com resultado decimal não exato devem arredondar (escala 10,
 * HALF_UP, como math::mean) em vez de falhar com "Non-terminating decimal expansion".
 */
class DecimalDivisionTest {

    private Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    private Datastore ds() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        return ds;
    }

    private BigDecimal scalar(AxonValue v) {
        AxonValue x = v;
        while (x.isArray() && !x.asArray().isEmpty()) {
            x = x.asArray().get(0);
        }
        if (x.isObject()) {
            x = x.asObject().values().iterator().next();
        }
        assertTrue(x.isNumber(), "esperado número, got: " + v);
        return x.asDecimal();
    }

    private BigDecimal field(AxonValue v, String name) {
        AxonValue row = v.isArray() ? v.asArray().get(0) : v;
        return row.asObject().get(name).asDecimal();
    }

    private void assertNum(String esperado, BigDecimal real) {
        assertEquals(0, new BigDecimal(esperado).compareTo(real), "esperado " + esperado + ", got " + real);
    }

    @Test
    void divisaoInteiraNaoExataArredonda() {
        assertNum("3.3333333333", scalar(ds().execute("RETURN 10 / 3", session(), null)));
    }

    @Test
    void divisaoComDecimalNaoExataArredonda() {
        assertNum("0.3333333333", scalar(ds().execute("RETURN 1 / 3.0", session(), null)));
    }

    @Test
    void divisaoRepeteArredondaParaCima() {
        assertNum("0.6666666667", scalar(ds().execute("RETURN 2 / 3", session(), null)));
    }

    @Test
    void divisaoExataMantemValor() {
        assertNum("2.5", scalar(ds().execute("RETURN 10 / 4", session(), null)));
        assertNum("5", scalar(ds().execute("RETURN 10 / 2", session(), null)));
    }

    @Test
    void divisaoPorZeroLancaErro() {
        Datastore ds = ds();
        assertThrows(com.axonbase.common.AxonError.class, () -> ds.execute("RETURN 1 / 0", session(), null));
        assertThrows(com.axonbase.common.AxonError.class, () -> ds.execute("RETURN 1.5 / 0.0", session(), null));
    }

    @Test
    void avgSobreDecimaisNaoExatosArredonda() {
        Datastore ds = ds();
        ds.execute("CREATE p:1 CONTENT { k: 1, v: 1.5 }", session(), null);
        ds.execute("CREATE p:2 CONTENT { k: 1, v: 2.5 }", session(), null);
        ds.execute("CREATE p:3 CONTENT { k: 1, v: 2.5 }", session(), null);
        // (1.5 + 2.5 + 2.5) / 3 = 2.1666666667
        assertNum("2.1666666667", field(ds.execute("SELECT avg(v) AS m FROM p GROUP BY k", session(), null), "m"));
    }

    @Test
    void avgExatoMantemValor() {
        Datastore ds = ds();
        ds.execute("CREATE p:1 CONTENT { k: 1, v: 1 }", session(), null);
        ds.execute("CREATE p:2 CONTENT { k: 1, v: 2 }", session(), null);
        assertNum("1.5", field(ds.execute("SELECT avg(v) AS m FROM p GROUP BY k", session(), null), "m"));
    }
}
