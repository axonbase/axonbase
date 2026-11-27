package com.axonbase.core.partition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Uma transação confirmada de dentro de uma escrita (evento que chama procedure) não pode travar o banco. */
@DisplayName("Partições: escrita disparada de dentro de outra escrita")
class PartitionNestedWriteTest {

    @TempDir
    Path tmp;

    /** Roda numa thread à parte, com prazo: uma trava que não volta vira falha do teste, e não teste parado. */
    private static Future<?> start(ExecutorService worker, TimeData db, String sql) {
        return worker.submit(() -> db.run(sql));
    }

    @Test
    @DisplayName("procedure chamada por evento que precisa criar partição é recusada, sem travar as escritas")
    void procedureFromEvent_newPartition_isRefusedInsteadOfDeadlocking() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "escrita-com-evento");
            thread.setDaemon(true);
            return thread;
        });
        try (TimeData db = TimeData.rocks(tmp.resolve("db"))) {
            db.run("DEFINE PARTITION ON TABLE registro FIELD at INTERVAL 1d");
            db.run("CREATE PROCEDURE registrar() AS $$ "
                + "db.exec('CREATE registro CONTENT {at: d\"2026-11-01T10:00:00Z\"}') $$");
            db.run("DEFINE EVENT registra ON TABLE pedido WHEN true THEN (CALL registrar())");

            Future<?> first = start(worker, db, "CREATE pedido:um CONTENT {v: 1}");

            assertThrows(ExecutionException.class, () -> first.get(10, TimeUnit.SECONDS));
            assertEquals(0, db.count("SELECT * FROM pedido"));
            assertEquals(0, db.count("SELECT * FROM registro"));

            // a partição criada por uma escrita comum libera o evento, e o banco continua gravando
            start(worker, db, "CREATE registro CONTENT {at: d\"2026-11-01T11:00:00Z\"}").get(10, TimeUnit.SECONDS);
            start(worker, db, "CREATE pedido:dois CONTENT {v: 2}").get(10, TimeUnit.SECONDS);

            assertEquals(1, db.count("SELECT * FROM pedido"));
            assertEquals(2, db.count("SELECT * FROM registro"));
        } finally {
            worker.shutdownNow();
        }
    }
}
