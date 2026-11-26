package com.axonbase.core.journal;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.storage.CommitWatermark;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Até onde dá para restaurar a partir de um backup, olhando só o índice dos segmentos.
 *
 * <p>Como o índice dos lotes não tem buracos e cresce junto com o carimbo, partindo do que o backup já contém basta
 * achar o maior índice {@code K} sem falhas no journal: todo instante até o carimbo de {@code K} (ou até uma marca
 * d'água que não passe de {@code K}) está completo.</p>
 */
public final class PitrCoverage {

    /**
     * @param from           carimbo do backup; instantes anteriores precisam de um backup mais antigo
     * @param to             último instante com todos os lotes presentes
     * @param contiguousIndex maior índice sem falhas a partir do backup
     */
    public record Window(HlcTimestamp from, HlcTimestamp to, long contiguousIndex) {
    }

    private PitrCoverage() {
    }

    public static Window window(List<SegmentInfo> segments, CommitWatermark base) {
        List<IndexRange> present = new ArrayList<>();
        for (SegmentInfo s : segments) {
            if (s.batches() == 0) {
                continue;
            }
            long from = s.firstIndex();
            for (IndexRange hole : s.holes()) {
                present.add(new IndexRange(from, hole.from() - 1));
                from = hole.to() + 1;
            }
            present.add(new IndexRange(from, s.lastIndex()));
        }
        present.sort(Comparator.comparingLong(IndexRange::from));
        long contiguous = base.index();
        for (IndexRange range : present) {
            if (range.from() > contiguous + 1) {
                break;
            }
            contiguous = Math.max(contiguous, range.to());
        }
        HlcTimestamp to = base.stamp();
        for (SegmentInfo s : segments) {
            if (s.batches() > 0 && s.lastIndex() <= contiguous) {
                to = HlcTimestamp.max(to, s.lastStamp());
            }
            if (s.complete().index() <= contiguous) {
                to = HlcTimestamp.max(to, s.complete().stamp());
            }
        }
        return new Window(base.stamp(), to, contiguous);
    }
}
