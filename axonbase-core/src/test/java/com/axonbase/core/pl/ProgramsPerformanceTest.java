package com.axonbase.core.pl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import org.junit.jupiter.api.Test;

/** Custo de chamar função de usuário por linha e vazão de procedures. */
class ProgramsPerformanceTest {

    private static final int ROWS = 100_000;

    private final Datastore ds = Datastore.memory();
    private final Session s = ProgramsTest.session(ds);

    private double bestMillis(String sql, int rounds) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < rounds; i++) {
            long t0 = System.nanoTime();
            ds.execute(sql, s, null);
            best = Math.min(best, (System.nanoTime() - t0) / 1e6);
        }
        return best;
    }

    @Test
    void funcaoPorLinhaCustaPoucosMicrossegundos() {
        for (int b = 0; b < ROWS; b += 5000) {
            StringBuilder sb = new StringBuilder("INSERT INTO t [");
            for (int i = b; i < b + 5000; i++) {
                sb.append(i > b ? "," : "").append("{valor: ").append(100 + i % 50).append("}");
            }
            ds.execute(sb.append(']').toString(), s, null);
        }
        ds.execute("CREATE FUNCTION desconto(valor) AS $$\n    return valor * 0.9\n$$", s, null);
        assertEquals(ROWS, ds.execute("SELECT valor FROM t", s, null).asArray().size());

        String base = "SELECT valor, valor * 0.9 AS d FROM t";
        String comFuncao = "SELECT valor, desconto(valor) AS d FROM t";
        bestMillis(base, 2);
        bestMillis(comFuncao, 2);
        double baseMs = bestMillis(base, 5);
        double funcMs = bestMillis(comFuncao, 5);
        double microsPerRow = (funcMs - baseMs) * 1000.0 / ROWS;
        System.out.printf("PERF funcao por linha: base=%.1f ms, com funcao=%.1f ms, extra=%.2f us/linha%n",
            baseMs, funcMs, microsPerRow);
        assertTrue(microsPerRow < 10.0, "custo extra por linha: " + microsPerRow + " us");
    }

    @Test
    void vazaoDeProcedureComUmaLeituraEUmaEscrita() {
        ds.execute("CREATE c:1 CONTENT {n: 0}", s, null);
        ds.execute("""
            CREATE PROCEDURE conta() AS $$
                atual = db.one("SELECT n FROM c:1")
                db.exec("UPDATE c:1 SET n = $n", {"n": atual["n"] + 1})
            $$""", s, null);
        int calls = 2000;
        ds.execute("CALL conta()", s, null);
        long b0 = System.nanoTime();
        for (int i = 0; i < calls; i++) {
            ds.execute("SELECT n FROM c:1", s, null);
            ds.execute("UPDATE c:1 SET n = 5", s, null);
        }
        System.out.printf("PERF referencia SQL puro (1 SELECT + 1 UPDATE): %.0f pares/s%n",
            calls / ((System.nanoTime() - b0) / 1e9));
        ds.execute("UPDATE c:1 SET n = 0", s, null);
        ds.execute("CALL conta()", s, null);
        calls = 2000;
        long t0 = System.nanoTime();
        for (int i = 0; i < calls; i++) {
            ds.execute("CALL conta()", s, null);
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf("PERF procedure (1 db.one + 1 db.exec): %d chamadas em %.3f s = %.0f chamadas/s%n",
            calls, secs, calls / secs);
        assertEquals(calls + 1, ds.execute("SELECT n FROM c:1", s, null).asObject().get("n").asLong());
    }
}
