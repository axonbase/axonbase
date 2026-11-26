package com.axonbase.core.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Portão 1 da E2, opcional ({@code -Daxon.gate.raft=true}): com 64 escritores simultâneos num cluster de três
 * nós, {@code always} precisa render pelo menos 60% do modo {@code batch}. Pensado para Linux numa máquina
 * dedicada; em máquina carregada ou em disco virtualizado o número não vale como veredito.
 *
 * <p>Propriedades: {@code axon.gate.raft.writers} (padrão {@code 8,64,256}; o veredito usa 64),
 * {@code axon.gate.raft.seconds} (20), {@code axon.gate.raft.rounds} (3) e {@code axon.gate.raft.dir}
 * (diretório no disco a medir; padrão temporário).</p>
 */
@DisplayName("Portão E2.1: group commit com AXON_STORAGE_SYNC=always")
@EnabledIfSystemProperty(named = "axon.gate.raft", matches = "true")
class RaftWriteGateTest {

    private static final int GATE_WRITERS = 64;
    private static final double MINIMUM_RATIO = 0.60;

    @TempDir
    Path temporary;

    @Test
    void alwaysKeepsAtLeastSixtyPercentOfBatch() throws Exception {
        int[] writers = RaftWriteGateBenchmark.parseWriters(
            System.getProperty("axon.gate.raft.writers", "8,64,256"));
        int seconds = Integer.getInteger("axon.gate.raft.seconds", 20);
        int rounds = Integer.getInteger("axon.gate.raft.rounds", 3);
        String chosen = System.getProperty("axon.gate.raft.dir");
        Path dir = chosen == null ? temporary : Path.of(chosen);
        List<RaftWriteGateBenchmark.Result> results = new ArrayList<>();
        String previous = System.getProperty("axon.storage.sync");
        try {
            for (int round = 0; round < rounds; round++) {
                for (String mode : List.of("always", "batch")) {
                    System.setProperty("axon.storage.sync", mode);
                    List<RaftWriteGateBenchmark.Result> run = RaftWriteGateBenchmark.run(dir, writers, seconds);
                    // AXON_STORAGE_SYNC no ambiente teria precedência e invalidaria a comparação.
                    assertEquals(mode.toUpperCase(java.util.Locale.ROOT), run.getFirst().mode(),
                        "rode sem a variável AXON_STORAGE_SYNC");
                    results.addAll(run);
                }
            }
        } finally {
            if (previous == null) {
                System.clearProperty("axon.storage.sync");
            } else {
                System.setProperty("axon.storage.sync", previous);
            }
        }
        List<String> failures = new ArrayList<>();
        for (String scenario : List.of("raw", "sql")) {
            for (int count : writers) {
                double always = median(results, "ALWAYS", scenario, count);
                double batch = median(results, "BATCH", scenario, count);
                double ratio = always / batch;
                System.out.printf("GATE1 summary scenario=%s writers=%d always=%.0f batch=%.0f ratio=%.3f%n",
                    scenario, count, always, batch, ratio);
                if (count == GATE_WRITERS && !(ratio >= MINIMUM_RATIO)) {
                    failures.add(String.format("%s: always rende %.1f%% do batch com %d escritores", scenario,
                        ratio * 100, count));
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("; ", failures));
    }

    private static double median(List<RaftWriteGateBenchmark.Result> results, String mode, String scenario,
                                 int writers) {
        double[] sorted = results.stream()
            .filter(r -> r.mode().equals(mode) && r.scenario().equals(scenario) && r.writers() == writers)
            .mapToDouble(RaftWriteGateBenchmark.Result::opsPerSecond).sorted().toArray();
        return sorted[sorted.length / 2];
    }
}
