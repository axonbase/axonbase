package com.axonbase.core.journal;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.BackupEngine;
import org.rocksdb.BackupEngineOptions;
import org.rocksdb.Env;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Restauração a um instante: backup físico mais o journal, comparado a um modelo de referência")
class PitrRestoreTest {

    @TempDir
    Path tmp;

    private final AtomicLong wall = new AtomicLong(1_790_000_000_000L);

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static TreeMap<String, String> dump(RocksDbBackend b) {
        TreeMap<String, String> out = new TreeMap<>();
        b.scanEntries("", (k, v) -> {
            out.put(k, new String(v, StandardCharsets.UTF_8));
            return true;
        });
        return out;
    }

    private static JournalOptions options() {
        return JournalOptions.defaults().withMaxSegmentAge(Duration.ofHours(1)).withIdleHeartbeat(Duration.ZERO);
    }

    /** Um nó com relógio falso, journal ligado e diretório de backup. */
    private final class Node implements AutoCloseable {
        final Path live;
        final Path backups;
        final Path journalDir;
        RocksDbBackend db;
        PitrJournal journal;
        PhysicalBackups engine;

        Node(String name) {
            this(name, options());
        }

        Node(String name, JournalOptions options) {
            live = tmp.resolve(name).resolve("live");
            backups = tmp.resolve(name).resolve("backups");
            journalDir = tmp.resolve(name).resolve("journal");
            open(options);
            engine = PhysicalBackups.open(backups);
        }

        void open(JournalOptions options) {
            db = new RocksDbBackend(live.toString(), RocksDbBackend.SyncMode.OFF,
                new HybridClock(wall::get, 500L, e -> { }));
            journal = PitrJournal.attach(db, journalDir, options);
        }

        void restart() {
            journal.close();
            db.close();
            open(options());
        }

        int backup() {
            return engine.create(db).id();
        }

        /** Restaura ao instante, troca a base e devolve o que ficou nela. */
        TreeMap<String, String> restoreTo(int backup, long instant) {
            wall.incrementAndGet(); // o milissegundo pedido precisa ter terminado para o journal cobri-lo
            journal.seal();
            PitrRestore.Prepared prepared = PitrRestore.prepare(backups, backup, journalDir, live,
                HlcTimestamp.endOfMillisecond(instant));
            db.replaceWith(prepared.directory(), 30_000);
            return dump(db);
        }

        @Override
        public void close() {
            journal.close();
            db.close();
            engine.close();
        }
    }

    /** Executa uma operação aleatória na base e no modelo. */
    private static void randomOp(Random random, RocksDbBackend db, Map<String, String> model, int step) {
        String key = "k" + random.nextInt(60);
        switch (random.nextInt(10)) {
            case 0, 1 -> {
                db.delete(key);
                model.remove(key);
            }
            case 2, 3 -> {
                String other = "k" + random.nextInt(60);
                Map<String, byte[]> puts = new java.util.LinkedHashMap<>();
                puts.put(key, bytes("c" + step));
                puts.put("t" + random.nextInt(20), bytes("c" + step));
                Set<String> deletes = puts.containsKey(other) ? Set.of() : Set.of(other);
                db.commit(Map.of(), puts, deletes);
                deletes.forEach(model::remove);
                puts.forEach((k, v) -> model.put(k, new String(v, StandardCharsets.UTF_8)));
            }
            case 4 -> {
                if (db.putIfAbsent(key, bytes("a" + step))) {
                    model.put(key, "a" + step);
                }
            }
            default -> {
                db.put(key, bytes("p" + step));
                model.put(key, "p" + step);
            }
        }
    }

    /** Portão 5: diferença zero em instantes aleatórios, com backups tirados no meio da carga. */
    @Test
    @DisplayName("restaurar a instantes aleatórios dá diferença zero para o modelo de referência")
    void differentialRestoreAtRandomInstants() {
        for (int seed = 0; seed < 4; seed++) {
            Random random = new Random(seed);
            ConcurrentSkipListMap<Long, TreeMap<String, String>> history = new ConcurrentSkipListMap<>();
            List<long[]> backups = new ArrayList<>();
            try (Node node = new Node("diff" + seed,
                    options().withMaxSegmentBytes(700 + random.nextInt(3000)))) {
                TreeMap<String, String> model = new TreeMap<>();
                for (int step = 0; step < 600; step++) {
                    if (step % 150 == 20) {
                        backups.add(new long[] {node.backup(), wall.get()});
                        wall.incrementAndGet();
                    }
                    randomOp(random, node.db, model, step);
                    history.put(wall.get(), new TreeMap<>(model));
                    wall.addAndGet(1 + random.nextInt(50));
                }
                long first = backups.get(0)[1] + 1;
                long last = history.lastKey();
                List<Long> instants = new ArrayList<>();
                instants.add(last);
                for (int i = 0; i < 4; i++) {
                    instants.add(first + (long) (random.nextDouble() * (last - first)));
                }
                // do mais novo para o mais antigo: cada restore abre um buraco depois dos anteriores
                instants.sort(java.util.Comparator.reverseOrder());
                for (long instant : instants) {
                    long[] base = backups.stream().filter(b -> b[1] < instant)
                        .reduce((a, b) -> b).orElseThrow();
                    TreeMap<String, String> restored = node.restoreTo((int) base[0], instant);
                    assertEquals(history.floorEntry(instant).getValue(), restored,
                        "semente " + seed + ", instante " + instant);
                }
            }
        }
    }

    @Test
    @DisplayName("sob 8 threads, restaurar a um instante de pausa dá exatamente o estado registrado nele")
    void differentialRestoreUnderConcurrentLoad() throws Exception {
        try (Node node = new Node("conc", options().withMaxSegmentBytes(20_000)
                .withMaxSegmentAge(Duration.ofMillis(50)))) {
            ReentrantReadWriteLock pause = new ReentrantReadWriteLock(true);
            Map<String, String> model = new java.util.concurrent.ConcurrentHashMap<>();
            AtomicBoolean stop = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Thread> writers = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int n = t;
                writers.add(Thread.ofPlatform().start(() -> {
                    Random random = new Random(n);
                    // chaves próprias de cada thread (o modelo é exato) e um commit com várias de uma vez
                    for (int step = 0; !stop.get(); step++) {
                        pause.readLock().lock();
                        try {
                            String key = "w" + n + "-" + random.nextInt(40);
                            if (random.nextInt(5) == 0) {
                                node.db.delete(key);
                                model.remove(key);
                            } else if (random.nextInt(4) == 0) {
                                String second = "w" + n + "-m" + random.nextInt(10);
                                node.db.commit(Map.of(), Map.of(key, bytes("c" + step), second, bytes("c" + step)),
                                    Set.of());
                                model.put(key, "c" + step);
                                model.put(second, "c" + step);
                            } else {
                                node.db.put(key, bytes("p" + step));
                                model.put(key, "p" + step);
                            }
                            wall.incrementAndGet();
                        } catch (Throwable e) {
                            failure.compareAndSet(null, e);
                            return;
                        } finally {
                            pause.readLock().unlock();
                        }
                    }
                }));
            }
            List<Long> instants = new ArrayList<>();
            List<TreeMap<String, String>> states = new ArrayList<>();
            int base = 0;
            for (int i = 0; i < 8; i++) {
                Thread.sleep(80);
                if (i == 1) {
                    base = node.backup(); // backup no meio da carga
                }
                pause.writeLock().lock();
                try {
                    instants.add(wall.get());
                    states.add(new TreeMap<>(model));
                    wall.addAndGet(5); // separa o instante registrado dos próximos commits
                } finally {
                    pause.writeLock().unlock();
                }
            }
            stop.set(true);
            for (Thread w : writers) {
                w.join(60_000);
            }
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(0L, node.journal.droppedBatches());
            for (int i = 7; i >= 2; i--) {
                assertEquals(states.get(i), node.restoreTo(base, instants.get(i)), "pausa " + i);
            }
        }
    }

    @Test
    @DisplayName("depois de reiniciar o nó, instantes de antes e de depois continuam alcançáveis")
    void restoreAcrossARestart() {
        try (Node node = new Node("restart")) {
            node.db.put("a", bytes("1"));
            int base = node.backup();
            wall.addAndGet(10);
            node.db.put("b", bytes("2"));
            long before = wall.get();
            wall.addAndGet(10);
            node.restart();
            node.db.put("c", bytes("3"));
            long after = wall.get() + 5;
            wall.addAndGet(100);
            node.db.put("d", bytes("4"));

            assertEquals(Map.of("a", "1", "b", "2", "c", "3"), node.restoreTo(base, after));
            assertEquals(Map.of("a", "1", "b", "2"), node.restoreTo(base, before));
        }
    }

    @Test
    @DisplayName("um lote que não chegou ao journal impede de passar dele, com o último instante alcançável")
    void aGapStopsTheRestoreAtTheLastCompleteInstant() {
        try (Node node = new Node("gap")) {
            node.db.put("a", bytes("1"));
            int base = node.backup();
            wall.addAndGet(10);
            node.db.put("b", bytes("2"));
            long reachable = wall.get();
            wall.addAndGet(10);
            node.journal.seal();
            node.db.commitSink(null); // o journal perde o próximo lote
            node.db.put("perdido", bytes("x"));
            node.db.commitSink(node.journal);
            wall.addAndGet(10);
            node.db.put("c", bytes("3"));
            long unreachable = wall.get();
            node.journal.seal();

            PitrException error = assertThrows(PitrException.class, () -> PitrRestore.prepare(node.backups, base,
                node.journalDir, node.live, HlcTimestamp.endOfMillisecond(unreachable)));

            assertEquals(PitrException.Reason.UNREACHABLE, error.reason());
            assertTrue(error.latest().physical() >= reachable && error.latest().physical() < unreachable,
                "último alcançável: " + error.latest());
            assertTrue(!Files.exists(node.live.resolve(".restoring")), "nada preparado fica para trás");
            assertEquals(Map.of("a", "1", "b", "2"), node.restoreTo(base, reachable));
        }
    }

    @Test
    @DisplayName("um segmento com um buraco no meio vale até o último lote antes dele, não é descartado inteiro")
    void aSegmentIsUsableUpToItsFirstHole() throws Exception {
        try (Node node = new Node("partial")) {
            node.db.put("a", bytes("1"));
            int base = node.backup();
            node.journal.seal();
            wall.addAndGet(10);
            node.db.put("b", bytes("2"));
            wall.addAndGet(10);
            node.db.put("c", bytes("3"));
            long reachable = wall.get();
            wall.addAndGet(10);
            node.db.commitSink(null);
            node.db.put("perdido", bytes("x"));
            node.db.commitSink(node.journal);
            wall.addAndGet(10);
            node.db.put("d", bytes("4")); // no mesmo segmento, depois do buraco
            node.journal.seal();

            PitrCoverage.Window window = PitrCoverage.window(node.journalDir, node.journal.segments(),
                node.engine.list().get(0).watermark());

            assertEquals(reachable, window.to().physical(), "vai até o lote imediatamente antes do buraco");
            // o milissegundo do lote c pode ter mais lotes (o perdido): o último que se pode pedir é o anterior
            assertEquals(reachable - 1, window.latestMillis());
            assertEquals(Map.of("a", "1", "b", "2"), node.restoreTo(base, window.latestMillis()));
        }
    }

    @Test
    @DisplayName("um backup tirado depois do buraco volta a permitir instantes novos")
    void aNewBackupAfterAGapResumesCoverage() {
        try (Node node = new Node("gapbase")) {
            node.db.put("a", bytes("1"));
            wall.addAndGet(10);
            node.db.commitSink(null);
            node.db.put("perdido", bytes("x"));
            node.db.commitSink(node.journal);
            wall.addAndGet(10);
            int base = node.backup();
            wall.addAndGet(10);
            node.db.put("c", bytes("3"));
            long instant = wall.get();

            assertEquals(Map.of("a", "1", "perdido", "x", "c", "3"), node.restoreTo(base, instant));
        }
    }

    @Test
    @DisplayName("depois de uma restauração, a história abandonada não é reaplicada em instantes novos")
    void theAbandonedHistoryIsNeverMixedIn() {
        try (Node node = new Node("timeline")) {
            node.db.put("a", bytes("1"));
            int base = node.backup();
            wall.addAndGet(10);
            long early = wall.get();
            node.db.put("early", bytes("e"));
            long afterEarly = wall.get();
            wall.addAndGet(10);
            node.db.put("abandonado", bytes("x"));
            wall.addAndGet(10);

            assertEquals(Map.of("a", "1", "early", "e"), node.restoreTo(base, afterEarly));
            wall.addAndGet(10);
            node.db.put("novo", bytes("n"));
            long now = wall.get();

            // o backup antigo não atravessa o restore
            PitrException error = assertThrows(PitrException.class, () -> node.restoreTo(base, now));
            assertEquals(PitrException.Reason.UNREACHABLE, error.reason());
            // um backup tirado depois dele alcança o presente, sem o lote abandonado
            int fresh = node.backup();
            wall.addAndGet(10);
            node.db.put("mais", bytes("m"));
            assertEquals(Map.of("a", "1", "early", "e", "novo", "n", "mais", "m"),
                node.restoreTo(fresh, wall.get()));
            // e o passado anterior ao restore continua disponível com o backup antigo
            assertTrue(early < now);
        }
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (var files = Files.walk(from)) {
            for (Path p : files.toList()) {
                Path target = to.resolve(from.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * A base volta a uma foto tirada num fechamento limpo, escreve com o journal desligado e ganha um backup novo:
     * os segmentos da história abandonada reusam os mesmos índices e não podem ser reaplicados sobre ele.
     */
    @Test
    @DisplayName("segmentos de uma história abandonada nunca são aplicados sobre um backup da história nova")
    void abandonedSegmentsAreNeverAppliedToANewHistory() throws Exception {
        Path live = tmp.resolve("rewind").resolve("live");
        Path photo = tmp.resolve("rewind").resolve("photo");
        Path backups = tmp.resolve("rewind").resolve("backups");
        Path journalDir = tmp.resolve("rewind").resolve("journal");
        Supplier<RocksDbBackend> opener = () -> new RocksDbBackend(live.toString(), RocksDbBackend.SyncMode.OFF,
            new HybridClock(wall::get, 500L, e -> { }));
        try (RocksDbBackend db = opener.get()) {
            db.put("a", bytes("1"));
        }
        copyTree(live, photo);
        try (RocksDbBackend db = opener.get(); PitrJournal journal = PitrJournal.attach(db, journalDir, options())) {
            for (int i = 0; i < 20; i++) {
                wall.incrementAndGet();
                db.put("abandonado" + i, bytes("x"));
            }
        }
        // a foto volta por cima (reversão de volume) e a base segue sem journal
        com.axonbase.core.storage.TestTrees.delete(live);
        copyTree(photo, live);
        long instant;
        int id;
        try (RocksDbBackend db = opener.get(); PhysicalBackups engine = PhysicalBackups.open(backups)) {
            wall.addAndGet(100);
            db.put("novo", bytes("n"));
            wall.addAndGet(10);
            instant = wall.get();
            wall.addAndGet(10);
            id = engine.create(db).id();
        }
        int backup = id;
        long target = instant;

        // ou recusa, ou entrega exatamente o estado daquele instante; nunca com os lotes abandonados
        try {
            PitrRestore.Prepared prepared = PitrRestore.prepare(backups, backup, journalDir, live,
                HlcTimestamp.endOfMillisecond(target));
            try (RocksDbBackend restored = new RocksDbBackend(prepared.directory().toString(),
                    RocksDbBackend.SyncMode.OFF)) {
                assertEquals(Map.of("a", "1", "novo", "n"), dump(restored));
            }
        } catch (PitrException refused) {
            assertTrue(refused.reason() == PitrException.Reason.UNREACHABLE
                || refused.reason() == PitrException.Reason.BASE_TOO_NEW, refused.reason().toString());
        }
    }

    @Test
    @DisplayName("segmentos de outra linha do tempo não contam na cobertura")
    void segmentsOfAnotherTimelineDoNotCount() {
        try (Node node = new Node("lines")) {
            node.db.put("a", bytes("1"));
            int base = node.backup();
            wall.addAndGet(10);
            node.db.put("b", bytes("2"));
            wall.addAndGet(10);
            node.journal.seal();
            var watermark = node.engine.list().get(0).watermark();
            var foreign = new com.axonbase.core.storage.CommitWatermark(watermark.stamp(), watermark.index(),
                watermark.timeline() + 1);

            assertTrue(PitrCoverage.window(node.journalDir, node.journal.segments(), watermark).contiguousIndex()
                > watermark.index());
            assertEquals(watermark.index(),
                PitrCoverage.window(node.journalDir, node.journal.segments(), foreign).contiguousIndex());
            assertTrue(base > 0);
        }
    }

    @Test
    @DisplayName("um instante anterior ao conteúdo do backup é recusado")
    void aBaseNewerThanTheInstantIsRejected() {
        try (Node node = new Node("toonew")) {
            node.db.put("a", bytes("1"));
            long early = wall.get();
            wall.addAndGet(10);
            node.db.put("b", bytes("2"));
            int base = node.backup();
            node.journal.seal();

            PitrException error = assertThrows(PitrException.class, () -> PitrRestore.prepare(node.backups, base,
                node.journalDir, node.live, HlcTimestamp.endOfMillisecond(early)));

            assertEquals(PitrException.Reason.BASE_TOO_NEW, error.reason());
            assertTrue(!Files.exists(node.live.resolve(".restoring")));
        }
    }

    @Test
    @DisplayName("um instante além do que o journal cobre é recusado com o último alcançável")
    void aFutureInstantIsRejected() {
        try (Node node = new Node("future")) {
            node.db.put("a", bytes("1"));
            int base = node.backup();
            node.journal.seal();

            PitrException error = assertThrows(PitrException.class, () -> PitrRestore.prepare(node.backups, base,
                node.journalDir, node.live, HlcTimestamp.endOfMillisecond(wall.get() + 3_600_000)));

            assertEquals(PitrException.Reason.UNREACHABLE, error.reason());
            assertTrue(error.latest().physical() >= wall.get());
        }
    }

    @Test
    @DisplayName("um backup sem carimbo (0.10.x) não serve de base")
    void aBackupWithoutStampCannotBeABase() throws Exception {
        try (Node node = new Node("legacy")) {
            node.db.put("a", bytes("1"));
        }
        Path backups = tmp.resolve("legacy-backups");
        Files.createDirectories(backups);
        org.rocksdb.RocksDB.loadLibrary();
        try (org.rocksdb.Options db = new org.rocksdb.Options().setCreateIfMissing(true);
             org.rocksdb.RocksDB raw = org.rocksdb.RocksDB.open(db, tmp.resolve("legacy-db").toString());
             BackupEngineOptions opts = new BackupEngineOptions(backups.toString());
             BackupEngine engine = BackupEngine.open(Env.getDefault(), opts)) {
            raw.put(bytes("x"), bytes("y"));
            engine.createNewBackupWithMetadata(raw, "axon-version=7", true);
        }
        try (PhysicalBackups pb = PhysicalBackups.open(backups)) {
            assertEquals(7L, pb.list().get(0).version());
        }

        PitrException error = assertThrows(PitrException.class, () -> PitrRestore.prepare(backups, 1,
            tmp.resolve("legacy").resolve("journal"), tmp.resolve("legacy").resolve("live"),
            HlcTimestamp.endOfMillisecond(wall.get())));

        assertEquals(PitrException.Reason.NO_BASE_STAMP, error.reason());
    }

    @Test
    @DisplayName("a janela alcançável de cada backup aparece sem restaurar nada")
    void coverageReportsTheReachableWindow() {
        try (Node node = new Node("window")) {
            node.db.put("a", bytes("1"));
            wall.addAndGet(10);
            long atBackup = wall.get();
            int base = node.backup();
            wall.addAndGet(10);
            node.db.put("b", bytes("2"));
            node.journal.seal();

            PitrCoverage.Window window = PitrCoverage.window(node.journalDir, node.journal.segments(),
                node.engine.list().stream().filter(i -> i.id() == base).findFirst().orElseThrow().watermark());

            assertTrue(window.from().physical() >= atBackup - 10 && window.from().physical() <= atBackup + 1);
            assertTrue(window.to().physical() >= wall.get());
        }
    }
}
