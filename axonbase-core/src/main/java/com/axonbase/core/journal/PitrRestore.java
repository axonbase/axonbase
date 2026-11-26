package com.axonbase.core.journal;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.storage.CommitWatermark;
import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Restauração a um instante: o backup físico mais a reaplicação, em ordem de arquivamento, dos lotes do journal com
 * carimbo até o instante. Os lotes são absolutos (grava e apaga com o valor final), então reaplicar um que o backup
 * já contém não estraga nada, e por chave o último vence.
 *
 * <p>Prepara a base em {@code <dados>/.restoring}, sem tocar na base ativa; a troca é do chamador
 * ({@link RocksDbBackend#replaceWith}), igual ao restore físico.</p>
 */
public final class PitrRestore {

    /**
     * @param directory      a base preparada, pronta para a troca
     * @param base           marca do backup usado
     * @param appliedBatches lotes reaplicados do journal
     * @param position       maior carimbo e índice da base preparada
     */
    public record Prepared(Path directory, CommitWatermark base, long appliedBatches, CommitWatermark position) {
    }

    private PitrRestore() {
    }

    /**
     * @param backupDir  diretório do {@code BackupEngine}
     * @param backupId   backup físico que serve de base
     * @param journalDir diretório dos segmentos (o próprio ou uma área de importação)
     * @param liveDir    diretório da base ativa
     * @param target     instante pedido, inclusive (veja {@link HlcTimestamp#endOfMillisecond})
     * @throws PitrException quando o instante não é alcançável; nada fica preparado
     */
    public static Prepared prepare(Path backupDir, int backupId, Path journalDir, Path liveDir,
                                   HlcTimestamp target) {
        CommitWatermark base = baseOf(backupDir, backupId);
        List<SegmentInfo> segments;
        try {
            segments = JournalSegments.list(journalDir);
        } catch (IOException e) {
            throw new PitrException(PitrException.Reason.BAD_JOURNAL, e.getMessage(), HlcTimestamp.NONE, e);
        }
        PitrCoverage.Window window = PitrCoverage.window(journalDir, segments, base);
        if (target.compareTo(window.to()) > 0) {
            throw new PitrException(PitrException.Reason.UNREACHABLE,
                "o journal só alcança " + window.to() + " a partir do backup " + backupId, window.to(), null);
        }
        Path prepared = PhysicalBackups.prepareRestore(backupDir, backupId, liveDir);
        boolean done = false;
        try (RocksDbBackend db = new RocksDbBackend(prepared.toString(), RocksDbBackend.SyncMode.OFF)) {
            if (db.persistedPosition().stamp().compareTo(target) > 0) {
                throw new PitrException(PitrException.Reason.BASE_TOO_NEW,
                    "o backup " + backupId + " já contém escritas posteriores ao instante pedido");
            }
            long applied = replay(db, journalDir, segments, base.index(), window.contiguousIndex(), target);
            db.syncWal();
            CommitWatermark position = db.persistedPosition();
            done = true;
            return new Prepared(prepared, base, applied, position);
        } catch (IOException e) {
            throw new PitrException(PitrException.Reason.BAD_JOURNAL, e.getMessage(), HlcTimestamp.NONE, e);
        } finally {
            // o try com recursos fecha a base antes deste bloco
            if (!done) {
                PhysicalBackups.discardPrepared(liveDir);
            }
        }
    }

    private static CommitWatermark baseOf(Path backupDir, int backupId) {
        try (PhysicalBackups backups = PhysicalBackups.open(backupDir)) {
            PhysicalBackups.Info info = backups.list().stream().filter(i -> i.id() == backupId).findFirst()
                .orElseThrow(() -> new PitrException(PitrException.Reason.NO_BACKUP,
                    "backup " + backupId + " não existe"));
            if (info.watermark().equals(CommitWatermark.NONE)) {
                throw new PitrException(PitrException.Reason.NO_BASE_STAMP,
                    "o backup " + backupId + " não tem carimbo; tire um backup físico novo");
            }
            return info.watermark();
        }
    }

    private static long replay(RocksDbBackend db, Path journalDir, List<SegmentInfo> segments, long after, long upTo,
                               HlcTimestamp target) throws IOException {
        long[] applied = new long[1];
        int end = firstCovering(segments, target);
        for (int i = 0; i < segments.size() && i <= end; i++) {
            SegmentInfo segment = segments.get(i);
            if (segment.batches() == 0 || segment.lastIndex() <= after) {
                continue;
            }
            JournalSegments.read(journalDir.resolve(segment.name()), record -> {
                if (record.index() > after && record.index() <= upTo
                    && record.batch().stamp().compareTo(target) <= 0) {
                    db.applyArchived(record.index(), record.batch());
                    applied[0]++;
                }
                return true;
            });
        }
        return applied[0];
    }

    /**
     * Busca binária pelo primeiro segmento cuja marca d'água alcança o instante: dali para a frente nenhum lote tem
     * carimbo até ele. Devolve o tamanho da lista quando nenhum alcança.
     */
    static int firstCovering(List<SegmentInfo> segments, HlcTimestamp target) {
        int low = 0;
        int high = segments.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (segments.get(middle).complete().stamp().compareTo(target) >= 0) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }
}
