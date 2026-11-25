package com.axonbase.core;

import com.axonbase.core.ScaleGateTest.Run;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Regras do teste de portão de escala (sem rodar o benchmark)")
class ScaleGateEvaluationTest {

    private static Run run(double ratio, double limit10, double p99, double order, double filter, double calSingle,
                           double calParallel, int cpus) {
        Map<String, Double> raw = new LinkedHashMap<>();
        raw.put("ingest_ratio_8x1", ratio);
        raw.put("select_limit10_ms", limit10);
        raw.put("nearest20_p99_ms", p99);
        raw.put("order_by_limit10_ms", order);
        raw.put("where_nomatch_limit10_ms", filter);
        return new Run(raw, calSingle, calParallel, cpus);
    }

    private static final Run BASE = run(2.4, 2, 60, 200, 150, 50, 200, 4);

    @Test
    @DisplayName("execução igual à linha de base passa")
    void sameAsBaselinePasses() {
        assertTrue(ScaleGateTest.evaluate(BASE, BASE).isEmpty());
    }

    @Test
    @DisplayName("máquina duas vezes mais lenta passa, porque a calibração acompanha")
    void slowerMachineIsNormalized() {
        Run slow = run(2.4, 4, 99, 400, 300, 100, 400, 4);
        assertTrue(ScaleGateTest.evaluate(slow, BASE).isEmpty(), ScaleGateTest.evaluate(slow, BASE).toString());
    }

    @Test
    @DisplayName("piora de mais de 25% na mesma máquina reprova")
    void regressionFails() {
        Run worse = run(2.4, 2, 60, 280, 150, 50, 200, 4);
        List<String> failures = ScaleGateTest.evaluate(worse, BASE);
        assertEquals(1, failures.size(), failures.toString());
        assertTrue(failures.get(0).contains("order_by_limit10_ms"));
    }

    @Test
    @DisplayName("metas absolutas valem mesmo sem linha de base")
    void absoluteGoals() {
        Run bad = run(1.5, 80, 150, 900, 900, 50, 200, 4);
        assertEquals(5, ScaleGateTest.evaluate(bad, null).size());
    }

    @Test
    @DisplayName("razão de ingestão só é comparada com a linha de base com o mesmo número de núcleos")
    void ratioNeedsSameCpus() {
        Run other = run(2.05, 2, 60, 200, 150, 50, 200, 16);
        assertTrue(ScaleGateTest.evaluate(other, BASE).isEmpty());
        Run same = run(2.05, 2, 60, 200, 150, 50, 200, 4);
        assertEquals(1, ScaleGateTest.evaluate(same, BASE).size());
    }
}
