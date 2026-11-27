package com.axonbase.core.partition;

import com.axonbase.core.control.ControlCommand;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.IndexBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.axonbase.core.partition.TimeData.DAY2;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A construção retomada na releitura do catálogo precisa enxergar a tabela já particionada. */
@DisplayName("Partições: ordem da releitura do catálogo")
class PartitionReplayOrderTest {

    private static final String MARKER = "IXB|test|dev|events|quem";

    @TempDir
    Path tmp;

    @AfterEach
    void clearHook() {
        Datastore.replayHook(null);
    }

    private static void awaitBuilds() {
        try {
            assertTrue(IndexBuilder.awaitAll(10_000), "a construção não terminou");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static List<String> uniqueEntries(TimeData db) {
        return db.physicalKeys("events").stream().filter(k -> k.contains("!uidx")).toList();
    }

    @Test
    @DisplayName("índice UNIQUE retomado logo na releitura não fica pronto e vazio")
    void interruptedUniqueIndex_isRebuiltFromThePartitions() {
        try (TimeData db = TimeData.rocks(tmp.resolve("db")).threeDays()) {
            db.run("DEFINE INDEX quem ON TABLE events COLUMNS who UNIQUE");
            // como uma queda no meio da construção: o marcador ficou e as entradas ainda não existem
            uniqueEntries(db).forEach(db.backend::delete);
            db.backend.put(MARKER, new byte[] {1});
            // a construção termina antes do comando seguinte da releitura: o pior intercalamento possível
            Datastore.replayHook(command -> {
                if (command.kind() == ControlCommand.Kind.INDEX) {
                    awaitBuilds();
                }
            });

            db.restart();
            awaitBuilds();

            assertThrows(RuntimeException.class, () -> db.run(
                "CREATE events:dup CONTENT {at: d\"" + DAY2 + "\", who: \"ana\"}"));
            assertTrue(db.backend.get(MARKER).isEmpty(), "a construção retomada precisa terminar");
        }
    }
}
