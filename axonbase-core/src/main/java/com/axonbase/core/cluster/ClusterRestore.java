package com.axonbase.core.cluster;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.journal.JournalRecord;
import com.axonbase.core.journal.JournalSegments;
import com.axonbase.core.journal.SegmentInfo;
import com.axonbase.core.shard.backup.ShardBackupNode;
import com.axonbase.core.shard.backup.ShardJournalHooks;
import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A base de um cluster num instante: o backup físico de um membro mais o journal, reaplicado em ordem de índice do
 * log, juntando os segmentos de todos os nós.
 *
 * <p>O diretório pronto serve a qualquer nó de um cluster novo, que sobe com o diretório do Raft vazio: a base diz em
 * que índice parou e não traz a lista de membros antiga.</p>
 */
public final class ClusterRestore {

    private static final String CONFIG_KEY = "__raft/config";

    /**
     * @param directory      a base pronta
     * @param baseIndex      índice do log em que o backup foi tirado
     * @param lastIndex      índice do último lote aplicado
     * @param appliedBatches lotes reaplicados do journal
     * @param lastStamp      carimbo do último lote reaplicado, ou {@link HlcTimestamp#NONE} se nenhum
     */
    public record Prepared(Path directory, long baseIndex, long lastIndex, long appliedBatches,
                           HlcTimestamp lastStamp) {
    }

    /** O instante pedido não é alcançável com este backup e estes segmentos. Nada é entregue. */
    public static class Unreachable extends RuntimeException {
        private final HlcTimestamp latest;

        Unreachable(String message, HlcTimestamp latest) {
            super(message);
            this.latest = latest;
        }

        /** O último carimbo que se consegue alcançar. */
        public HlcTimestamp latest() {
            return latest;
        }
    }

    /** O backup usado já passa do instante pedido: um mais antigo ainda pode servir. */
    public static final class BaseTooNew extends Unreachable {
        BaseTooNew(String message, HlcTimestamp latest) {
            super(message, latest);
        }
    }

    private record Source(Path file, SegmentInfo info) {
    }

    private ClusterRestore() {
    }

    /**
     * @param backupDir   diretório do {@code BackupEngine} com o backup
     * @param backupId    o backup que serve de base
     * @param journalDirs os diretórios de segmentos, um por nó que arquivou
     * @param target      diretório da base nova, vazio ou inexistente
     * @param until       instante pedido, inclusive, ou {@code null} para tudo o que os segmentos têm
     * @throws Unreachable quando falta um lote no caminho até o instante
     */
    public static Prepared prepare(Path backupDir, int backupId, List<Path> journalDirs, Path target,
                                   HlcTimestamp until) {
        requireEmpty(target);
        Path prepared = PhysicalBackups.prepareRestore(backupDir, backupId, target);
        boolean done = false;
        try {
            Prepared result;
            try (RocksDbBackend db = new RocksDbBackend(prepared.toString(), RocksDbBackend.SyncMode.OFF)) {
                result = replay(db, journalDirs, target, until);
                db.syncWal();
            }
            PhysicalBackups.commitPrepared(target);
            done = true;
            return result;
        } finally {
            if (!done) {
                PhysicalBackups.discardPrepared(target);
            }
        }
    }

    private static Prepared replay(RocksDbBackend db, List<Path> journalDirs, Path target, HlcTimestamp until) {
        RaftApplier.Applied base = RaftApplier.applied(db).orElseThrow(() -> new IllegalStateException(
            "o backup não veio de um nó de cluster: não diz em que ponto do log foi tirado"));
        if (until != null && db.recordedPosition().stamp().compareTo(until) > 0) {
            throw new BaseTooNew("o backup já contém escritas posteriores ao instante pedido; use um mais antigo",
                db.recordedPosition().stamp());
        }
        Merge merge = new Merge(db, base.index() + 1, until);
        List<Source> sources = sources(journalDirs);
        for (Source source : sources) {
            if (source.info().batches() == 0 || source.info().lastIndex() < merge.next) {
                continue;
            }
            // em ordem de primeiro índice: daqui em diante ninguém tem o índice que falta
            if (merge.finished || source.info().firstIndex() > merge.next) {
                break;
            }
            try {
                JournalSegments.read(source.file(), merge::offer);
            } catch (IOException e) {
                throw new UncheckedIOException("segmento ilegível: " + source.file(), e);
            }
        }
        merge.requireReached(sources);
        // a base restaurada começa uma história nova: sem a lista de membros e, se o grupo era um shard, sem a
        // identificação da história antiga, para um backup de shards não misturar os journals das duas
        db.applyReplicated(Map.of(RaftApplier.APPLIED_KEY, RaftApplier.marker(merge.next - 1, base.term())),
            Set.of(CONFIG_KEY, ShardBackupNode.INCARNATION_KEY, ShardJournalHooks.BARRIER_KEY));
        return new Prepared(target, base.index(), merge.next - 1, merge.applied, merge.lastStamp);
    }

    private static List<Source> sources(List<Path> journalDirs) {
        List<Source> sources = new ArrayList<>();
        for (Path directory : journalDirs) {
            try {
                for (SegmentInfo info : JournalSegments.list(directory)) {
                    sources.add(new Source(directory.resolve(info.name()), info));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("diretório de journal ilegível: " + directory, e);
            }
        }
        sources.sort(Comparator.comparingLong((Source s) -> s.info().firstIndex()));
        return sources;
    }

    /** Aplica os lotes em ordem de índice, guardando os que chegam adiantados até o buraco ser preenchido. */
    private static final class Merge {
        private final RocksDbBackend db;
        private final HlcTimestamp until;
        private final TreeMap<Long, CommittedBatch> ahead = new TreeMap<>();
        long next;
        long applied;
        HlcTimestamp lastStamp = HlcTimestamp.NONE;
        /** Apareceu o primeiro lote depois do instante pedido: não há mais o que aplicar. */
        boolean finished;

        Merge(RocksDbBackend db, long next, HlcTimestamp until) {
            this.db = db;
            this.next = next;
            this.until = until;
        }

        boolean offer(JournalRecord record) {
            if (record.index() < next) {
                return true;
            }
            CommittedBatch other = ahead.put(record.index(), record.batch());
            if (other != null && !other.stamp().equals(record.batch().stamp())) {
                throw new IllegalStateException("os segmentos misturam histórias diferentes no índice "
                    + record.index() + ": arquive cada cluster numa época própria");
            }
            while (!finished && !ahead.isEmpty() && ahead.firstKey() == next) {
                CommittedBatch batch = ahead.pollFirstEntry().getValue();
                if (until != null && batch.stamp().compareTo(until) > 0) {
                    finished = true;
                } else {
                    db.applyArchived(next, batch);
                    lastStamp = HlcTimestamp.max(lastStamp, batch.stamp());
                    applied++;
                    next++;
                }
            }
            return !finished;
        }

        void requireReached(List<Source> sources) {
            if (finished) {
                return;
            }
            boolean beyond = !ahead.isEmpty() || sources.stream()
                .anyMatch(s -> s.info().batches() > 0 && s.info().lastIndex() >= next);
            if (until == null) {
                if (beyond) {
                    throw new Unreachable("falta o lote " + next + " em todos os segmentos, e há lotes depois dele",
                        lastStamp);
                }
                return;
            }
            // um segmento pode provar que não houve escrita até o instante
            boolean covered = sources.stream().anyMatch(s -> s.info().complete().stamp().compareTo(until) >= 0
                && s.info().complete().index() < next);
            if (!covered) {
                throw new Unreachable("os segmentos não chegam ao instante pedido: o último lote aplicável é o "
                    + (next - 1), lastStamp);
            }
        }
    }

    private static void requireEmpty(Path target) {
        if (!Files.isDirectory(target)) {
            return;
        }
        try (Stream<Path> entries = Files.list(target)) {
            if (entries.anyMatch(p -> !p.getFileName().toString().startsWith("."))) {
                throw new IllegalStateException("o destino da restauração não está vazio: " + target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
