package com.axonbase.core;

import com.axonbase.common.AxonError;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Conversões: entrada inválida é erro, nunca um valor inventado")
class ConversionErrorTest {

    private final Datastore ds = Datastore.memory();
    private final Session session = scope();

    private static Session scope() {
        Session s = Session.create();
        s.namespace("t");
        s.database("d");
        return s;
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, session, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "type::datetime('nada')",
        "type::datetime('2026-11-20T10:00:00')",
        "type::datetime()",
        "type::int('3.7')",
        "type::int('abc')",
        "type::float('abc')",
        "type::float('NaN')",
        "type::number('')",
        "type::decimal('x')",
        "type::bool('talvez')",
        "type::record('t', NONE)",
        "time::year('nada')",
        "time::from::unix('abc')",
        "time::from::millis('abc')",
        "<int>'abc'",
        "<int>true",
        "<decimal>'x'",
        "<bool>'talvez'"
    })
    void entradaInvalidaDaErroClaro(String expression) {
        assertThrows(AxonError.class, () -> run("RETURN " + expression));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "type::datetime(NONE)", "type::int(NONE)", "type::float(null)", "type::decimal(NONE)",
        "type::bool(null)", "time::year(null)", "time::floor(NONE, '10m')", "time::from::unix(NONE)",
        "<string>null", "<int>NONE"
    })
    void valorAusenteContinuaAusente(String expression) {
        AxonValue value = run("RETURN " + expression);

        assertTrue(value.isNull() || value.isNone(), value.toString());
    }

    @Test
    void textoSoComDataViraMeiaNoiteUtc() {
        assertEquals("2026-11-20T00:00:00Z", run("RETURN type::datetime('2026-11-20')").asInstant().toString());
    }

    @Test
    void entradasValidasContinuamConvertendo() {
        assertEquals("2026-11-20T13:00:00Z",
            run("RETURN type::datetime('2026-11-20T10:00:00-03:00')").asInstant().toString());
        assertEquals(42, run("RETURN type::int(' 42 ')").asLong());
        assertEquals(3, run("RETURN type::int(3.7)").asLong());
        assertFalse(run("RETURN type::bool('false')").asBool());
        assertFalse(run("RETURN <bool>'false'").asBool());
        assertEquals("abc", run("RETURN <string>'abc'").asString());
        assertEquals(0, new BigDecimal("0.3").compareTo(
            run("RETURN <decimal>'0.1' + <decimal>'0.2'").asDecimal()));
    }

    @Test
    void campoInteiroRecusaFracaoEmVezDeTruncar() {
        run("DEFINE FIELD n ON TABLE t TYPE int");

        assertThrows(AxonError.class, () -> run("CREATE t:1 SET n = 3.7"));
        run("CREATE t:2 SET n = 4.0");

        AxonValue row = run("SELECT * FROM t:2");
        assertEquals(4, (row.isArray() ? row.asArray().get(0) : row).asObject().get("n").asLong());
    }

    @Test
    void dataGravadaComoTextoNaoLevaMarcadorInterno() {
        run("DEFINE FIELD quando ON TABLE t TYPE string");
        run("CREATE t:1 SET quando = type::datetime('2026-01-01T00:00:00Z')");

        AxonValue row = run("SELECT * FROM t:1");
        assertEquals("2026-01-01T00:00:00Z",
            (row.isArray() ? row.asArray().get(0) : row).asObject().get("quando").asString());
        assertEquals("2026-01-01T00:00:00Z",
            run("RETURN type::string(type::datetime('2026-01-01T00:00:00Z'))").asString());
    }
}
