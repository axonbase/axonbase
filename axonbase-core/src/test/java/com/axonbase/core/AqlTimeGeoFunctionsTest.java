package com.axonbase.core;

import com.axonbase.common.AxonError;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fuso horário nas funções de tempo, time::clock, time::floor e geo::cell. */
class AqlTimeGeoFunctionsTest {

    private static final String SAO_PAULO = "'America/Sao_Paulo'";
    // 18:30 em Brasília (UTC-3) e 21:30 em UTC
    private static final String T = "<datetime>'2026-11-20T21:30:00Z'";

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

    // ------------------------------------------------------------------ fuso

    @Test
    void semFusoOsExtratoresContinuamEmUtc() {
        assertEquals(21, run("RETURN time::hour(" + T + ")").asLong());
        assertEquals(30, run("RETURN time::minute(" + T + ")").asLong());
        assertEquals(20, run("RETURN time::day(" + T + ")").asLong());
    }

    @Test
    void comFusoOsExtratoresUsamOHorarioLocal() {
        assertEquals(18, run("RETURN time::hour(" + T + ", " + SAO_PAULO + ")").asLong());
        assertEquals(30, run("RETURN time::minute(" + T + ", " + SAO_PAULO + ")").asLong());
        assertEquals(5, run("RETURN time::wday(" + T + ", " + SAO_PAULO + ")").asLong());
    }

    @Test
    void fusoPodeMudarODiaDoMes() {
        // 01:30 UTC de 21/11 ainda é 22:30 de 20/11 em Brasília
        String t = "<datetime>'2026-11-21T01:30:00Z'";
        assertEquals(21, run("RETURN time::day(" + t + ")").asLong());
        assertEquals(20, run("RETURN time::day(" + t + ", " + SAO_PAULO + ")").asLong());
    }

    @Test
    void fusoInvalidoDaErroClaro() {
        AxonError e = assertThrows(AxonError.class, () -> run("RETURN time::hour(" + T + ", 'Marte/Olympus')"));
        assertTrue(e.getMessage().contains("Marte/Olympus"), e.getMessage());
    }

    // ----------------------------------------------------------- time::clock

    @Test
    void clockDevolveHoraEMinutoComoTexto() {
        assertEquals("21:30", run("RETURN time::clock(" + T + ")").asString());
        assertEquals("18:30", run("RETURN time::clock(" + T + ", " + SAO_PAULO + ")").asString());
        assertEquals("09:05", run("RETURN time::clock(<datetime>'2026-11-20T12:05:59Z', " + SAO_PAULO + ")").asString());
    }

    @Test
    void clockComBetweenFiltraAJanelaDoDiaNoFusoLocal() {
        String[] utc = {"20:59", "21:00", "21:45", "22:30", "22:31"};
        for (int i = 0; i < utc.length; i++) {
            run("CREATE ev:" + (i + 1) + " CONTENT {n: " + (i + 1) + ", quando: <datetime>'2026-11-20T" + utc[i] + ":00Z'}");
        }
        AxonValue rows = run("SELECT n FROM ev WHERE time::clock(quando, " + SAO_PAULO + ") BETWEEN '18:00' AND '19:30'");
        assertEquals(3, rows.asArray().size());
    }

    // ----------------------------------------------------------- time::floor

    @Test
    void floorArredondaParaBaixoNaJanela() {
        String t = "<datetime>'2026-11-20T21:37:12Z'";
        assertEquals(Instant.parse("2026-11-20T21:30:00Z"), run("RETURN time::floor(" + t + ", '10m')").asInstant());
        assertEquals(Instant.parse("2026-11-20T21:00:00Z"), run("RETURN time::floor(" + t + ", '1h')").asInstant());
        assertEquals(Instant.parse("2026-11-20T21:37:00Z"), run("RETURN time::floor(" + t + ", '1m')").asInstant());
        assertEquals(Instant.parse("2026-11-20T21:37:10Z"), run("RETURN time::floor(" + t + ", '10s')").asInstant());
    }

    @Test
    void floorDeUmDiaAlinhaNaMeiaNoiteDoFuso() {
        String t = "<datetime>'2026-11-20T21:37:12Z'";
        assertEquals(Instant.parse("2026-11-20T00:00:00Z"), run("RETURN time::floor(" + t + ", '1d')").asInstant());
        // meia-noite de Brasília é 03:00 UTC
        assertEquals(Instant.parse("2026-11-20T03:00:00Z"),
            run("RETURN time::floor(" + t + ", '1d', " + SAO_PAULO + ")").asInstant());
    }

    @Test
    void floorAgrupaEventosDaMesmaJanela() {
        run("CREATE ev:1 CONTENT {quando: <datetime>'2026-11-20T21:31:00Z'}");
        run("CREATE ev:2 CONTENT {quando: <datetime>'2026-11-20T21:39:59Z'}");
        run("CREATE ev:3 CONTENT {quando: <datetime>'2026-11-20T21:40:00Z'}");
        AxonValue buckets = run("SELECT VALUE time::floor(quando, '10m') FROM ev");
        assertEquals(buckets.asArray().get(0).asInstant(), buckets.asArray().get(1).asInstant());
        assertNotEquals(buckets.asArray().get(1).asInstant(), buckets.asArray().get(2).asInstant());
    }

    @Test
    void floorComDuracaoInvalidaDaErroClaro() {
        assertThrows(AxonError.class, () -> run("RETURN time::floor(" + T + ", 'dez minutos')"));
        assertThrows(AxonError.class, () -> run("RETURN time::floor(" + T + ", '0m')"));
    }

    // -------------------------------------------------------------- geo::cell

    private static String point(double lon, double lat) {
        return "{type: 'Point', coordinates: [" + lon + ", " + lat + "]}";
    }

    @Test
    void cellDevolveUmPoligonoFechadoQueContemOPonto() {
        AxonValue cell = run("RETURN geo::cell(" + point(-46.6333, -23.5505) + ", 500)");
        assertEquals("Polygon", cell.asObject().get("type").asString());
        AxonValue ring = cell.asObject().get("coordinates").asArray().get(0);
        assertEquals(5, ring.asArray().size());
        assertEquals(ring.asArray().get(0).asArray().get(0).asDouble(), ring.asArray().get(4).asArray().get(0).asDouble(), 0);
        assertTrue(run("RETURN geo::contains(geo::cell(" + point(-46.6333, -23.5505) + ", 500), "
            + point(-46.6333, -23.5505) + ")").asBool());
    }

    @Test
    void cellEDeterministicaEAgrupaPontosProximos() {
        AxonValue a = run("RETURN geo::cell(" + point(-46.63330, -23.55050) + ", 500)");
        AxonValue b = run("RETURN geo::cell(" + point(-46.63331, -23.55051) + ", 500)");
        AxonValue far = run("RETURN geo::cell(" + point(-46.70, -23.60) + ", 500)");
        assertEquals(a, b);
        assertNotEquals(a, far);
    }

    @Test
    void cellTemLadoAproximadoEmMetros() {
        AxonValue ring = run("RETURN geo::cell(" + point(-46.6333, -23.5505) + ", 500)")
            .asObject().get("coordinates").asArray().get(0);
        double west = ring.asArray().get(0).asArray().get(0).asDouble();
        double east = ring.asArray().get(1).asArray().get(0).asDouble();
        double south = ring.asArray().get(0).asArray().get(1).asDouble();
        double north = ring.asArray().get(2).asArray().get(1).asDouble();
        double heightMeters = (north - south) * 111_320d;
        double widthMeters = (east - west) * 111_320d * Math.cos(Math.toRadians(-23.5505));
        assertEquals(500, heightMeters, 5);
        assertEquals(500, widthMeters, 10);
    }

    @Test
    void cellComEntradaInvalidaDevolveNulo() {
        assertTrue(run("RETURN geo::cell('x', 500)").isNull());
        assertTrue(run("RETURN geo::cell(" + point(-46.6, -23.5) + ", 0)").isNull());
    }
}
