package com.axonbase.core.cluster;

import com.axonbase.core.dr.ArchiveLayout;
import com.axonbase.core.dr.ArchiveStore;
import com.axonbase.core.dr.DirectoryArchiveStore;
import com.axonbase.core.dr.DrArchiver;
import com.axonbase.core.dr.DrRestore;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.journal.JournalOptions;
import com.axonbase.core.journal.PitrJournal;
import com.axonbase.core.shard.backup.ShardBackupNode;
import com.axonbase.core.shard.backup.ShardJournalHooks;
import com.axonbase.core.storage.CommitWatermark;
import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Restauração de cluster: backup mais journal de vários nós")
class ClusterRestoreTest {

    /** O backup de partida para no índice 5. */
    private static final long BASE = 5;

    @TempDir
    Path dir;

    /** Carimbos dos lotes do journal: bem depois do backup, um por segundo. */
    private final long epoch = System.currentTimeMillis() + 60_000;

    private HlcTimestamp stamp(long index) {
        return new HlcTimestamp(epoch + (index - BASE) * 1_000, 0);
    }

    private Path backup() {
        Path backups = dir.resolve("backups");
        try (RocksDbBackend source = new RocksDbBackend(dir.resolve("origem").toString());
             PhysicalBackups engine = PhysicalBackups.open(backups)) {
            source.applyReplicated(Map.of(RaftApplier.APPLIED_KEY, RaftApplier.marker(BASE, 3),
                "base", bytes("b"), "__raft/config", bytes("10.0.0.1:9000")), Set.of());
            engine.create(source);
        }
        return backups;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private Path journal(String node, long... indexes) {
        return skewedJournal(node, 0, indexes);
    }

    /** Um journal com os índices dados; {@code skew} muda os carimbos, para simular outra história. */
    private Path skewedJournal(String node, long skew, long[] indexes) {
        return segments(node, skew, true, indexes);
    }

    private Path segments(String node, long skew, boolean onePerBatch, long[] indexes) {
        Path directory = dir.resolve("journal-" + node);
        AtomicReference<CommitWatermark> mark = new AtomicReference<>(CommitWatermark.NONE);
        JournalOptions options = JournalOptions.defaults().withIdleHeartbeat(Duration.ZERO);
        try (PitrJournal journal = PitrJournal.open(directory, options, mark::get)) {
            for (long index : indexes) {
                HlcTimestamp at = new HlcTimestamp(stamp(index).physical() + skew, 0);
                journal.offer(index, new CommittedBatch("tx" + index,
                    Map.of("k" + index, bytes("v" + index)), Set.of(), false, at));
                mark.set(new CommitWatermark(at, index));
                if (onePerBatch) {
                    journal.seal();
                }
            }
        }
        return directory;
    }

    private static long[] range(long from, long to) {
        return LongStream.rangeClosed(from, to).toArray();
    }

    private static Set<String> keys(Path data) {
        try (RocksDbBackend db = new RocksDbBackend(data.toString())) {
            return Set.copyOf(db.keysWithPrefix(""));
        }
    }

    @Test
    @DisplayName("junta nós com buracos diferentes e grava o ponto do log, sem a lista antiga de membros")
    void mergesNodes() {
        Path backups = backup();
        Path first = journal("n1", 4, 5, 6, 7, 10);
        Path second = journal("n2", 6, 8, 9);
        Path target = dir.resolve("restaurada");

        ClusterRestore.Prepared prepared =
            ClusterRestore.prepare(backups, 1, List.of(first, second), target, null);

        assertEquals(BASE, prepared.baseIndex());
        assertEquals(10, prepared.lastIndex());
        assertEquals(5, prepared.appliedBatches());
        assertEquals(stamp(10), prepared.lastStamp());
        Set<String> keys = keys(target);
        assertTrue(keys.containsAll(List.of("base", "k6", "k7", "k8", "k9", "k10")), keys.toString());
        assertFalse(keys.contains("__raft/config"), "a lista de membros antiga ficou na base");
        try (RocksDbBackend db = new RocksDbBackend(target.toString())) {
            assertEquals(new RaftApplier.Applied(10, 3), RaftApplier.applied(db).orElseThrow());
        }
    }

    @Test
    @DisplayName("para no instante pedido, inclusive")
    void stopsAtInstant() {
        Path backups = backup();
        Path journal = journal("n1", range(6, 12));
        Path target = dir.resolve("restaurada");

        ClusterRestore.Prepared prepared = ClusterRestore.prepare(backups, 1, List.of(journal), target,
            HlcTimestamp.endOfMillisecond(stamp(9).physical()));

        assertEquals(9, prepared.lastIndex());
        Set<String> keys = keys(target);
        assertTrue(keys.contains("k9") && !keys.contains("k10"), keys.toString());
    }

    @Test
    @DisplayName("um lote que falta em todos os nós interrompe, dizendo até onde dá")
    void gapStops() {
        Path backups = backup();
        Path journal = journal("n1", 6, 7, 9, 10);
        Path target = dir.resolve("restaurada");

        ClusterRestore.Unreachable failure = assertThrows(ClusterRestore.Unreachable.class,
            () -> ClusterRestore.prepare(backups, 1, List.of(journal), target, null));

        assertEquals(stamp(7), failure.latest());
        assertTrue(keys(dir.resolve("origem")).contains("base"));
        assertFalse(java.nio.file.Files.exists(target.resolve("CURRENT")), "ficou uma base pela metade");
        // antes do buraco o instante é alcançável; depois do 7 não, porque o 8 poderia ser anterior ao instante
        assertEquals(6, ClusterRestore.prepare(backups, 1, List.of(journal), dir.resolve("ate6"),
            HlcTimestamp.endOfMillisecond(stamp(7).physical() - 1)).lastIndex());
    }

    @Test
    @DisplayName("sem instante, a marca à frente do último lote prova que falta o fim do histórico")
    void lostTailStopsRecoveryWithoutInstant() {
        Path backups = backup();
        Path directory = dir.resolve("journal-cauda");
        AtomicReference<CommitWatermark> mark = new AtomicReference<>(CommitWatermark.NONE);
        JournalOptions options = JournalOptions.defaults().withIdleHeartbeat(Duration.ZERO);
        try (PitrJournal journal = PitrJournal.open(directory, options, mark::get)) {
            for (long index : new long[] {6, 7}) {
                journal.offer(index, new CommittedBatch("tx" + index,
                    Map.of("k" + index, bytes("v" + index)), Set.of(), false, stamp(index)));
                mark.set(new CommitWatermark(stamp(index), index));
            }
            journal.seal();
            // os lotes 8 e 9 foram confirmados com a fila do journal cheia: só a marca andou
            mark.set(new CommitWatermark(stamp(9), 9));
            journal.seal();
        }
        Path target = dir.resolve("restaurada");

        ClusterRestore.Unreachable failure = assertThrows(ClusterRestore.Unreachable.class,
            () -> ClusterRestore.prepare(backups, 1, List.of(directory), target, null));

        assertEquals(stamp(7), failure.latest());
        assertFalse(java.nio.file.Files.exists(target.resolve("CURRENT")), "ficou uma base pela metade");
    }

    @Test
    @DisplayName("um instante depois do que os segmentos provam é recusado")
    void beyondCoverage() {
        Path backups = backup();
        Path journal = journal("n1", 6, 7);

        assertThrows(ClusterRestore.Unreachable.class, () -> ClusterRestore.prepare(backups, 1, List.of(journal),
            dir.resolve("restaurada"), HlcTimestamp.endOfMillisecond(stamp(20).physical())));
    }

    @Test
    @DisplayName("um instante anterior ao backup pede um backup mais antigo")
    void backupTooNew() {
        Path backups = backup();

        assertThrows(ClusterRestore.Unreachable.class, () -> ClusterRestore.prepare(backups, 1, List.of(),
            dir.resolve("restaurada"), new HlcTimestamp(1, 0)));
    }

    @Test
    @DisplayName("histórias misturadas no mesmo índice são recusadas, e o destino cheio também")
    void refusesMixedHistoryAndUsedTarget() {
        Path backups = backup();
        // o índice 8 chega adiantado nos dois, com carimbos diferentes
        Path first = segments("n1", 0, false, new long[] {6, 8});
        Path second = segments("n2", 500, false, new long[] {6, 8});

        assertThrows(IllegalStateException.class, () -> ClusterRestore.prepare(backups, 1,
            List.of(first, second), dir.resolve("restaurada"), null));
        assertThrows(IllegalStateException.class, () -> ClusterRestore.prepare(backups, 1, List.of(first),
            dir.resolve("origem"), null));
    }

    @Test
    @DisplayName("na recuperação, um backup que já passa do instante cede lugar ao anterior")
    void recoveryFallsBackToAnOlderBackup() throws Exception {
        Path backups = dir.resolve("backups");
        try (RocksDbBackend source = new RocksDbBackend(dir.resolve("origem").toString());
             PhysicalBackups engine = PhysicalBackups.open(backups)) {
            for (long index = BASE; index <= BASE + 3; index++) {
                source.applyArchived(index, new CommittedBatch("tx" + index,
                    Map.of(RaftApplier.APPLIED_KEY, RaftApplier.marker(index, 3), "k" + index, bytes("v" + index)),
                    Set.of(), false, stamp(index)));
                if (index == BASE || index == BASE + 3) {
                    engine.create(source);
                }
            }
        }
        Path archive = dir.resolve("arquivo");
        ArchiveStore store = new DirectoryArchiveStore(archive);
        ArchiveLayout layout = new ArchiveLayout("axon-dr", "e1", "g");
        DrArchiver archiver = new DrArchiver(store, layout, "n1");
        assertEquals(2, archiver.pushBackups(backups));
        assertTrue(archiver.pushSegments(journal("n1", range(BASE + 1, BASE + 3))) > 0);
        HlcTimestamp until = HlcTimestamp.endOfMillisecond(stamp(BASE + 1).physical());
        // o carimbo do descritor diz que o segundo backup serve, mas a base dele já passa do instante
        Path second = archive.resolve(store.list("axon-dr/").stream().filter(k -> k.contains("/descriptors/2-"))
            .findFirst().orElseThrow());
        java.nio.file.Files.move(second, second.resolveSibling("2-" + stamp(BASE + 1).physical() + "-0"));

        DrRestore.Result result = DrRestore.restore(store, layout, dir.resolve("work"),
            dir.resolve("restaurada"), until, 2);

        assertEquals(1, result.backupId());
        assertEquals(BASE + 1, result.prepared().lastIndex());
        Set<String> keys = keys(dir.resolve("restaurada"));
        assertTrue(keys.contains("k6") && !keys.contains("k7"), keys.toString());
    }

    @Test
    @DisplayName("a base restaurada de um shard não guarda a identificação da história antiga, nem a do journal")
    void forgetsShardIdentity() {
        Path backups = dir.resolve("backups-shard");
        try (RocksDbBackend source = new RocksDbBackend(dir.resolve("origem-shard").toString());
             PhysicalBackups engine = PhysicalBackups.open(backups)) {
            source.applyReplicated(Map.of(RaftApplier.APPLIED_KEY, RaftApplier.marker(BASE, 3), "base", bytes("b"),
                ShardBackupNode.INCARNATION_KEY, bytes("historia-antiga"),
                ShardJournalHooks.BARRIER_KEY, bytes("historia-antiga")), Set.of());
            engine.create(source);
        }
        // uma barreira também no journal: a reaplicação a regrava, e ela precisa sair mesmo assim
        Path directory = dir.resolve("journal-shard");
        AtomicReference<CommitWatermark> mark = new AtomicReference<>(CommitWatermark.NONE);
        try (PitrJournal journal = PitrJournal.open(directory,
                JournalOptions.defaults().withIdleHeartbeat(Duration.ZERO), mark::get)) {
            journal.offer(BASE + 1, new CommittedBatch("barreira", Map.of("k6", bytes("v6"),
                ShardJournalHooks.BARRIER_KEY, bytes("historia-antiga")), Set.of(), false, stamp(BASE + 1)));
            mark.set(new CommitWatermark(stamp(BASE + 1), BASE + 1));
            journal.seal();
        }
        Path target = dir.resolve("restaurada-shard");

        ClusterRestore.Prepared prepared = ClusterRestore.prepare(backups, 1, List.of(directory), target, null);

        assertEquals(BASE + 1, prepared.lastIndex());
        Set<String> keys = keys(target);
        assertTrue(keys.containsAll(List.of("base", "k6")), keys.toString());
        assertFalse(keys.contains(ShardBackupNode.INCARNATION_KEY), keys.toString());
        assertFalse(keys.contains(ShardJournalHooks.BARRIER_KEY), keys.toString());
    }

    @Test
    @DisplayName("o journal de um nó arquiva cada lote confirmado e mantém os ganchos anteriores")
    void clusterJournalDecoratesHooks() throws Exception {
        StringBuilder calls = new StringBuilder();
        ReplicationHooks previous = new ReplicationHooks() {
            @Override
            public void committed(long index, long term, CommittedBatch batch) {
                calls.append(index).append(';');
            }

            @Override
            public boolean accept(CommittedBatch batch) {
                return false;
            }
        };
        Path directory = dir.resolve("journal-hooks");
        CommittedBatch batch = new CommittedBatch("t", Map.of("k", bytes("v")), Set.of(), false, stamp(6));
        try (ShardJournalHooks journal = ShardJournalHooks.open(directory, JournalOptions.defaults(), previous)) {
            assertEquals(Long.MAX_VALUE, journal.replayFrom(), "um journal novo não pede a história");
            assertFalse(journal.accept(batch));
            assertEquals(batch, journal.stamp(batch));
            journal.recovered(batch);
            journal.committed(6, 1, batch);
            assertEquals(6, journal.journal().seal().complete().index());
        }
        assertEquals("6;", calls.toString());
        try (ShardJournalHooks reopened = ShardJournalHooks.open(directory, JournalOptions.defaults(), previous)) {
            assertEquals(7, reopened.replayFrom(), "o que veio depois do arquivado é pedido de volta");
            assertEquals(6, reopened.journal().seal().complete().index(), "a marca precisa sobreviver à reabertura");
        }
    }
}
