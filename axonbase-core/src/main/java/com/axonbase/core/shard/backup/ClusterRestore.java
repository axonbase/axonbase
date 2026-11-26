package com.axonbase.core.shard.backup;

import com.axonbase.core.cluster.RaftApplier;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.journal.JournalRecord;
import com.axonbase.core.journal.JournalSegments;
import com.axonbase.core.journal.PitrException;
import com.axonbase.core.journal.SegmentInfo;
import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Restaura todos os shards de um manifesto ao mesmo instante: cada um parte do backup físico e reaplica, em ordem de
 * índice, os lotes do journal com carimbo até o instante.
 *
 * <p>Um shard só é aceito se o journal tiver, sem falhas, todos os índices desde o que o backup contém até um lote
 * posterior ao instante, que prova que nada ficou de fora. Ou todos os shards ficam preparados, ou nenhum.</p>
 *
 * <p>A base preparada perde a posição do Raft: cada membro do shard recebe a mesma cópia e começa com o diretório do
 * Raft vazio.</p>
 */
public final class ClusterRestore {

    /** De onde ler o backup e o journal de um shard (os do manifesto, ou cópias deles). */
    public record Source(Path backupDir, Path journalDir) {
    }

    /**
     * @param baseIndex      índice do log que o backup continha
     * @param appliedBatches lotes reaplicados do journal
     * @param restoredUntil  carimbo do último lote da base preparada
     */
    public record Restored(long baseIndex, long appliedBatches, HlcTimestamp restoredUntil) {
    }

    private ClusterRestore() {
    }

    /**
     * @param target      instante pedido, inclusive; {@code null} é o corte do manifesto
     * @param destination recebe um diretório de dados por shard, com o nome dele; nenhum pode existir
     */
    public static Map<String, Restored> restore(ClusterManifest manifest, HlcTimestamp target,
                                                Map<String, Source> sources, Path destination) {
        HlcTimestamp instant = target == null ? manifest.cut() : target;
        if (instant.compareTo(manifest.cut()) < 0) {
            throw new PitrException(PitrException.Reason.BASE_TOO_NEW, "o instante " + instant
                + " é anterior ao corte " + manifest.cut() + " deste manifesto: use um mais antigo");
        }
        for (ClusterManifest.ShardBackup shard : manifest.shards()) {
            if (!sources.containsKey(shard.shard())) {
                throw new PitrException(PitrException.Reason.NO_BACKUP, "falta a origem do shard " + shard.shard());
            }
            if (Files.exists(destination.resolve(shard.shard()))) {
                throw new IllegalArgumentException("o destino já existe: " + destination.resolve(shard.shard()));
            }
        }
        List<Path> staging = new ArrayList<>();
        boolean done = false;
        try {
            Map<String, Restored> out = new LinkedHashMap<>();
            for (ClusterManifest.ShardBackup shard : manifest.shards()) {
                Path prepared = destination.resolve(shard.shard() + ".restoring");
                deleteTree(prepared);
                staging.add(prepared);
                out.put(shard.shard(), prepare(shard, sources.get(shard.shard()), prepared, instant));
            }
            for (ClusterManifest.ShardBackup shard : manifest.shards()) {
                Files.move(destination.resolve(shard.shard() + ".restoring"), destination.resolve(shard.shard()),
                    StandardCopyOption.ATOMIC_MOVE);
            }
            done = true;
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("falha ao publicar os shards restaurados em " + destination, e);
        } finally {
            if (!done) {
                staging.forEach(ClusterRestore::deleteTree);
            }
        }
    }

    private static Restored prepare(ClusterManifest.ShardBackup shard, Source source, Path directory,
                                    HlcTimestamp instant) {
        PhysicalBackups.restore(source.backupDir(), shard.backupId(), directory);
        try (RocksDbBackend db = new RocksDbBackend(directory.toString(), RocksDbBackend.SyncMode.OFF)) {
            String incarnation = db.get(ShardBackupNode.INCARNATION_KEY)
                .map(raw -> new String(raw, StandardCharsets.UTF_8)).orElse("");
            if (!incarnation.equals(shard.incarnation())) {
                throw new PitrException(PitrException.Reason.BAD_JOURNAL,
                    "o backup do shard " + shard.shard() + " é de outra história");
            }
            long base = RaftApplier.applied(db).map(RaftApplier.Applied::index).orElseThrow(
                () -> new PitrException(PitrException.Reason.NO_BASE_STAMP,
                    "o backup do shard " + shard.shard() + " não tem posição do log"));
            long[] applied = new long[1];
            HlcTimestamp[] last = {HlcTimestamp.NONE};
            Reached reached = walk(source.journalDir(), base, instant, record -> {
                byte[] barrier = record.batch().puts().get(ShardJournalHooks.BARRIER_KEY);
                if (barrier != null && !new String(barrier, StandardCharsets.UTF_8).equals(shard.incarnation())) {
                    throw new PitrException(PitrException.Reason.BAD_JOURNAL,
                        "o journal do shard " + shard.shard() + " é de outra história");
                }
                db.applyArchived(record.index(), record.batch());
                applied[0]++;
                last[0] = record.batch().stamp();
            });
            if (!reached.passed()) {
                throw new PitrException(PitrException.Reason.UNREACHABLE, "o journal do shard " + shard.shard()
                    + " só vai até o índice " + reached.index() + " (" + reached.stamp() + "), sem provar o instante "
                    + instant, reached.stamp(), null);
            }
            // a cópia vira o começo de um cluster novo: sem posição do log e sem a história antiga
            Set<String> forget = new java.util.LinkedHashSet<>(db.keysWithPrefix("__raft/"));
            forget.add(ShardBackupNode.INCARNATION_KEY);
            forget.add(ShardJournalHooks.BARRIER_KEY);
            db.commit(Map.of(), Map.of(), forget, Set.of());
            db.syncWal();
            return new Restored(base, applied[0], last[0]);
        }
    }

    /**
     * @param passed um lote posterior ao instante apareceu, então tudo até ele está presente
     * @param index  último índice contíguo visto
     * @param stamp  carimbo dele
     */
    private record Reached(boolean passed, long index, HlcTimestamp stamp) {
    }

    /**
     * Percorre o journal a partir do índice seguinte à base, sem pular nenhum, entregando os lotes até o instante.
     * Para no primeiro lote posterior a ele, ou no primeiro buraco.
     */
    private static Reached walk(Path journalDir, long base, HlcTimestamp instant, Consumer<JournalRecord> apply) {
        long[] expected = {base + 1};
        boolean[] passed = {false};
        boolean[] hole = {false};
        HlcTimestamp[] last = {HlcTimestamp.NONE};
        try {
            for (SegmentInfo segment : JournalSegments.list(journalDir)) {
                if (passed[0] || hole[0]) {
                    break;
                }
                if (segment.batches() == 0 || segment.lastIndex() < expected[0]) {
                    continue;
                }
                JournalSegments.read(journalDir.resolve(segment.name()), record -> {
                    // um índice repetido é a mesma entrada arquivada de novo depois de um reinício
                    if (record.index() < expected[0]) {
                        return true;
                    }
                    if (record.index() > expected[0]) {
                        hole[0] = true;
                        return false;
                    }
                    HlcTimestamp stamp = record.batch().stamp();
                    if (stamp.compareTo(last[0]) <= 0) {
                        throw new PitrException(PitrException.Reason.BAD_JOURNAL,
                            "o carimbo do lote " + record.index() + " não cresce com o índice");
                    }
                    if (stamp.compareTo(instant) > 0) {
                        passed[0] = true;
                        return false;
                    }
                    apply.accept(record);
                    last[0] = stamp;
                    expected[0]++;
                    return true;
                });
            }
        } catch (IOException e) {
            throw new PitrException(PitrException.Reason.BAD_JOURNAL, e.getMessage(), HlcTimestamp.NONE, e);
        }
        return new Reached(passed[0], expected[0] - 1, last[0]);
    }

    /** Confere, sem aplicar nada, que o journal tem todos os índices depois da base até {@code upTo}. */
    static void requireContiguous(Path journalDir, long baseIndex, long upTo) {
        Reached reached = walk(journalDir, baseIndex, HlcTimestamp.endOfMillisecond(Long.MAX_VALUE), record -> { });
        if (reached.index() < upTo) {
            throw new PitrException(PitrException.Reason.UNREACHABLE, "falta o índice " + (reached.index() + 1)
                + " no journal em " + journalDir + ": tire um backup novo", reached.stamp(), null);
        }
    }

    private static void deleteTree(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("falha ao apagar " + directory, e);
        }
    }
}
