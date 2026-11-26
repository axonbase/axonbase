package com.axonbase.core.partition;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.partition.cold.FileSystemColdStore;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kill -9 enquanto o motor cria partições, as leva para a camada fria, as traz de volta e as remove. Ao reabrir, cada
 * partição listada está inteira, o que foi removido não deixa chave alguma, e a manutenção termina o que ficou pelo
 * meio.
 */
@DisplayName("Partições: queda do processo")
class PartitionCrashTest {

    private static final int ROWS = 20;
    private static final Instant FIRST = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tmp;

    private static ColdTier tier(Path cold, Path cache) {
        return ColdTier.of(new FileSystemColdStore(cold), cache, ColdTier.Settings.defaults());
    }

    private static Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    private static PartitionMaintenance maintenance(Datastore ds) {
        return new PartitionMaintenance(ds, PartitionMaintenance.Options.defaults(), System::currentTimeMillis);
    }

    private static String start(long day) {
        return "d\"" + FIRST.plus(Duration.ofDays(day)) + "\"";
    }

    private static List<Long> days(Datastore ds) {
        return ds.execute("SHOW PARTITIONS ON TABLE events", session(), null).asArray().stream()
            .map(p -> Duration.between(FIRST, p.asObject().get("start").asInstant()).toDays()).toList();
    }

    /** Processo filho: {@code <base> <frio> <cache>}. Roda até ser morto. */
    public static final class Child {
        public static void main(String[] args) {
            Datastore ds = new Datastore(new RocksDbBackend(args[0], RocksDbBackend.SyncMode.OFF),
                tier(Path.of(args[1]), Path.of(args[2])));
            if (ds.findDatabase("test", "dev") == null) {
                ds.createDatabase("test", "dev");
            }
            Session s = session();
            ds.execute("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d", s, null);
            long next = days(ds).stream().mapToLong(Long::longValue).max().orElse(-1) + 1;
            for (long day = next; ; day++) {
                // o dia inteiro num commit: uma partição listada tem sempre todas as linhas
                ds.execute("BEGIN", s, null);
                for (int i = 0; i < ROWS; i++) {
                    ds.execute("CREATE events:d" + day + "r" + i + " CONTENT {at: " + start(day) + "}", s, null);
                }
                ds.execute("COMMIT", s, null);
                List<Long> listed = days(ds);
                if (listed.contains(day - 2)) {
                    ds.execute("ALTER PARTITION ON TABLE events AT " + start(day - 2) + " TO COLD", s, null);
                }
                if (listed.contains(day - 4) && day % 2 == 0) {
                    ds.execute("ALTER PARTITION ON TABLE events AT " + start(day - 4) + " TO HOT", s, null);
                }
                if (listed.contains(day - 6)) {
                    ds.execute("DROP PARTITION ON TABLE events AT " + start(day - 6), s, null);
                }
                maintenance(ds).runOnce();
                System.out.println("ROUND " + day);
                System.out.flush();
            }
        }
    }

    private Process spawn(Path log, String... args) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new java.util.ArrayList<>(List.of(java, "-Xmx512m", "-cp",
            System.getProperty("java.class.path"), Child.class.getName()));
        command.addAll(List.of(args));
        return new ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(new File(log.toString()))).start();
    }

    private static void awaitRound(Process process, Path log) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(log) && Files.readString(log).contains("ROUND")) {
                return;
            }
            if (!process.isAlive()) {
                break;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("o filho não completou uma rodada: " + Files.readString(log));
    }

    @Test
    @DisplayName("kill -9 em momentos aleatórios: nenhuma partição pela metade e nenhuma sobra das removidas")
    void killDuringPartitionLifecycle() throws Exception {
        Random random = new Random(11);
        Path database = tmp.resolve("db");
        Path cold = tmp.resolve("cold");
        for (int round = 0; round < 8; round++) {
            Path log = tmp.resolve("child-" + round + ".log");
            Process process = spawn(log, database.toString(), cold.toString(), tmp.resolve("cache").toString());
            try {
                awaitRound(process, log);
                Thread.sleep(100 + random.nextInt(900));
                assertTrue(process.isAlive(), "o filho caiu sozinho: " + Files.readString(log));
            } finally {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            try (ColdTier tier = tier(cold, tmp.resolve("cache-parent"));
                 RocksDbBackend backend = new RocksDbBackend(database.toString(), RocksDbBackend.SyncMode.OFF)) {
                Datastore ds = new Datastore(backend, tier);
                verify(ds, backend, "rodada " + round + " ao reabrir");
                assertEquals(0, maintenance(ds).runOnce().failures(), "rodada " + round);
                verify(ds, backend, "rodada " + round + " depois da manutenção");
            }
        }
    }

    private static void verify(Datastore ds, RocksDbBackend backend, String when) {
        List<Long> listed = days(ds);
        for (long day : listed) {
            AxonValue rows = ds.execute("SELECT * FROM events WHERE at = " + start(day), session(), null);
            assertEquals(ROWS, rows.asArray().size(), when + ": dia " + day);
        }
        AxonValue all = ds.execute("SELECT * FROM events", session(), null);
        assertEquals(listed.size() * ROWS, all.asArray().size(), when);
        List<String> prefixes = listed.stream().map(d -> PartitionKeys.partitionPrefix("test", "dev", "events",
            FIRST.plus(Duration.ofDays(d)).toEpochMilli())).toList();
        for (String key : backend.keysWithPrefix(PartitionKeys.tablePrefix("test", "dev", "events"))) {
            assertTrue(prefixes.stream().anyMatch(key::startsWith), when + ": chave órfã " + key);
        }
    }
}
