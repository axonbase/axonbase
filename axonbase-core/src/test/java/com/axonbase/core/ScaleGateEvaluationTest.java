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

    private static final Run BASE = run(2.4, 2, 60, 200, 150, 50, 200, 12);

    @Test
    @DisplayName("execução igual à linha de base passa")
    void sameAsBaselinePasses() {
        assertTrue(ScaleGateTest.evaluate(BASE, BASE).isEmpty());
    }

    @Test
    @DisplayName("máquina duas vezes mais lenta passa, porque a calibração acompanha")
    void slowerMachineIsNormalized() {
        Run slow = run(2.4, 4, 99, 400, 300, 100, 400, 12);
        assertTrue(ScaleGateTest.evaluate(slow, BASE).isEmpty(), ScaleGateTest.evaluate(slow, BASE).toString());
    }

    @Test
    @DisplayName("piora de mais de 25% na mesma máquina reprova")
    void regressionFails() {
        Run worse = run(2.4, 2, 60, 280, 150, 50, 200, 12);
        List<String> failures = ScaleGateTest.evaluate(worse, BASE);
        assertEquals(1, failures.size(), failures.toString());
        assertTrue(failures.get(0).contains("order_by_limit10_ms"));
    }

    @Test
    @DisplayName("metas absolutas valem mesmo sem linha de base")
    void absoluteGoals() {
        Run bad = run(0.2, 80, 150, 900, 900, 50, 200, 12);
        assertEquals(5, ScaleGateTest.evaluate(bad, null).size());
    }

    @Test
    @DisplayName("razão de ingestão tem só o piso de sanidade e não é comparada com a linha de base")
    void ingestRatioHasOnlyASanityFloor() {
        Run fast = run(3.2, 2, 60, 200, 150, 50, 200, 12);
        // 0,4 é o que se mede em Linux virtualizado: passa mesmo contra uma base de 3,2 com o mesmo número de núcleos
        assertTrue(ScaleGateTest.evaluate(run(0.4, 2, 60, 200, 150, 50, 200, 12), fast).isEmpty());
        // abaixo do piso as threads estão se atrapalhando de forma grave
        List<String> failures = ScaleGateTest.evaluate(run(0.2, 2, 60, 200, 150, 50, 200, 12), fast);
        assertEquals(1, failures.size(), failures.toString());
        assertTrue(failures.get(0).contains("ingest_ratio_8x1"));
    }

    @Test
    void metasDependemDosNucleosSoOndeFazSentido() {
        // a razão de ingestão tem só o piso de sanidade, em qualquer máquina
        assertEquals(0.3, ScaleGateTest.goalFor(ScaleGateTest.Metric.INGEST_RATIO, 12), 1e-9);
        assertEquals(0.3, ScaleGateTest.goalFor(ScaleGateTest.Metric.INGEST_RATIO, 2), 1e-9);
        // varredura paralela: 500 ms com 12 núcleos ou mais, subindo até 2,5 vezes em máquinas menores
        assertEquals(500.0, ScaleGateTest.goalFor(ScaleGateTest.Metric.ORDER_TOPK_MS, 12), 1e-9);
        assertEquals(500.0, ScaleGateTest.goalFor(ScaleGateTest.Metric.ORDER_TOPK_MS, 64), 1e-9);
        assertEquals(1250.0, ScaleGateTest.goalFor(ScaleGateTest.Metric.ORDER_TOPK_MS, 4), 1e-9);
        assertEquals(1250.0, ScaleGateTest.goalFor(ScaleGateTest.Metric.FILTER_SCAN_MS, 1), 1e-9);
        // a meta de varredura ainda reprova a volta ao caminho serial (cerca de 2,4 s) em 4 núcleos
        assertTrue(2400.0 > ScaleGateTest.goalFor(ScaleGateTest.Metric.ORDER_TOPK_MS, 4));
        // as metas de latência de uma consulta só não dependem do número de núcleos
        assertEquals(50.0, ScaleGateTest.goalFor(ScaleGateTest.Metric.LIMIT10_MS, 2), 1e-9);
        assertEquals(100.0, ScaleGateTest.goalFor(ScaleGateTest.Metric.NEAREST_P99_MS, 64), 1e-9);
    }
}
