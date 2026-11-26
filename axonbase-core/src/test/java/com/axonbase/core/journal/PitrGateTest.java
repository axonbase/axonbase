package com.axonbase.core.journal;

import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.core.storage.CommitWatermark;
import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Portões da E2 que custam minutos, ligados com {@code -Daxon.gate.pitr=true}: um milhão de commits com o relógio
 * saltando e {@code kill -9} sob carga com PITR depois da queda. {@code -Daxon.gate.pitr.commits} e
 * {@code -Daxon.gate.pitr.rounds} reduzem os dois para uma conferência rápida.
 */
@DisplayName("Portões do PITR: 1 milhão de commits e kill -9")
@EnabledIfSystemProperty(named = "axon.gate.pitr", matches = "true")
class PitrGateTest {

    @TempDir
    Path tmp;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(RocksDbBackend db, String key) {
        return db.get(key).map(v -> new String(v, StandardCharsets.UTF_8)).orElse(null);
    }

    @Test
    @DisplayName("1 milhão de commits com o relógio saltando: carimbos estritamente crescentes na ordem dos índices")
    void aMillionCommitsWithAJumpingClock() throws Exception {
        int total = Integer.getInteger("axon.gate.pitr.commits", 1_000_000);
        int threads = 8;
        AtomicLong wall = new AtomicLong(1_790_000_000_000L);
        AtomicLongArray physical = new AtomicLongArray(total + 1);
        AtomicLongArray logical = new AtomicLongArray(total + 1);
        long started = System.nanoTime();
        try (RocksDbBackend db = new RocksDbBackend(tmp.resolve("million").toString(), RocksDbBackend.SyncMode.OFF,
                new HybridClock(wall::get, 500L, e -> { }))) {
            db.commitSink((index, batch) -> {
                physical.set((int) index, batch.stamp().physical());
                logical.set((int) index, batch.stamp().logical());
            });
            List<Thread> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int n = t;
                workers.add(Thread.ofPlatform().start(() -> {
                    Random random = new Random(n);
                    for (int i = 0; i < total / threads; i++) {
                        int dice = random.nextInt(1000);
                        if (dice < 5) {
                            wall.addAndGet(-random.nextInt(100_000));
                        } else if (dice < 10) {
                            wall.addAndGet(random.nextInt(100_000));
                        } else if (dice < 200) {
                            wall.incrementAndGet();
                        }
                        db.put("k" + random.nextInt(5_000), bytes("v"));
                    }
                }));
            }
            for (Thread w : workers) {
                w.join();
            }
            assertEquals(total, db.watermark().index());
        }
        for (int i = 2; i <= total; i++) {
            boolean grows = physical.get(i) > physical.get(i - 1)
                || physical.get(i) == physical.get(i - 1) && logical.get(i) > logical.get(i - 1);
            if (!grows) {
                throw new AssertionError("o carimbo do índice " + i + " não é maior que o do anterior");
            }
        }
        System.out.println("GATE HLC: " + total + " commits em " + threads + " threads, ordem de índice igual à de"
            + " carimbo, " + (System.nanoTime() - started) / 1_000_000 + " ms");
    }

    /** Processo filho: grava sem parar, com o journal ligado e um backup físico logo no começo. */
    public static final class Child {
        public static void main(String[] args) throws Exception {
            Path live = Path.of(args[0]);
            Path store = Path.of(args[1]);
            JournalOptions options = JournalOptions.defaults()
                .withMaxSegmentAge(Duration.ofSeconds(Long.parseLong(args[2])));
            RocksDbBackend db = new RocksDbBackend(live.toString(), RocksDbBackend.SyncMode.BATCH);
            PitrJournal.attach(db, live.resolve(".pitr-journal"), options);
            db.put("seq", bytes("-1"));
            try (PhysicalBackups backups = PhysicalBackups.open(store)) {
                backups.create(db);
            }
            // escritas de fundo em várias threads, para a fila do journal ter o que perder
            for (int t = 0; t < 4; t++) {
                int n = t;
                Thread.ofPlatform().daemon(true).start(() -> {
                    byte[] value = new byte[256];
                    for (long i = 0; ; i++) {
                        db.put("ruido-" + n + "-" + (i % 2_000), value);
                    }
                });
            }
            System.out.println("READY");
            System.out.flush();
            for (long i = 0; ; i++) {
                // um commit atômico: o registro i, o ponteiro para o último e a hora dele. Um corte consistente vê um
                // prefixo, e a hora diz quanto tempo de escritas ficou fora do journal.
                db.commit(Map.of(), Map.of("item-" + i, bytes("x" + i), "seq", bytes(String.valueOf(i)),
                    "quando", bytes(String.valueOf(System.currentTimeMillis()))), Set.of());
            }
        }
    }

    private Process spawn(Path log, String... args) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = new ArrayList<>(List.of(java, "-Xmx512m", "--enable-native-access=ALL-UNNAMED", "-cp",
            System.getProperty("java.class.path"), Child.class.getName()));
        cmd.addAll(List.of(args));
        return new ProcessBuilder(cmd).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(new File(log.toString()))).start();
    }

    @Test
    @DisplayName("kill -9 sob carga: perde no máximo 60 s de lotes arquivados e o PITR depois da queda é consistente")
    void killNineLosesAtMostSixtySeconds() throws Exception {
        org.rocksdb.RocksDB.loadLibrary();
        Random random = new Random(11);
        long worstLossMs = 0;
        long worstLostItems = 0;
        int rounds = Integer.getInteger("axon.gate.pitr.rounds", 8);
        for (int round = 0; round < rounds; round++) {
            Path live = tmp.resolve("live" + round);
            Path store = tmp.resolve("store" + round);
            Path log = tmp.resolve("child" + round + ".log");
            // a última rodada usa o segmento padrão de 25 s: o que protege é a recuperação do segmento aberto
            long segmentSeconds = round == rounds - 1 ? 25 : 1;
            Process p = spawn(log, live.toString(), store.toString(), String.valueOf(segmentSeconds));
            try {
                long deadline = System.currentTimeMillis() + 120_000;
                while (!(Files.exists(log) && Files.readString(log).contains("READY"))) {
                    assertTrue(p.isAlive() && System.currentTimeMillis() < deadline,
                        () -> "o filho não ficou pronto: " + readQuietly(log));
                    Thread.sleep(20);
                }
                Thread.sleep(1_000 + random.nextInt(4_000));
            } finally {
                p.destroyForcibly();
                p.waitFor(30, TimeUnit.SECONDS);
            }

            Path journalDir = live.resolve(".pitr-journal");
            PitrCoverage.Window window;
            CommitWatermark base;
            long committedItem;
            long committedAt;
            try (PhysicalBackups backups = PhysicalBackups.open(store)) {
                base = backups.list().get(0).watermark();
            }
            try (RocksDbBackend db = new RocksDbBackend(live.toString(), RocksDbBackend.SyncMode.OFF)) {
                // o que a base viva tem é o que estava confirmado quando o processo morreu
                committedItem = Long.parseLong(text(db, "seq"));
                committedAt = Long.parseLong(text(db, "quando"));
                try (PitrJournal journal = PitrJournal.open(journalDir, JournalOptions.defaults(), db::watermark)) {
                    window = PitrCoverage.window(journalDir, journal.segments(), base);
                }
            }

            // restaurar ao último instante alcançável dá um corte consistente: um prefixo dos commits do item
            HlcTimestamp target = window.to();
            PitrRestore.Prepared prepared = PitrRestore.prepare(store, 1, journalDir, live, target);
            try (RocksDbBackend restored = new RocksDbBackend(prepared.directory().toString(),
                    RocksDbBackend.SyncMode.OFF)) {
                long last = Long.parseLong(text(restored, "seq"));
                for (long i = 0; i <= last; i++) {
                    assertEquals("x" + i, text(restored, "item-" + i), "rodada " + round + ": buraco no item " + i);
                }
                assertEquals(null, text(restored, "item-" + (last + 1)), "rodada " + round + ": lote pela metade");
                long lossMs = Math.max(0, committedAt - Long.parseLong(text(restored, "quando")));
                long lostItems = Math.max(0, committedItem - last);
                worstLossMs = Math.max(worstLossMs, lossMs);
                worstLostItems = Math.max(worstLostItems, lostItems);
                assertTrue(lossMs <= 60_000, "rodada " + round + ": perdeu " + lossMs + " ms");
                System.out.println("GATE KILL rodada " + round + " (segmento " + segmentSeconds + " s): item "
                    + committedItem + " confirmado, " + lostItems + " fora do journal (" + lossMs + " ms), "
                    + prepared.appliedBatches() + " lotes reaplicados, item restaurado até " + last);
            }
        }
        System.out.println("GATE KILL: pior perda " + worstLossMs + " ms e " + worstLostItems + " itens em "
            + rounds + " rodadas");
    }

    private static String readQuietly(Path log) {
        try {
            return Files.readString(log);
        } catch (IOException e) {
            return e.getMessage();
        }
    }
}
