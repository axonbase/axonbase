package com.axonbase.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A agregação por fingerprint é limitada mesmo quando muitas threads trazem instruções diferentes ao mesmo tempo.
 * Sem o limite, cada instrução nova percorre um mapa cada vez maior e o servidor fica lento.
 */
@DisplayName("Limite do painel de atividade")
class ActivityRegistryBoundTest {

    private static final int LIMIT = 500;
    private static final int THREADS = 32;
    private static final int STATEMENTS_PER_THREAD = 1_000;

    @Test
    @DisplayName("instruções distintas e concorrentes nunca passam do limite de fingerprints")
    void distinctConcurrentStatementsStayBounded() throws Exception {
        ActivityRegistry registry = new ActivityRegistry(ActivityConfig.from(Map.of()));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                int worker = t;
                workers.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < STATEMENTS_PER_THREAD; i++) {
                        // o nome da tabela não é um literal: cada instrução tem o seu fingerprint
                        ActivityRegistry.Running run = registry.begin(null, "app", "main",
                            "SELECT * FROM t" + worker + "_" + i, "http", null, null, null);
                        registry.end(run, false, false);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        int kept = registry.top("count", Integer.MAX_VALUE).size();

        assertTrue(kept <= LIMIT, "fingerprints guardados: " + kept);
        assertTrue(kept > LIMIT / 2, "o descarte não pode esvaziar a agregação: " + kept);
    }
}
